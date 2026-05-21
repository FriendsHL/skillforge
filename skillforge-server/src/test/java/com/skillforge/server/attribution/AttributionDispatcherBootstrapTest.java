package com.skillforge.server.attribution;

import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.repository.AgentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AttributionDispatcherBootstrap} system-prompt swap.
 *
 * <p>Structural twin of {@code MemoryCuratorBootstrapTest} — same 6 idempotency
 * cases plus W3 explicit coverage for "classpath resource resolves to null →
 * leave SEE_FILE placeholder + log.warn (NOT NPE / NOT overwrite with null)".
 */
@ExtendWith(MockitoExtension.class)
class AttributionDispatcherBootstrapTest {

    @Mock
    private AgentRepository agentRepository;

    private AttributionDispatcherBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        bootstrap = new AttributionDispatcherBootstrap(agentRepository);
    }

    @Test
    @DisplayName("swap is a no-op when no attribution-dispatcher agent row exists (V93 not yet applied)")
    void swap_noAgent_isNoop() {
        when(agentRepository.findFirstByName(AttributionDispatcherBootstrap.AGENT_NAME))
                .thenReturn(Optional.empty());

        bootstrap.swapSystemPromptOnBoot();

        verify(agentRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("swap replaces SEE_FILE placeholder with classpath resource content")
    void swap_seeFilePlaceholder_replacesWithResourceContent() {
        AgentEntity agent = newAgent("SEE_FILE:attribution-dispatcher-system-prompt.md");
        agent.setAgentType("system");  // isolate prompt-swap path from agent_type self-heal
        when(agentRepository.findFirstByName(AttributionDispatcherBootstrap.AGENT_NAME))
                .thenReturn(Optional.of(agent));

        bootstrap.swapSystemPromptOnBoot();

        ArgumentCaptor<AgentEntity> saved = ArgumentCaptor.forClass(AgentEntity.class);
        verify(agentRepository).save(saved.capture());
        String swapped = saved.getValue().getSystemPrompt();
        assertThat(swapped).doesNotStartWith("SEE_FILE:");
        // Sanity: known content fragments from attribution-dispatcher-system-prompt.md
        assertThat(swapped).contains("attribution-dispatcher");
        assertThat(swapped).contains("DispatchAttributionPatterns");
    }

    @Test
    @DisplayName("swap leaves operator-edited prompts alone (no SEE_FILE prefix)")
    void swap_alreadyOverridden_doesNotOverwrite() {
        AgentEntity agent = newAgent("operator edited prompt — do not overwrite");
        agent.setAgentType("system");  // prevent self-heal save
        when(agentRepository.findFirstByName(AttributionDispatcherBootstrap.AGENT_NAME))
                .thenReturn(Optional.of(agent));

        bootstrap.swapSystemPromptOnBoot();

        verify(agentRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("W3: SEE_FILE placeholder pointing at missing resource → leaves placeholder, never saves null")
    void swap_missingResource_doesNotOverwriteWithNull() {
        AgentEntity agent = newAgent("SEE_FILE:definitely-not-on-classpath-xyz.md");
        agent.setAgentType("system");  // prevent self-heal save
        when(agentRepository.findFirstByName(AttributionDispatcherBootstrap.AGENT_NAME))
                .thenReturn(Optional.of(agent));

        bootstrap.swapSystemPromptOnBoot();

        // W3 invariant: never overwrite with null when resource is missing.
        // Operator + log.warn need the placeholder visible to diagnose the
        // missing resource without the agent crashing at first chat.
        verify(agentRepository, never()).save(org.mockito.ArgumentMatchers.any());
        assertThat(agent.getSystemPrompt()).startsWith("SEE_FILE:");
    }

    @Test
    @DisplayName("agent_type self-heal: user → system on boot (defense-in-depth for pre-V89 rows)")
    void swap_agentTypeUserUnseeded_selfHealsToSystem() {
        // Simulates a hand-rolled row created before V93 (or future migration drift).
        // newAgent default agentType is 'user' (the AgentEntity field default).
        AgentEntity agent = newAgent("operator hand-edited — no SEE_FILE");
        assertThat(agent.getAgentType()).isEqualTo("user");
        when(agentRepository.findFirstByName(AttributionDispatcherBootstrap.AGENT_NAME))
                .thenReturn(Optional.of(agent));

        bootstrap.swapSystemPromptOnBoot();

        // Self-heal flipped agentType + saved. Prompt untouched (operator-edited path).
        ArgumentCaptor<AgentEntity> saved = ArgumentCaptor.forClass(AgentEntity.class);
        verify(agentRepository).save(saved.capture());
        assertThat(saved.getValue().getAgentType()).isEqualTo("system");
        assertThat(saved.getValue().getSystemPrompt()).isEqualTo("operator hand-edited — no SEE_FILE");
    }

    @Test
    @DisplayName("swap is safe when repository throws (test profile bypassing migration, etc.)")
    void swap_repositoryThrows_isSwallowed() {
        when(agentRepository.findFirstByName(AttributionDispatcherBootstrap.AGENT_NAME))
                .thenThrow(new RuntimeException("table not yet created"));

        bootstrap.swapSystemPromptOnBoot();   // must not throw

        verify(agentRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("loadPromptFromClasspath returns null for missing resources (W3 helper coverage)")
    void loadPromptFromClasspath_missingResource_returnsNull() {
        assertThat(bootstrap.loadPromptFromClasspath("definitely-not-on-classpath-xyz.md")).isNull();
    }

    @Test
    @DisplayName("loadPromptFromClasspath returns content for the seeded attribution-dispatcher-system-prompt.md")
    void loadPromptFromClasspath_existingResource_returnsContent() {
        String content = bootstrap.loadPromptFromClasspath(
                AttributionDispatcherBootstrap.PROMPT_RESOURCE_PATH);
        assertThat(content).isNotNull();
        assertThat(content).contains("attribution-dispatcher");
        assertThat(content).contains("DispatchAttributionPatterns");
    }

    private static AgentEntity newAgent(String prompt) {
        AgentEntity a = new AgentEntity();
        a.setId(202L);
        a.setName(AttributionDispatcherBootstrap.AGENT_NAME);
        a.setSystemPrompt(prompt);
        return a;
    }
}
