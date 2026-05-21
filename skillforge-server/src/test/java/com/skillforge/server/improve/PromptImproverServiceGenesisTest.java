package com.skillforge.server.improve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.EvalScenarioEntity;
import com.skillforge.server.entity.PromptAbRunEntity;
import com.skillforge.server.entity.PromptVersionEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.EvalScenarioDraftRepository;
import com.skillforge.server.repository.EvalTaskRepository;
import com.skillforge.server.repository.OptimizationEventRepository;
import com.skillforge.server.repository.PatternSessionMemberRepository;
import com.skillforge.server.repository.PromptAbRunRepository;
import com.skillforge.server.repository.PromptVersionRepository;
import com.skillforge.server.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PROMPT-IMPROVER-GENESIS-BASELINE (2026-05-21) — unit tests for the
 * genesis path in {@link PromptImproverService#runAbTestAgainst}.
 *
 * <p>Context: pre-genesis, all 11 production agents had
 * {@code t_agent.active_prompt_version_id == NULL}, so every flywheel-
 * attributed A/B trigger hit the {@code IllegalStateException} at the
 * baseline-resolution branch — the entire prompt-surface arm of the
 * self-improve flywheel was broken on first contact. The genesis path
 * materializes a v1 active baseline from {@code t_agent.system_prompt} so
 * subsequent A/B runs can compare baseline (v1) vs candidate (v2).
 *
 * <p>Test scope: focused on the genesis branch decisions only — the rest
 * of {@code runAbTestAgainst} (scenario resolution, abRun creation, async
 * dispatch) is mocked out so each test exercises a single decision.
 */
@ExtendWith(MockitoExtension.class)
// Some shared stubs in setUp() (versionRepository.findById / save,
// abRunRepository.save / findByAgentIdAndStatus) only fire on the happy /
// non-throw paths — strict mode would flag them as unnecessary on the
// throws tests (genesisThrows_*). Loose strictness keeps the shared
// setUp readable while still verifying intent via verify(...) calls.
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PromptImproverService.runAbTestAgainst — genesis baseline")
class PromptImproverServiceGenesisTest {

    @Mock private AgentRepository agentRepository;
    @Mock private EvalTaskRepository evalTaskRepository;
    @Mock private PromptVersionRepository promptVersionRepository;
    @Mock private PromptAbRunRepository promptAbRunRepository;
    @Mock private AbEvalPipeline abEvalPipeline;
    @Mock private PromptPromotionService promotionService;
    @Mock private LlmProviderFactory llmProviderFactory;
    @Mock private ExecutorService coordinatorExecutor;
    @Mock private EvalScenarioDraftRepository evalScenarioRepository;
    @Mock private OptimizationEventRepository optimizationEventRepository;
    @Mock private PatternSessionMemberRepository patternSessionMemberRepository;
    @Mock private SessionRepository sessionRepository;
    @Mock private SessionScenarioExtractorService sessionScenarioExtractor;
    @Mock private EphemeralScenarioCleanupService ephemeralScenarioCleanupService;

    private PromptImproverService service;

    // In-memory stores so the candidate / baseline lookups inside the method
    // resolve correctly (otherwise the candidate findById would return empty
    // and the genesis branch would never be reached).
    private final Map<String, PromptVersionEntity> versionStore = new HashMap<>();

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
                evalScenarioRepository,
                optimizationEventRepository,
                patternSessionMemberRepository,
                sessionRepository,
                sessionScenarioExtractor,
                ephemeralScenarioCleanupService);

        // Default repository behavior: findById delegates to in-memory store
        // so candidate/baseline lookups succeed when versionStore has the row.
        when(promptVersionRepository.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(versionStore.get(inv.getArgument(0))));
        when(promptVersionRepository.save(any(PromptVersionEntity.class)))
                .thenAnswer(inv -> {
                    PromptVersionEntity v = inv.getArgument(0);
                    versionStore.put(v.getId(), v);
                    return v;
                });
        when(promptAbRunRepository.save(any(PromptAbRunEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(promptAbRunRepository.findByAgentIdAndStatus(anyString(), anyString()))
                .thenReturn(Collections.emptyList());
    }

    /** Helper: fresh agent with id + system prompt. */
    private AgentEntity agent(long id, String systemPrompt) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setSystemPrompt(systemPrompt);
        a.setAutoImprovePaused(false);
        return a;
    }

    /** Helper: pre-seed a candidate v2 row in the store. */
    private PromptVersionEntity seedCandidate(String id, String agentId, int versionNumber) {
        PromptVersionEntity v = new PromptVersionEntity();
        v.setId(id);
        v.setAgentId(agentId);
        v.setContent("candidate content");
        v.setVersionNumber(versionNumber);
        v.setStatus("candidate");
        v.setSource("attribution");
        versionStore.put(id, v);
        return v;
    }

    /** Helper: a single held-out scenario so the empty-scenarios guard passes. */
    private List<EvalScenarioEntity> heldOutScenarios(String agentId) {
        EvalScenarioEntity s = new EvalScenarioEntity();
        s.setId("scenario-1");
        s.setAgentId(agentId);
        return List.of(s);
    }

    @Test
    @DisplayName("genesis created when agent has no active prompt version")
    void genesisCreated_whenAgentHasNoActiveVersion() {
        AgentEntity a = agent(10L, "Be concise and helpful.");
        a.setActivePromptVersionId(null);  // explicit — first-time agent
        when(agentRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(a));
        seedCandidate("cand-uuid-1", "10", 2);
        when(evalScenarioRepository.findByAgentIdAndSplit("10", "held_out"))
                .thenReturn(heldOutScenarios("10"));

        ArgumentCaptor<PromptVersionEntity> versionCaptor =
                ArgumentCaptor.forClass(PromptVersionEntity.class);
        ArgumentCaptor<AgentEntity> agentCaptor = ArgumentCaptor.forClass(AgentEntity.class);

        String abRunId = service.runAbTestAgainst(
                /*agentId*/ "10",
                /*baselineVersionId*/ null,  // triggers genesis path
                /*candidateVersionId*/ "cand-uuid-1",
                /*evalScenarioIds*/ null);

        assertThat(abRunId).isNotBlank();

        // 1 genesis save + 1 candidate findById (no candidate save in this path)
        // — capture all save invocations and find the v1 row.
        verify(promptVersionRepository, times(1)).save(versionCaptor.capture());
        PromptVersionEntity genesis = versionCaptor.getValue();
        assertThat(genesis.getAgentId()).isEqualTo("10");
        assertThat(genesis.getVersionNumber()).isEqualTo(1);
        assertThat(genesis.getStatus()).isEqualTo("active");
        assertThat(genesis.getSource()).isEqualTo("genesis");
        assertThat(genesis.getContent()).isEqualTo("Be concise and helpful.");
        assertThat(genesis.getId()).isNotBlank();

        // Agent activePromptVersionId pointer was updated to genesis.id.
        verify(agentRepository).save(agentCaptor.capture());
        assertThat(agentCaptor.getValue().getActivePromptVersionId())
                .isEqualTo(genesis.getId());

        // abRun was created with the genesis baseline resolved correctly —
        // candidate references the c2 row; baseline reference is implicit in
        // the resolved baselineId captured by the async lambda.
        verify(promptAbRunRepository).save(any(PromptAbRunEntity.class));
    }

    @Test
    @DisplayName("genesis skipped when agent already has active prompt version")
    void genesisSkipped_whenAgentAlreadyHasActiveVersion() {
        AgentEntity a = agent(11L, "(legacy prompt — should NOT be materialized)");
        a.setActivePromptVersionId("existing-v1-uuid");
        when(agentRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(a));

        // Seed the existing v1 baseline + new v2 candidate so both findById
        // calls succeed and the method runs through to abRun creation.
        PromptVersionEntity existingV1 = new PromptVersionEntity();
        existingV1.setId("existing-v1-uuid");
        existingV1.setAgentId("11");
        existingV1.setContent("Existing baseline content");
        existingV1.setVersionNumber(1);
        existingV1.setStatus("active");
        existingV1.setSource("auto_improve");  // not 'genesis'
        versionStore.put(existingV1.getId(), existingV1);
        seedCandidate("cand-uuid-2", "11", 2);
        when(evalScenarioRepository.findByAgentIdAndSplit("11", "held_out"))
                .thenReturn(heldOutScenarios("11"));

        service.runAbTestAgainst("11", null, "cand-uuid-2", null);

        // No PromptVersionEntity save — neither genesis nor candidate. The
        // baseline already exists, the candidate row is pre-seeded by the
        // caller (AttributionApprovalService) before runAbTestAgainst is
        // invoked, and runAbTestAgainst itself never saves a version row on
        // the non-genesis path.
        verify(promptVersionRepository, never()).save(any(PromptVersionEntity.class));
        // No agent save either — only the genesis path mutates the agent
        // (setting activePromptVersionId).
        verify(agentRepository, never()).save(any(AgentEntity.class));
    }

    @Test
    @DisplayName("genesis skipped when explicit baselineVersionId provided (even if agent has no active version)")
    void genesisSkipped_whenExplicitBaselineProvided_agentHasNoActive() {
        // W1 r1 java-reviewer follow-up: explicit baselineVersionId 优先于 agent.activePromptVersionId 检查。
        // 即使 agent 没 active version (virgin agent)，caller 显式传 baselineVersionId 时不该触发 genesis 物化。
        AgentEntity a = agent(20L, "Be concise.");
        a.setActivePromptVersionId(null);  // virgin agent
        when(agentRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(a));

        // Caller 显式准备 baseline + candidate
        PromptVersionEntity explicitBaseline = new PromptVersionEntity();
        explicitBaseline.setId("explicit-baseline-uuid");
        explicitBaseline.setAgentId("20");
        explicitBaseline.setContent("Caller-supplied baseline content");
        explicitBaseline.setVersionNumber(1);
        explicitBaseline.setStatus("active");
        explicitBaseline.setSource("auto_improve");
        versionStore.put(explicitBaseline.getId(), explicitBaseline);
        seedCandidate("cand-uuid-99", "20", 2);
        when(evalScenarioRepository.findByAgentIdAndSplit("20", "held_out"))
                .thenReturn(heldOutScenarios("20"));

        service.runAbTestAgainst(
                "20",
                "explicit-baseline-uuid",  // ← 显式 baseline，跳过 genesis
                "cand-uuid-99",
                null);

        // 不应触发 genesis: 0 promptVersionRepository.save + 0 agentRepository.save
        verify(promptVersionRepository, never()).save(any(PromptVersionEntity.class));
        verify(agentRepository, never()).save(any(AgentEntity.class));
    }

    @Test
    @DisplayName("genesis throws clean IllegalStateException when agent has empty system prompt")
    void genesisThrows_whenAgentHasEmptySystemPrompt() {
        AgentEntity a = agent(12L, null);  // null system prompt
        a.setActivePromptVersionId(null);
        when(agentRepository.findByIdForUpdate(12L)).thenReturn(Optional.of(a));
        seedCandidate("cand-uuid-3", "12", 2);

        assertThatThrownBy(() -> service.runAbTestAgainst(
                "12", null, "cand-uuid-3", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot bootstrap baseline")
                .hasMessageContaining("12");

        // Genesis save MUST NOT happen; agent pointer MUST NOT be updated.
        verify(promptVersionRepository, never()).save(any(PromptVersionEntity.class));
        verify(agentRepository, never()).save(any(AgentEntity.class));
    }

    @Test
    @DisplayName("genesis throws when agent has whitespace-only (blank) system prompt")
    void genesisThrows_whenAgentHasBlankSystemPrompt() {
        AgentEntity a = agent(13L, "   \n\t  ");  // whitespace-only — isBlank() true
        a.setActivePromptVersionId(null);
        when(agentRepository.findByIdForUpdate(13L)).thenReturn(Optional.of(a));
        seedCandidate("cand-uuid-4", "13", 2);

        assertThatThrownBy(() -> service.runAbTestAgainst(
                "13", null, "cand-uuid-4", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot bootstrap baseline");

        verify(promptVersionRepository, never()).save(any(PromptVersionEntity.class));
        verify(agentRepository, never()).save(any(AgentEntity.class));
    }

    @Test
    @DisplayName("genesis row carries source='genesis' (distinct from attribution / auto_improve)")
    void genesisSourceValueIsGenesis() {
        AgentEntity a = agent(14L, "Original system prompt for agent 14.");
        a.setActivePromptVersionId(null);
        when(agentRepository.findByIdForUpdate(14L)).thenReturn(Optional.of(a));
        seedCandidate("cand-uuid-5", "14", 2);
        when(evalScenarioRepository.findByAgentIdAndSplit("14", "held_out"))
                .thenReturn(heldOutScenarios("14"));

        service.runAbTestAgainst("14", null, "cand-uuid-5", null);

        // Inspect the saved genesis row directly from the in-memory store.
        // Filter on source='genesis' to be explicit about what we're asserting.
        PromptVersionEntity genesis = versionStore.values().stream()
                .filter(v -> "genesis".equals(v.getSource()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected a v1 row with source='genesis'"));

        assertThat(genesis.getSource()).isEqualTo("genesis");
        // Not 'attribution' / 'auto_improve' / 'manual' — confirms the new
        // source value is being written and isn't accidentally inheriting
        // the entity default ('auto_improve' per PromptVersionEntity).
        assertThat(genesis.getSource()).isNotEqualTo("auto_improve");
        assertThat(genesis.getSource()).isNotEqualTo("attribution");
    }

    @Test
    @DisplayName("genesis atomic: agent.activePromptVersionId points to the newly-created v1 row id")
    void genesisAtomic_baselineAndAgentBothPersisted() {
        AgentEntity a = agent(15L, "Some baseline prompt.");
        a.setActivePromptVersionId(null);
        when(agentRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(a));
        seedCandidate("cand-uuid-6", "15", 2);
        when(evalScenarioRepository.findByAgentIdAndSplit("15", "held_out"))
                .thenReturn(heldOutScenarios("15"));

        service.runAbTestAgainst("15", null, "cand-uuid-6", null);

        // The agent passed to save() must have its activePromptVersionId
        // pointing to a real v1 row id (not null, not stale, and the row
        // must actually exist in the version store post-save).
        ArgumentCaptor<AgentEntity> agentCaptor = ArgumentCaptor.forClass(AgentEntity.class);
        verify(agentRepository).save(agentCaptor.capture());
        AgentEntity savedAgent = agentCaptor.getValue();
        assertThat(savedAgent.getActivePromptVersionId()).isNotNull();
        assertThat(savedAgent.getActivePromptVersionId()).isNotBlank();

        // The id must correspond to a real PromptVersionEntity row that was
        // persisted in this same method call (proves the two writes are
        // tied together, not pointing to some leftover).
        PromptVersionEntity referenced =
                versionStore.get(savedAgent.getActivePromptVersionId());
        assertThat(referenced).isNotNull();
        assertThat(referenced.getVersionNumber()).isEqualTo(1);
        assertThat(referenced.getStatus()).isEqualTo("active");
        assertThat(referenced.getSource()).isEqualTo("genesis");
        assertThat(referenced.getAgentId()).isEqualTo("15");
    }
}
