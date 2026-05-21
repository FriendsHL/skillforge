package com.skillforge.server.attribution;

import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.repository.AgentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * ATTRIBUTION-DISPATCHER-AGENT (V93 seed): boot-time swapper for the
 * {@code attribution-dispatcher} agent's {@code system_prompt}.
 *
 * <p>Structural twin of {@link AttributionCuratorBootstrap} — only the agent
 * name + classpath resource path differ. The V93 Flyway migration seeds
 * {@code t_agent.system_prompt = 'SEE_FILE:attribution-dispatcher-system-prompt.md'}
 * to avoid escaping the multi-line prompt through SQL string literals. This
 * bootstrap reads the prompt from
 * {@code classpath:attribution-dispatcher-system-prompt.md} once on boot and
 * swaps the placeholder out.
 *
 * <p>Idempotency: if the {@code system_prompt} no longer starts with the
 * {@code SEE_FILE:} sentinel (operator manually edited, or a prior boot
 * already swapped), we leave it alone — operator edits win. We never
 * overwrite non-placeholder prompts.
 *
 * <p>Why {@link ApplicationReadyEvent} instead of {@code @PostConstruct}:
 * the V93 migration must finish first (Flyway runs before
 * {@code ApplicationReadyEvent} but after some {@code @PostConstruct}
 * hooks), and we want the agent row to already exist when we run.
 */
@Component
public class AttributionDispatcherBootstrap {

    private static final Logger log = LoggerFactory.getLogger(AttributionDispatcherBootstrap.class);

    public static final String AGENT_NAME = "attribution-dispatcher";
    static final String SEE_FILE_SENTINEL_PREFIX = "SEE_FILE:";
    public static final String PROMPT_RESOURCE_PATH = "attribution-dispatcher-system-prompt.md";

    private final AgentRepository agentRepository;

    public AttributionDispatcherBootstrap(AgentRepository agentRepository) {
        this.agentRepository = agentRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void swapSystemPromptOnBoot() {
        Optional<AgentEntity> opt;
        try {
            opt = agentRepository.findFirstByName(AGENT_NAME);
        } catch (Exception e) {
            // If the V93 migration hasn't applied yet (test profile bypassing
            // Flyway, etc.) we don't want to crash the whole app. Just log + move on.
            log.warn("[AttributionDispatcherBootstrap] lookup failed (migration not yet applied?): {}", e.getMessage());
            return;
        }
        if (opt.isEmpty()) {
            log.debug("[AttributionDispatcherBootstrap] no attribution-dispatcher agent row — skipping swap");
            return;
        }
        AgentEntity agent = opt.get();
        // SYSTEM-AGENT-TYPING defense-in-depth — ensure agent_type='system' on
        // every boot. V93 seeds agent_type='system' explicitly to avoid this
        // self-heal save in steady-state, but a hand-rolled / pre-V89 row
        // would land here as 'user' and we want to repair it.
        if (!"system".equals(agent.getAgentType())) {
            agent.setAgentType("system");
            agentRepository.save(agent);
            log.info("[AttributionDispatcherBootstrap] agentId={} agent_type self-healed to 'system'", agent.getId());
        }
        String current = agent.getSystemPrompt();
        if (current == null || !current.startsWith(SEE_FILE_SENTINEL_PREFIX)) {
            // Already swapped, or operator hand-edited — leave alone.
            log.debug("[AttributionDispatcherBootstrap] agentId={} prompt already non-placeholder ({} chars) — leaving alone",
                    agent.getId(), current == null ? 0 : current.length());
            return;
        }

        String resourcePath = current.substring(SEE_FILE_SENTINEL_PREFIX.length()).trim();
        if (resourcePath.isEmpty()) {
            resourcePath = PROMPT_RESOURCE_PATH;
        }
        String prompt = loadPromptFromClasspath(resourcePath);
        if (prompt == null) {
            // W3 explicit coverage: resource missing → leave the SEE_FILE
            // placeholder in place + log.warn. We never overwrite with null
            // (would NPE downstream when ChatService passes system_prompt to
            // the LLM provider).
            log.warn("[AttributionDispatcherBootstrap] agentId={} cannot resolve prompt resource '{}' — leaving placeholder",
                    agent.getId(), resourcePath);
            return;
        }

        agent.setSystemPrompt(prompt);
        agentRepository.save(agent);
        log.info("[AttributionDispatcherBootstrap] agentId={} swapped placeholder for {} ({} chars)",
                agent.getId(), resourcePath, prompt.length());
    }

    /**
     * Package-private for unit testing. Returns null when the resource is missing or
     * unreadable (caller logs and skips — never crash boot just because the prompt
     * file got moved).
     */
    String loadPromptFromClasspath(String resourcePath) {
        ClassPathResource resource = new ClassPathResource(resourcePath);
        if (!resource.exists()) {
            return null;
        }
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[AttributionDispatcherBootstrap] failed to read resource '{}': {}",
                    resourcePath, e.getMessage());
            return null;
        }
    }
}
