package com.skillforge.server.improve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.LlmRequest;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.PromptVersionEntity;
import com.skillforge.server.memory.context.MemoryContextProvider;
import com.skillforge.server.memory.context.MemoryContextSnapshot;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    @Mock private MemoryContextProvider memoryContextProvider;

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
                props,
                org.mockito.Mockito.mock(com.skillforge.server.improve.surface.PromptSurface.class),
                org.mockito.Mockito.mock(PromptEvalService.class),
                // Phase 1.4 (2026-05-16) → Phase 1.4d (2026-05-17): ephemeral
                // fallback deps + W5 cleanup service. mock(...) per W3 fix
                // (Mockito-friendly NPE if any future test path dereferences).
                org.mockito.Mockito.mock(com.skillforge.server.repository.EvalScenarioDraftRepository.class),
                org.mockito.Mockito.mock(com.skillforge.server.repository.OptimizationEventRepository.class),
                org.mockito.Mockito.mock(com.skillforge.server.repository.PatternSessionMemberRepository.class),
                org.mockito.Mockito.mock(com.skillforge.server.repository.SessionRepository.class),
                org.mockito.Mockito.mock(com.skillforge.server.improve.SessionScenarioExtractorService.class),
                org.mockito.Mockito.mock(com.skillforge.server.improve.EphemeralScenarioCleanupService.class),
                null,
                memoryContextProvider);
        // PromptImproverServiceAttributionTest exercises startImprovementFromAttribution
        // (V3.1 sync LLM fill path) — does NOT invoke AbstractAbEvalRunner.run(),
        // so no PromptEvalService.run setup needed.
    }

    private AgentEntity agent(long id) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setOwnerId(7L);
        a.setSystemPrompt("Be concise.");
        a.setAutoImprovePaused(false);
        return a;
    }

    /** V3.1: stub LLM provider to return a fixed improved-prompt response. */
    private LlmProvider stubLlmReturning(String responseContent) {
        LlmProvider provider = org.mockito.Mockito.mock(LlmProvider.class);
        LlmResponse response = new LlmResponse();
        response.setContent(responseContent);
        when(provider.chat(any(LlmRequest.class))).thenReturn(response);
        when(llmProviderFactory.getProvider("test")).thenReturn(provider);
        return provider;
    }

    /** Build a fake PromptVersionEntity used to seed "existing versions" mocks. */
    private PromptVersionEntity versionRow(String agentId, int versionNumber, String status) {
        PromptVersionEntity v = new PromptVersionEntity();
        v.setId("v-" + agentId + "-" + versionNumber);
        v.setAgentId(agentId);
        v.setVersionNumber(versionNumber);
        v.setStatus(status);
        v.setContent("existing content " + versionNumber);
        return v;
    }

    @Test
    @DisplayName("happy path: persists candidate PromptVersionEntity with source='attribution', no PromptAbRun")
    void startImprovementFromAttribution_happyPath_persistsAttributionCandidate() {
        when(agentRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(agent(10L)));
        // Agent already has v1..v3 → genesis baseline path NOT entered, candidate is v4.
        when(promptVersionRepository.findByAgentIdOrderByVersionNumberDesc("10"))
                .thenReturn(List.of(
                        versionRow("10", 3, "deprecated"),
                        versionRow("10", 2, "deprecated"),
                        versionRow("10", 1, "active")));
        stubLlmReturning("Be concise. Always validate Bash exit codes before proceeding.");
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
        // V3.1: content now populated by synchronous LLM call (no longer empty
        // placeholder). The actual text matches what the stubbed provider returned.
        assertThat(saved.getContent())
                .isEqualTo("Be concise. Always validate Bash exit codes before proceeding.");

        // Critical: NO PromptAbRunEntity creation (attribution path doesn't have an eval baseline).
        verify(promptAbRunRepository, never()).save(any());
        // Genesis baseline path NOT entered: only the candidate was saved (one
        // promptVersionRepository.save invocation, no agent.save for activePromptVersionId).
        verify(promptVersionRepository, times(1)).save(any(PromptVersionEntity.class));
        verify(agentRepository, never()).save(any());
    }

    @Test
    @DisplayName("BYPASS check: succeeds even when agent.lastPromotedAt would block the legacy startImprovement path")
    void startImprovementFromAttribution_bypassesLegacyCooldown_succeeds() {
        // Setup: agent that just got promoted 1h ago — would fail legacy
        // checkEligibility's COOLDOWN_ACTIVE check (24h window).
        AgentEntity recentlyPromoted = agent(20L);
        recentlyPromoted.setLastPromotedAt(Instant.now().minusSeconds(3600));
        when(agentRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(recentlyPromoted));
        // PROMPT-IMPROVER-GENESIS-BASELINE: agent has v1..v2 → no genesis path
        // (the bypass-cooldown semantic this test guards is orthogonal to the
        // genesis-baseline materialization; covering both in one mock would
        // collide with the agent.save assertions in other tests).
        when(promptVersionRepository.findByAgentIdOrderByVersionNumberDesc("20"))
                .thenReturn(List.of(
                        versionRow("20", 2, "deprecated"),
                        versionRow("20", 1, "active")));
        stubLlmReturning("Improved prompt for bypass path");
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
    @DisplayName("memory context provider output is included in attribution prompt generation")
    void startImprovementFromAttribution_memoryContextAvailable_includesContextInPrompt() {
        when(agentRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(agent(10L)));
        when(promptVersionRepository.findByAgentIdOrderByVersionNumberDesc("10"))
                .thenReturn(List.of(versionRow("10", 1, "active")));
        when(memoryContextProvider.load(7L, "Add explicit Bash exit-code handling"))
                .thenReturn(new MemoryContextSnapshot(
                        7L,
                        "task",
                        "User prefers concise fail-fast prompts.",
                        Set.of(201L),
                        "hash-201"));
        LlmProvider provider = stubLlmReturning("Improved prompt");
        when(promptVersionRepository.save(any(PromptVersionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        ArgumentCaptor<LlmRequest> requestCaptor = ArgumentCaptor.forClass(LlmRequest.class);

        service.startImprovementFromAttribution(
                99L, "10", "Add explicit Bash exit-code handling", 7L);

        verify(provider).chat(requestCaptor.capture());
        String userPrompt = requestCaptor.getValue().getMessages().get(0).getTextContent();
        assertThat(userPrompt)
                .contains("Relevant long-term memory context:")
                .contains("User prefers concise fail-fast prompts.");
    }

    @Test
    @DisplayName("blank memory context is rendered as (none) in attribution prompt generation")
    void startImprovementFromAttribution_blankMemoryContext_includesNoneMarker() {
        when(agentRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(agent(10L)));
        when(promptVersionRepository.findByAgentIdOrderByVersionNumberDesc("10"))
                .thenReturn(List.of(versionRow("10", 1, "active")));
        when(memoryContextProvider.load(7L, "Tune prompt"))
                .thenReturn(new MemoryContextSnapshot(7L, "task", "  ", Set.of(), "hash-empty"));
        LlmProvider provider = stubLlmReturning("Improved prompt");
        when(promptVersionRepository.save(any(PromptVersionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        ArgumentCaptor<LlmRequest> requestCaptor = ArgumentCaptor.forClass(LlmRequest.class);

        service.startImprovementFromAttribution(99L, "10", "Tune prompt", 7L);

        verify(provider).chat(requestCaptor.capture());
        String userPrompt = requestCaptor.getValue().getMessages().get(0).getTextContent();
        assertThat(userPrompt)
                .contains("Relevant long-term memory context:")
                .contains("(none)");
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
        when(agentRepository.findByIdForUpdate(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.startImprovementFromAttribution(
                99L, "999", "desc", 7L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Agent not found: 999");
        verify(promptVersionRepository, never()).save(any());
    }

    // ───────── PROMPT-IMPROVER-GENESIS-BASELINE (V106, 2026-05-23) tests ─────────

    @Test
    @DisplayName("genesis: first attribution for agent materializes v1 baseline (active, genesis_baseline) + v2 candidate (attribution)")
    void startImprovementFromAttribution_firstTimeForAgent_materializesBaselineAndCandidate() {
        AgentEntity fresh = agent(30L);
        fresh.setSystemPrompt("You are a helpful assistant.");
        // activePromptVersionId starts null — proves baseline path correctly
        // patches it to point at the new v1.
        when(agentRepository.findByIdForUpdate(30L)).thenReturn(Optional.of(fresh));
        when(promptVersionRepository.findByAgentIdOrderByVersionNumberDesc("30"))
                .thenReturn(List.of());  // empty → genesis path
        stubLlmReturning("You are a helpful assistant. Validate Bash exit codes.");
        ArgumentCaptor<PromptVersionEntity> versionCaptor =
                ArgumentCaptor.forClass(PromptVersionEntity.class);
        when(promptVersionRepository.save(versionCaptor.capture()))
                .thenAnswer(inv -> inv.getArgument(0));
        ArgumentCaptor<AgentEntity> agentCaptor = ArgumentCaptor.forClass(AgentEntity.class);
        when(agentRepository.save(agentCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        ImprovementStartResult result = service.startImprovementFromAttribution(
                100L, "30", "Add Bash error handling guidance", 7L);

        assertThat(result.promptVersionId()).isNotBlank();

        // Two prompt_version saves: [0]=baseline, [1]=candidate.
        List<PromptVersionEntity> savedVersions = versionCaptor.getAllValues();
        assertThat(savedVersions).hasSize(2);

        PromptVersionEntity baseline = savedVersions.get(0);
        assertThat(baseline.getVersionNumber()).isEqualTo(1);
        assertThat(baseline.getStatus()).isEqualTo("active");
        assertThat(baseline.getSource()).isEqualTo("genesis_baseline");
        assertThat(baseline.getContent()).isEqualTo("You are a helpful assistant.");
        assertThat(baseline.getAgentId()).isEqualTo("30");

        PromptVersionEntity candidate = savedVersions.get(1);
        assertThat(candidate.getVersionNumber()).isEqualTo(2);
        assertThat(candidate.getStatus()).isEqualTo("candidate");
        assertThat(candidate.getSource()).isEqualTo("attribution");
        assertThat(candidate.getContent())
                .isEqualTo("You are a helpful assistant. Validate Bash exit codes.");
        assertThat(candidate.getImprovementRationale())
                .isEqualTo("Add Bash error handling guidance");
        // Result UUID points to the CANDIDATE (per existing contract — caller
        // writes this to OptimizationEvent.candidatePromptVersionUuid).
        assertThat(result.promptVersionId()).isEqualTo(candidate.getId());

        // Agent updated with activePromptVersionId pointing at the new baseline,
        // so runAbTestAgainst's null-check won't re-fight the genesis path.
        verify(agentRepository, atLeastOnce()).save(any(AgentEntity.class));
        AgentEntity savedAgent = agentCaptor.getValue();
        assertThat(savedAgent.getActivePromptVersionId()).isEqualTo(baseline.getId());
    }

    @Test
    @DisplayName("genesis: baseline already exists → skip baseline creation, candidate is next (v2+)")
    void startImprovementFromAttribution_baselineAlreadyExists_skipsBaselineCreation() {
        AgentEntity established = agent(40L);
        established.setSystemPrompt("Established prompt content.");
        // active_prompt_version_id already set (typical after V106 backfill ran).
        established.setActivePromptVersionId("v-40-1");
        when(agentRepository.findByIdForUpdate(40L)).thenReturn(Optional.of(established));
        when(promptVersionRepository.findByAgentIdOrderByVersionNumberDesc("40"))
                .thenReturn(List.of(versionRow("40", 1, "active")));
        stubLlmReturning("Updated prompt with new behavior.");
        ArgumentCaptor<PromptVersionEntity> versionCaptor =
                ArgumentCaptor.forClass(PromptVersionEntity.class);
        when(promptVersionRepository.save(versionCaptor.capture()))
                .thenAnswer(inv -> inv.getArgument(0));

        ImprovementStartResult result = service.startImprovementFromAttribution(
                101L, "40", "Tweak behavior", 7L);

        assertThat(result.promptVersionId()).isNotBlank();
        // Only the candidate is saved (no baseline materialization), at v2.
        List<PromptVersionEntity> savedVersions = versionCaptor.getAllValues();
        assertThat(savedVersions).hasSize(1);
        PromptVersionEntity candidate = savedVersions.get(0);
        assertThat(candidate.getVersionNumber()).isEqualTo(2);
        assertThat(candidate.getStatus()).isEqualTo("candidate");
        assertThat(candidate.getSource()).isEqualTo("attribution");

        // Agent not re-saved (active_prompt_version_id untouched by skip path).
        verify(agentRepository, never()).save(any());
    }

    @Test
    @DisplayName("genesis: blank system_prompt → IllegalStateException (no rows written)")
    void startImprovementFromAttribution_emptySystemPrompt_throws() {
        AgentEntity emptyPromptAgent = agent(50L);
        emptyPromptAgent.setSystemPrompt("   ");  // blank only
        when(agentRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(emptyPromptAgent));
        when(promptVersionRepository.findByAgentIdOrderByVersionNumberDesc("50"))
                .thenReturn(List.of());  // empty → genesis path triggers

        assertThatThrownBy(() -> service.startImprovementFromAttribution(
                102L, "50", "Make it better", 7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("system_prompt")
                .hasMessageContaining("50");

        // No saves at all — we bailed before writing baseline or candidate.
        verify(promptVersionRepository, never()).save(any());
        verify(agentRepository, never()).save(any());
    }
}
