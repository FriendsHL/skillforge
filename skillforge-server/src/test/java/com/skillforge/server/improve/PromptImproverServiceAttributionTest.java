package com.skillforge.server.improve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.PromptVersionEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.EvalTaskRepository;
import com.skillforge.server.repository.PromptAbRunRepository;
import com.skillforge.server.repository.PromptVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V3 ATTRIBUTION-AGENT Phase 1.3 — unit tests for the new
 * {@link PromptImproverService#startImprovementFromAttribution} entry point.
 *
 * <p>Critical ratify (2026-05-15): the attribution path BYPASSES
 * {@link PromptImproverService}.checkEligibility entirely — the
 * {@code agent.lastPromotedAt} 24h cooldown is replaced by V3's
 * {@code t_optimization_event.cooldown_expires_at} pattern-level cooldown.
 * The third test case explicitly verifies the bypass: an agent that would be
 * blocked by the legacy {@code startImprovement(...)} path (lastPromotedAt =
 * NOW() − 1h) succeeds via the attribution path.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PromptImproverService.startImprovementFromAttribution (V3 Phase 1.3)")
class PromptImproverServiceAttributionTest {

    @Mock private AgentRepository agentRepository;
    @Mock private EvalTaskRepository evalTaskRepository;
    @Mock private PromptVersionRepository promptVersionRepository;
    @Mock private PromptAbRunRepository promptAbRunRepository;
    @Mock private AbEvalPipeline abEvalPipeline;
    @Mock private PromptPromotionService promotionService;
    @Mock private LlmProviderFactory llmProviderFactory;
    @Mock private ExecutorService coordinatorExecutor;

    private PromptImproverService service;

    @BeforeEach
    void setUp() {
        LlmProperties props = new LlmProperties();
        props.setDefaultProvider("test");
        service = new PromptImproverService(
                agentRepository,
                evalTaskRepository,
                promptVersionRepository,
                promptAbRunRepository,
                abEvalPipeline,
                promotionService,
                llmProviderFactory,
                new ObjectMapper(),
                coordinatorExecutor,
                props);
    }

    private AgentEntity agent(long id) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setSystemPrompt("Be concise.");
        a.setAutoImprovePaused(false);
        return a;
    }

    @Test
    @DisplayName("happy path: persists candidate PromptVersionEntity with source='attribution', no PromptAbRun")
    void startImprovementFromAttribution_happyPath_persistsAttributionCandidate() {
        when(agentRepository.findById(10L)).thenReturn(Optional.of(agent(10L)));
        when(promptVersionRepository.findMaxVersionNumber("10")).thenReturn(Optional.of(3));
        ArgumentCaptor<PromptVersionEntity> captor = ArgumentCaptor.forClass(PromptVersionEntity.class);
        when(promptVersionRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        ImprovementStartResult result = service.startImprovementFromAttribution(
                /*eventId*/ 99L,
                /*agentId*/ "10",
                /*attributedDescription*/ "Tune system prompt to add explicit Bash error handling",
                /*ownerId*/ 7L);

        assertThat(result).isNotNull();
        assertThat(result.agentId()).isEqualTo("10");
        assertThat(result.abRunId()).isNull();  // No A/B run created in 1.3
        assertThat(result.promptVersionId()).isNotBlank();
        assertThat(result.status()).isEqualTo("PENDING");

        PromptVersionEntity saved = captor.getValue();
        assertThat(saved.getAgentId()).isEqualTo("10");
        assertThat(saved.getStatus()).isEqualTo("candidate");
        assertThat(saved.getSource()).isEqualTo("attribution");  // distinguishes from "auto_improve"
        assertThat(saved.getVersionNumber()).isEqualTo(4);
        assertThat(saved.getImprovementRationale()).isEqualTo(
                "Tune system prompt to add explicit Bash error handling");
        assertThat(saved.getSourceEvalRunId()).isNull();
        assertThat(saved.getBaselinePassRate()).isNull();
        // Phase 1.3 placeholder content — Phase 1.4+ async LLM job fills it.
        // Empty-string (NOT null) because the column is NOT NULL.
        assertThat(saved.getContent()).isEmpty();

        // Critical: NO PromptAbRunEntity creation (attribution path doesn't have an eval baseline).
        verify(promptAbRunRepository, never()).save(any());
    }

    @Test
    @DisplayName("BYPASS check: succeeds even when agent.lastPromotedAt would block the legacy startImprovement path")
    void startImprovementFromAttribution_bypassesLegacyCooldown_succeeds() {
        // Setup: agent that just got promoted 1h ago — would fail legacy
        // checkEligibility's COOLDOWN_ACTIVE check (24h window).
        AgentEntity recentlyPromoted = agent(20L);
        recentlyPromoted.setLastPromotedAt(Instant.now().minusSeconds(3600));
        when(agentRepository.findById(20L)).thenReturn(Optional.of(recentlyPromoted));
        when(promptVersionRepository.findMaxVersionNumber("20")).thenReturn(Optional.of(0));
        when(promptVersionRepository.save(any(PromptVersionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // Attribution path BYPASSES checkEligibility (per ratify) — must succeed.
        ImprovementStartResult result = service.startImprovementFromAttribution(
                88L, "20", "Bypass cooldown test", 7L);

        assertThat(result.promptVersionId()).isNotBlank();
        assertThat(result.status()).isEqualTo("PENDING");
        // Confirm we never even consulted EvalTask — the legacy path needs it
        // for primary attribution; attribution path skips it entirely.
        verify(evalTaskRepository, never()).findById(any(String.class));
    }

    @Test
    @DisplayName("missing args: null eventId / null agentId / blank description → IllegalArgumentException")
    void startImprovementFromAttribution_invalidArgs_throws() {
        assertThatThrownBy(() -> service.startImprovementFromAttribution(
                null, "10", "desc", 7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventId");
        assertThatThrownBy(() -> service.startImprovementFromAttribution(
                99L, null, "desc", 7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agentId");
        assertThatThrownBy(() -> service.startImprovementFromAttribution(
                99L, "10", "  ", 7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attributedDescription");
        verify(promptVersionRepository, never()).save(any());
    }

    @Test
    @DisplayName("agent not found: throws clean RuntimeException, no version saved")
    void startImprovementFromAttribution_agentMissing_throws() {
        when(agentRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.startImprovementFromAttribution(
                99L, "999", "desc", 7L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Agent not found: 999");
        verify(promptVersionRepository, never()).save(any());
    }
}
