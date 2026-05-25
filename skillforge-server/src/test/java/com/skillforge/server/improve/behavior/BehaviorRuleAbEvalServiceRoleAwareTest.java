package com.skillforge.server.improve.behavior;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.BehaviorRuleAbRunEntity;
import com.skillforge.server.entity.BehaviorRuleVersionEntity;
import com.skillforge.server.entity.EvalScenarioEntity;
import com.skillforge.server.improve.AbEvalPipeline;
import com.skillforge.server.improve.AbScenarioResult;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.BehaviorRuleAbRunRepository;
import com.skillforge.server.repository.BehaviorRuleVersionRepository;
import com.skillforge.server.repository.EvalScenarioDraftRepository;
import com.skillforge.server.service.AgentService;
import com.skillforge.server.service.EvalDatasetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FLYWHEEL-AB-AGENT-AWARE-DATASET V1 — role-aware subset split tests for
 * {@link BehaviorRuleAbEvalService#runAsync(String)}. Covers the 3 paths
 * (prd.md §3.3 + UC-4):
 * <ol>
 *   <li><b>design owner</b> — target + regression dual-bucket via
 *       {@code findByDatasetVersionAndAgentRoles}, regression = general
 *       minus target ids (in-Java filter).</li>
 *   <li><b>general owner</b> — fallback regression-only mode (full dataset
 *       via {@code findAllByDatasetVersionId}).</li>
 *   <li><b>no scenarios match role</b> — fallback regression-only mode
 *       (target empty, full dataset becomes regression).</li>
 * </ol>
 *
 * <p>INV-3 (prd.md): target subset must NEVER include wrong-agent scenarios.
 * The design-owner test asserts the target subset received by the pipeline
 * carries only the scenarios returned by {@code findByDatasetVersionAndAgentRoles
 * ("dv-1", ["design"])} — no wrong-role bleed.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BehaviorRuleAbEvalService — role-aware subset split (FLYWHEEL V1)")
class BehaviorRuleAbEvalServiceRoleAwareTest {

    @Mock private BehaviorRuleVersionRepository versionRepository;
    @Mock private BehaviorRuleAbRunRepository abRunRepository;
    @Mock private EvalScenarioDraftRepository scenarioRepository;
    @Mock private EvalDatasetService evalDatasetService;
    @Mock private AgentRepository agentRepository;
    @Mock private AgentService agentService;
    @Mock private AbEvalPipeline abEvalPipeline;
    @Mock private ChatEventBroadcaster broadcaster;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final ExecutorService directExecutor = Executors.newSingleThreadExecutor();
    private final AgentRoleResolver roleResolver = new AgentRoleResolver();

    private BehaviorRuleAbEvalService service;

    @BeforeEach
    void setUp() {
        BehaviorRuleVersionToCustomRulesMapper rulesMapper =
                new BehaviorRuleVersionToCustomRulesMapper(objectMapper);
        service = new BehaviorRuleAbEvalService(
                versionRepository, abRunRepository, scenarioRepository,
                evalDatasetService, agentRepository, agentService,
                rulesMapper, abEvalPipeline, broadcaster, objectMapper,
                roleResolver, directExecutor);
    }

    @DisplayName("design owner → target = design subset, regression = general minus target ids")
    @Test
    void design_owner_dual_bucket_split() {
        BehaviorRuleVersionEntity v = newCandidateVersion("v1");
        BehaviorRuleAbRunEntity abRun = newAbRun("ab-design", "v1");
        when(abRunRepository.findById("ab-design")).thenReturn(Optional.of(abRun));
        when(versionRepository.findById("v1")).thenReturn(Optional.of(v));
        AgentEntity agent = new AgentEntity();
        agent.setId(100L);
        agent.setName("Design Agent");
        when(agentRepository.findById(100L)).thenReturn(Optional.of(agent));
        when(agentService.toAgentDefinition(agent)).thenReturn(new AgentDefinition());

        // Design subset: 2 scenarios; general subset: 3 scenarios (1 overlap
        // with design — must be filtered out from regression).
        EvalScenarioEntity tgtA = newScenario("tgt-A");
        EvalScenarioEntity tgtB = newScenario("tgt-B");
        EvalScenarioEntity genX = newScenario("gen-X");
        EvalScenarioEntity genY = newScenario("gen-Y");
        // Cross-tagged scenario tgt-B appears in both design AND general; in-
        // Java filter must drop it from regression to avoid double-counting.
        when(scenarioRepository.findByDatasetVersionAndAgentRoles(eq("dv-1"), rolesEq("design")))
                .thenReturn(List.of(tgtA, tgtB));
        when(scenarioRepository.findByDatasetVersionAndAgentRoles(eq("dv-1"), rolesEq("general")))
                .thenReturn(List.of(genX, genY, tgtB));  // tgtB also general-tagged

        when(abEvalPipeline.runWithExplicitDefs(anyString(), anyList(), any(), any()))
                .thenReturn(List.of(
                        result("tgt-A", 0.2, 0.9),
                        result("tgt-B", 0.3, 0.8),
                        result("gen-X", 0.7, 0.7),
                        result("gen-Y", 0.6, 0.6)));

        service.runAsync("ab-design");

        // INV-3 + dual-bucket assertion: pipeline gets target (2) + regression
        // (2, NOT 3 — tgtB removed) = 4 scenarios total. Wrong-agent scenarios
        // (no role) would not be in either query result, so cannot leak.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EvalScenarioEntity>> allCaptor =
                (ArgumentCaptor<List<EvalScenarioEntity>>) (ArgumentCaptor<?>)
                        ArgumentCaptor.forClass(List.class);
        verify(abEvalPipeline).runWithExplicitDefs(anyString(), allCaptor.capture(), any(), any());
        List<EvalScenarioEntity> pipelineInput = allCaptor.getValue();
        assertThat(pipelineInput).extracting(EvalScenarioEntity::getId)
                .containsExactly("tgt-A", "tgt-B", "gen-X", "gen-Y");  // target first, then regression
        // tgtB must appear EXACTLY once (in target), not duplicated in regression.
        long tgtBCount = pipelineInput.stream().filter(s -> "tgt-B".equals(s.getId())).count();
        assertThat(tgtBCount).isEqualTo(1L);

        ArgumentCaptor<BehaviorRuleAbRunEntity> savedCaptor =
                ArgumentCaptor.forClass(BehaviorRuleAbRunEntity.class);
        verify(abRunRepository, times(2)).save(savedCaptor.capture());
        BehaviorRuleAbRunEntity completed = savedCaptor.getAllValues().get(1);
        assertThat(completed.getStatus()).isEqualTo(BehaviorRuleAbRunEntity.STATUS_COMPLETED);
        assertThat(completed.getTargetCount()).isEqualTo(2);
        assertThat(completed.getRegressionCount()).isEqualTo(2);  // genX + genY only

        // Fallback path must NOT have been taken.
        verify(scenarioRepository, never()).findAllByDatasetVersionId(anyString());
    }

    @DisplayName("general owner → fallback regression-only mode (full dataset)")
    @Test
    void general_owner_fallback_regression_only() {
        BehaviorRuleVersionEntity v = newCandidateVersion("v1");
        BehaviorRuleAbRunEntity abRun = newAbRun("ab-gen", "v1");
        when(abRunRepository.findById("ab-gen")).thenReturn(Optional.of(abRun));
        when(versionRepository.findById("v1")).thenReturn(Optional.of(v));
        AgentEntity agent = new AgentEntity();
        agent.setId(100L);
        // Name that resolves to GENERAL — falls through all explicit patterns.
        agent.setName("Generic Worker Bot");
        when(agentRepository.findById(100L)).thenReturn(Optional.of(agent));
        when(agentService.toAgentDefinition(agent)).thenReturn(new AgentDefinition());

        EvalScenarioEntity s1 = newScenario("s-1");
        EvalScenarioEntity s2 = newScenario("s-2");
        when(scenarioRepository.findAllByDatasetVersionId("dv-1"))
                .thenReturn(List.of(s1, s2));
        when(abEvalPipeline.runWithExplicitDefs(anyString(), anyList(), any(), any()))
                .thenReturn(List.of(
                        result("s-1", 0.4, 0.6),
                        result("s-2", 0.4, 0.6)));

        service.runAsync("ab-gen");

        // Role-aware queries must NOT have been called when owner=general.
        verify(scenarioRepository, never())
                .findByDatasetVersionAndAgentRoles(anyString(), any(String[].class));
        verify(scenarioRepository).findAllByDatasetVersionId("dv-1");

        ArgumentCaptor<BehaviorRuleAbRunEntity> savedCaptor =
                ArgumentCaptor.forClass(BehaviorRuleAbRunEntity.class);
        verify(abRunRepository, times(2)).save(savedCaptor.capture());
        BehaviorRuleAbRunEntity completed = savedCaptor.getAllValues().get(1);
        assertThat(completed.getStatus()).isEqualTo(BehaviorRuleAbRunEntity.STATUS_COMPLETED);
        assertThat(completed.getTargetCount()).isZero();
        assertThat(completed.getRegressionCount()).isEqualTo(2);
        // Fallback → target_delta_pp null (matches INV-4 semantics)
        assertThat(completed.getTargetDeltaPp()).isNull();
        assertThat(completed.getRegressionDeltaPp()).isNotNull();
    }

    @DisplayName("design owner but no scenarios match role → fallback regression-only (full dataset)")
    @Test
    void design_owner_no_target_scenarios_falls_back() {
        BehaviorRuleVersionEntity v = newCandidateVersion("v1");
        BehaviorRuleAbRunEntity abRun = newAbRun("ab-empty", "v1");
        when(abRunRepository.findById("ab-empty")).thenReturn(Optional.of(abRun));
        when(versionRepository.findById("v1")).thenReturn(Optional.of(v));
        AgentEntity agent = new AgentEntity();
        agent.setId(100L);
        agent.setName("Design Agent");
        when(agentRepository.findById(100L)).thenReturn(Optional.of(agent));
        when(agentService.toAgentDefinition(agent)).thenReturn(new AgentDefinition());

        // No design-tagged scenarios in this dataset version yet.
        when(scenarioRepository.findByDatasetVersionAndAgentRoles(eq("dv-1"), rolesEq("design")))
                .thenReturn(List.of());
        EvalScenarioEntity s1 = newScenario("s-1");
        when(scenarioRepository.findAllByDatasetVersionId("dv-1")).thenReturn(List.of(s1));
        when(abEvalPipeline.runWithExplicitDefs(anyString(), anyList(), any(), any()))
                .thenReturn(List.of(result("s-1", 0.5, 0.5)));

        service.runAsync("ab-empty");

        // General fan-out query must NOT have been called when target was
        // empty (we go straight to findAllByDatasetVersionId).
        verify(scenarioRepository, never())
                .findByDatasetVersionAndAgentRoles(anyString(), rolesEq("general"));
        verify(scenarioRepository).findAllByDatasetVersionId("dv-1");

        ArgumentCaptor<BehaviorRuleAbRunEntity> savedCaptor =
                ArgumentCaptor.forClass(BehaviorRuleAbRunEntity.class);
        verify(abRunRepository, times(2)).save(savedCaptor.capture());
        BehaviorRuleAbRunEntity completed = savedCaptor.getAllValues().get(1);
        assertThat(completed.getStatus()).isEqualTo(BehaviorRuleAbRunEntity.STATUS_COMPLETED);
        assertThat(completed.getTargetCount()).isZero();
        assertThat(completed.getRegressionCount()).isEqualTo(1);
        assertThat(completed.getTargetDeltaPp()).isNull();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    /** Mockito matcher: String[] equal to a single-element {expectedRole}. */
    private static String[] rolesEq(String expectedRole) {
        ArgumentMatcher<String[]> matcher = a ->
                a != null && a.length == 1 && expectedRole.equals(a[0]);
        return argThat(matcher);
    }

    private BehaviorRuleVersionEntity newCandidateVersion(String id) {
        BehaviorRuleVersionEntity v = new BehaviorRuleVersionEntity();
        v.setId(id);
        v.setAgentId("100");
        v.setVersionNumber(1);
        v.setStatus(BehaviorRuleVersionEntity.STATUS_CANDIDATE);
        v.setRulesJson("[]");
        v.setSource(BehaviorRuleVersionEntity.SOURCE_MANUAL);
        return v;
    }

    private BehaviorRuleAbRunEntity newAbRun(String id, String candidateVersionId) {
        BehaviorRuleAbRunEntity r = new BehaviorRuleAbRunEntity();
        r.setId(id);
        r.setAgentId("100");
        r.setCandidateVersionId(candidateVersionId);
        r.setBaselineVersionId("");
        r.setStatus(BehaviorRuleAbRunEntity.STATUS_PENDING);
        r.setDatasetVersionId("dv-1");
        r.setAbRunKind(BehaviorRuleAbRunEntity.KIND_WITH_VS_WITHOUT);
        return r;
    }

    private EvalScenarioEntity newScenario(String id) {
        EvalScenarioEntity s = new EvalScenarioEntity();
        s.setId(id);
        s.setName(id);
        s.setTask("task " + id);
        return s;
    }

    private AbScenarioResult result(String id, double baselineScore, double candidateScore) {
        return new AbScenarioResult(id, id,
                new AbScenarioResult.RunResult("PENDING_JUDGE", baselineScore),
                new AbScenarioResult.RunResult("PENDING_JUDGE", candidateScore));
    }
}
