package com.skillforge.server.bootstrap;

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
 * V5 EVAL-DYNAMIC-USER-SIM Phase 1.2 (V85): boot-time swapper for the
 * {@code user-simulator} agent's {@code system_prompt}.
 *
 * <p>Structural twin of
 * {@link com.skillforge.server.attribution.AttributionCuratorBootstrap}
 * (V3/V81), differs only by agent name + classpath resource path. V85
 * Flyway seeds {@code t_agent.system_prompt =
 * 'SEE_FILE:user-simulator-system-prompt.md'} to avoid SQL-string-escaping the
 * multi-line persona / behavior-rule prompt. This bootstrap reads the prompt
 * from {@code classpath:user-simulator-system-prompt.md} on boot and swaps the
 * placeholder out.
 *
 * <p>Idempotency: if the {@code system_prompt} no longer starts with the
 * {@code SEE_FILE:} sentinel (operator manually edited, or a prior boot
 * already swapped), we leave it alone — operator edits win.
 *
 * <p>Why {@link ApplicationReadyEvent} instead of {@code @PostConstruct}:
 * the V85 migration must finish first (Flyway runs before
 * {@code ApplicationReadyEvent}), and we want the agent row to already exist.
 */
@Component
public class UserSimulatorBootstrap {

    private static final Logger log = LoggerFactory.getLogger(UserSimulatorBootstrap.class);

    public static final String AGENT_NAME = "user-simulator";
    static final String SEE_FILE_SENTINEL_PREFIX = "SEE_FILE:";
    static final String PROMPT_RESOURCE_PATH = "system-agents/user-simulator.system.md";

    private final AgentRepository agentRepository;

    public UserSimulatorBootstrap(AgentRepository agentRepository) {
        this.agentRepository = agentRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void swapSystemPromptOnBoot() {
        Optional<AgentEntity> opt;
        try {
            opt = agentRepository.findFirstByName(AGENT_NAME);
        } catch (Exception e) {
            log.warn("[UserSimulatorBootstrap] lookup failed (migration not yet applied?): {}", e.getMessage());
            return;
        }
        if (opt.isEmpty()) {
            log.debug("[UserSimulatorBootstrap] no user-simulator agent row — skipping swap");
            return;
        }
        AgentEntity agent = opt.get();
        String current = agent.getSystemPrompt();
        if (current == null || !current.startsWith(SEE_FILE_SENTINEL_PREFIX)) {
            log.debug("[UserSimulatorBootstrap] agentId={} prompt already non-placeholder ({} chars) — leaving alone",
                    agent.getId(), current == null ? 0 : current.length());
            return;
        }

        String resourcePath = current.substring(SEE_FILE_SENTINEL_PREFIX.length()).trim();
        if (resourcePath.isEmpty()) {
            resourcePath = PROMPT_RESOURCE_PATH;
        }
        String prompt = loadPromptFromClasspath(resourcePath);
        if (prompt == null) {
            log.warn("[UserSimulatorBootstrap] agentId={} cannot resolve prompt resource '{}' — leaving placeholder",
                    agent.getId(), resourcePath);
            return;
        }

        agent.setSystemPrompt(prompt);
        agentRepository.save(agent);
        log.info("[UserSimulatorBootstrap] agentId={} swapped placeholder for {} ({} chars)",
                agent.getId(), resourcePath, prompt.length());
    }

    /** Package-private for unit testing. Returns null when the resource is missing. */
    String loadPromptFromClasspath(String resourcePath) {
        ClassPathResource resource = new ClassPathResource(resourcePath);
        if (!resource.exists()) {
            return null;
        }
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[UserSimulatorBootstrap] failed to read resource '{}': {}",
                    resourcePath, e.getMessage());
            return null;
        }
    }
}
