package com.skillforge.server.improve.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.server.entity.AgentEvolveAbRunEntity;
import com.skillforge.server.improve.AbEvalPipeline;
import com.skillforge.server.improve.AbScenarioResult;
import com.skillforge.server.improve.AbScenarioResult.RunResult;
import com.skillforge.server.improve.behavior.AgentRoleResolver;
import com.skillforge.server.repository.AgentEvolveAbRunRepository;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.EvalScenarioDraftRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AUTOEVOLVE-AGENT-LEVEL-BUNDLE Phase 1 — {@link AgentEvolveAbEvalService} unit
 * tests: the pure {@link AgentEvolveAbEvalService#computeDeltas} function (§7 W5)
 * and the §7 W1 winner-carry-forward consistency guard in {@code startAgentAb}.
 *
 * <p>The coordinator executor is mocked (no-op) so {@code startAgentAb} does NOT
 * fire {@code runAsync} — these tests pin the synchronous row-build + W1 guard.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentEvolveAbEvalService")
class AgentEvolveAbEvalServiceTest {

    @Mock private AgentEvolveAbRunRepository abRunRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private BundleApplicator bundleApplicator;
    @Mock private AbEvalPipeline abEvalPipeline;
    @Mock private EvalScenarioDraftRepository scenarioRepository;
    @Mock private AgentRoleResolver agentRoleResolver;
    @Mock private com.skillforge.server.service.EvalDatasetService evalDatasetService;
    @Mock private ChatEventBroadcaster broadcaster;
    @Mock private ExecutorService coordinatorExecutor;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AgentEvolveAbEvalService service;

    @BeforeEach
    void setUp() {
        service = new AgentEvolveAbEvalService(
                abRunRepository, agentRepository, bundleApplicator, abEvalPipeline,
                scenarioRepository, agentRoleResolver, evalDatasetService, broadcaster, objectMapper,
                coordinatorExecutor);
    }

    // ---- computeDeltas (pure) -------------------------------------------------

    private RunResult pass() { return new RunResult("PENDING_JUDGE", 80.0); }
    private RunResult fail() { return new RunResult("PENDING_JUDGE", 10.0); }

    @Test
    @DisplayName("computeDeltas: both sides measured → rates and delta from per-scenario counts")
    void computeDeltas_bothSidesMeasured() {
        // candidate: 2/3 pass = 66.67; baseline: 1/3 pass = 33.33; delta = 33.33
        List<AbScenarioResult> perScenario = List.of(
                new AbScenarioResult("s1", "s1", pass(), pass()),
                new AbScenarioResult("s2", "s2", fail(), pass()),
                new AbScenarioResult("s3", "s3", fail(), fail()));

        AgentEvolveAbEvalService.AgentAbDeltas d =
                AgentEvolveAbEvalService.computeDeltas(perScenario, null);

        assertThat(d.candidatePassRate()).isCloseTo(66.666, org.assertj.core.data.Offset.offset(0.01));
        assertThat(d.baselinePassRate()).isCloseTo(33.333, org.assertj.core.data.Offset.offset(0.01));
        assertThat(d.deltaPassRate()).isCloseTo(33.333, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    @DisplayName("computeDeltas: cached baseline rate used verbatim; baseline side NOT counted")
    void computeDeltas_cachedBaseline() {
        // baseline side is the CACHED sentinel (never measured); cachedRate=60 used as-is.
        List<AbScenarioResult> perScenario = List.of(
                new AbScenarioResult("s1", "s1", new RunResult("CACHED", 0.0), pass()),
                new AbScenarioResult("s2", "s2", new RunResult("CACHED", 0.0), pass()),
                new AbScenarioResult("s3", "s3", new RunResult("CACHED", 0.0), fail()));

        AgentEvolveAbEvalService.AgentAbDeltas d =
                AgentEvolveAbEvalService.computeDeltas(perScenario, 60.0);

        assertThat(d.baselinePassRate()).isEqualTo(60.0);
        assertThat(d.candidatePassRate()).isCloseTo(66.666, org.assertj.core.data.Offset.offset(0.01));
        assertThat(d.deltaPassRate()).isCloseTo(6.666, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    @DisplayName("computeDeltas: empty per-scenario → zero rates")
    void computeDeltas_empty() {
        AgentEvolveAbEvalService.AgentAbDeltas d =
                AgentEvolveAbEvalService.computeDeltas(List.of(), null);
        assertThat(d.candidatePassRate()).isEqualTo(0.0);
        assertThat(d.baselinePassRate()).isEqualTo(0.0);
        assertThat(d.deltaPassRate()).isEqualTo(0.0);
    }

    // ---- computeSubsetDeltas (pure, §8 子点② vs-best) -------------------------

    @Test
    @DisplayName("computeSubsetDeltas (fresh): vs this run's baseline side per subset")
    void computeSubsetDeltas_fresh() {
        // target = {t1,t2}; general = {g1,g2}
        // candidate: t1 pass, t2 pass (target 100); g1 pass, g2 fail (general 50)
        // baseline:  t1 pass, t2 fail (target 50);  g1 fail, g2 fail (general 0)
        List<AbScenarioResult> run = List.of(
                new AbScenarioResult("t1", "t1", pass(), pass()),
                new AbScenarioResult("t2", "t2", fail(), pass()),
                new AbScenarioResult("g1", "g1", fail(), pass()),
                new AbScenarioResult("g2", "g2", fail(), fail()));
        Set<String> targetIds = Set.of("t1", "t2");
        Set<String> generalIds = Set.of("g1", "g2");

        AgentEvolveAbEvalService.SubsetDeltas d =
                AgentEvolveAbEvalService.computeSubsetDeltas(run, null, targetIds, generalIds);

        // target: candidate 100 − baseline 50 = +50 ; general: candidate 50 − baseline 0 = +50
        assertThat(d.targetDeltaPp()).isCloseTo(50.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(d.regressionDeltaPp()).isCloseTo(50.0, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    @DisplayName("computeSubsetDeltas (skip): best from prior winner's CANDIDATE side (子点①)")
    void computeSubsetDeltas_skip_usesPriorWinnerCandidateSide() {
        // current run: candidate side only meaningful (baseline = CACHED sentinel)
        // candidate target {t1,t2}: both pass (100); general {g1,g2}: g1 pass g2 pass (100)
        List<AbScenarioResult> currentRun = List.of(
                new AbScenarioResult("t1", "t1", new RunResult("CACHED", 0.0), pass()),
                new AbScenarioResult("t2", "t2", new RunResult("CACHED", 0.0), pass()),
                new AbScenarioResult("g1", "g1", new RunResult("CACHED", 0.0), pass()),
                new AbScenarioResult("g2", "g2", new RunResult("CACHED", 0.0), pass()));
        // prior winner's CANDIDATE side = best: target t1 pass t2 fail (50); general g1 pass g2 fail (50)
        List<AbScenarioResult> priorWinner = List.of(
                new AbScenarioResult("t1", "t1", fail(), pass()),
                new AbScenarioResult("t2", "t2", fail(), fail()),
                new AbScenarioResult("g1", "g1", fail(), pass()),
                new AbScenarioResult("g2", "g2", fail(), fail()));
        Set<String> targetIds = Set.of("t1", "t2");
        Set<String> generalIds = Set.of("g1", "g2");

        AgentEvolveAbEvalService.SubsetDeltas d =
                AgentEvolveAbEvalService.computeSubsetDeltas(currentRun, priorWinner, targetIds, generalIds);

        // target: candidate 100 − best 50 = +50 ; general: candidate 100 − best 50 = +50
        assertThat(d.targetDeltaPp()).isCloseTo(50.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(d.regressionDeltaPp()).isCloseTo(50.0, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    @DisplayName("computeSubsetDeltas: empty target subset → targetDeltaPp null (regression-only)")
    void computeSubsetDeltas_emptyTarget_nullTargetDelta() {
        List<AbScenarioResult> run = List.of(
                new AbScenarioResult("g1", "g1", fail(), pass()),
                new AbScenarioResult("g2", "g2", fail(), fail()));
        AgentEvolveAbEvalService.SubsetDeltas d = AgentEvolveAbEvalService.computeSubsetDeltas(
                run, null, Set.of(), Set.of("g1", "g2"));

        assertThat(d.targetDeltaPp()).isNull();
        assertThat(d.regressionDeltaPp()).isCloseTo(50.0, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    @DisplayName("passRateOver: counts only the subset, by side")
    void passRateOver_subsetAndSide() {
        List<AbScenarioResult> run = List.of(
                new AbScenarioResult("a", "a", pass(), fail()),
                new AbScenarioResult("b", "b", fail(), pass()),
                new AbScenarioResult("c", "c", pass(), pass()));   // c not in subset
        Set<String> ids = Set.of("a", "b");
        // baseline side: a pass, b fail → 50 ; candidate side: a fail, b pass → 50
        assertThat(AgentEvolveAbEvalService.passRateOver(run, ids, false)).isEqualTo(50.0);
        assertThat(AgentEvolveAbEvalService.passRateOver(run, ids, true)).isEqualTo(50.0);
        assertThat(AgentEvolveAbEvalService.passRateOver(run, Set.of(), true)).isEqualTo(0.0);
    }

    // ---- startAgentAb W1 guard ------------------------------------------------

    @Test
    @DisplayName("startAgentAb (fresh, no cached rate) builds a PENDING row + defers run")
    void startAgentAb_freshBaseline_buildsPendingRow() {
        Bundle candidate = new Bundle("pv-cand", null);
        Bundle baseline = new Bundle(null, null);
        lenient().when(abRunRepository.findFirstByAgentIdAndCandidateBundleJsonAndStatusInOrderByStartedAtDesc(
                anyString(), anyString(), any())).thenReturn(Optional.empty());
        when(abRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String abRunId = service.startAgentAb(candidate, baseline, "7", "dv-1", null);

        assertThat(abRunId).isNotBlank();
        ArgumentCaptor<AgentEvolveAbRunEntity> captor = ArgumentCaptor.forClass(AgentEvolveAbRunEntity.class);
        verify(abRunRepository).save(captor.capture());
        AgentEvolveAbRunEntity saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(AgentEvolveAbRunEntity.STATUS_PENDING);
        assertThat(saved.isSkipBaseline()).isFalse();
        assertThat(saved.getCachedBaselineRate()).isNull();
        assertThat(saved.getPriorWinnerAbRunId()).isNull();
        assertThat(saved.getAgentId()).isEqualTo("7");
        // No active tx in a plain unit call → defers immediately on the (mocked) executor.
        verify(coordinatorExecutor).execute(any());
    }

    @Test
    @DisplayName("startAgentAb (B1) null datasetVersionId → resolves the agent's default dataset")
    void startAgentAb_nullDataset_resolvesAgentDefault() {
        Bundle candidate = new Bundle("pv-cand", null);
        Bundle baseline = new Bundle(null, null);
        lenient().when(abRunRepository.findFirstByAgentIdAndCandidateBundleJsonAndStatusInOrderByStartedAtDesc(
                anyString(), anyString(), any())).thenReturn(Optional.empty());
        when(abRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(evalDatasetService.findDefaultVersionIdForAgent("7")).thenReturn("dv-default");

        String abRunId = service.startAgentAb(candidate, baseline, "7", null, null);

        assertThat(abRunId).isNotBlank();
        ArgumentCaptor<AgentEvolveAbRunEntity> captor = ArgumentCaptor.forClass(AgentEvolveAbRunEntity.class);
        verify(abRunRepository).save(captor.capture());
        assertThat(captor.getValue().getDatasetVersionId()).isEqualTo("dv-default");
    }

    @Test
    @DisplayName("startAgentAb (B1) null datasetVersionId + no agent default → fails loud")
    void startAgentAb_nullDataset_noDefault_throws() {
        Bundle candidate = new Bundle("pv-cand", null);
        Bundle baseline = new Bundle(null, null);
        when(evalDatasetService.findDefaultVersionIdForAgent("7")).thenReturn(null);

        assertThatThrownBy(() -> service.startAgentAb(candidate, baseline, "7", null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No dataset version available");
    }

    @Test
    @DisplayName("startAgentAb (skip) with no prior COMPLETED winner fails loud (W1)")
    void startAgentAb_skip_noPriorWinner_throws() {
        when(abRunRepository.findFirstByAgentIdAndStatusOrderByCompletedAtDesc(
                eq("7"), eq(AgentEvolveAbRunEntity.STATUS_COMPLETED))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.startAgentAb(
                new Bundle("pv-cand", null), new Bundle("pv-best", null), "7", "dv-1", 60.0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no prior COMPLETED");
        verify(abRunRepository, never()).save(any());
    }

    @Test
    @DisplayName("startAgentAb (skip) with baseline ≠ prior winner candidate fails loud (W1)")
    void startAgentAb_skip_bundleMismatch_throws() throws Exception {
        AgentEvolveAbRunEntity priorWinner = new AgentEvolveAbRunEntity();
        priorWinner.setId("ab-prior");
        // prior winner's candidate bundle was {pv-OTHER} — does NOT match the
        // incoming baseline {pv-best}.
        priorWinner.setCandidateBundleJson(objectMapper.writeValueAsString(new Bundle("pv-OTHER", null)));
        when(abRunRepository.findFirstByAgentIdAndStatusOrderByCompletedAtDesc(
                eq("7"), eq(AgentEvolveAbRunEntity.STATUS_COMPLETED)))
                .thenReturn(Optional.of(priorWinner));

        assertThatThrownBy(() -> service.startAgentAb(
                new Bundle("pv-cand", null), new Bundle("pv-best", null), "7", "dv-1", 60.0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("consistency violation");
        verify(abRunRepository, never()).save(any());
    }

    @Test
    @DisplayName("startAgentAb (skip) with matching prior winner sets skipBaseline + priorWinnerAbRunId")
    void startAgentAb_skip_matchingWinner_setsPriorRef() throws Exception {
        Bundle best = new Bundle("pv-best", null);
        AgentEvolveAbRunEntity priorWinner = new AgentEvolveAbRunEntity();
        priorWinner.setId("ab-prior");
        priorWinner.setCandidateBundleJson(objectMapper.writeValueAsString(best));
        priorWinner.setDatasetVersionId("dv-1");   // same dataset version as this run
        when(abRunRepository.findFirstByAgentIdAndStatusOrderByCompletedAtDesc(
                eq("7"), eq(AgentEvolveAbRunEntity.STATUS_COMPLETED)))
                .thenReturn(Optional.of(priorWinner));
        lenient().when(abRunRepository.findFirstByAgentIdAndCandidateBundleJsonAndStatusInOrderByStartedAtDesc(
                anyString(), anyString(), any())).thenReturn(Optional.empty());
        when(abRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String abRunId = service.startAgentAb(new Bundle("pv-cand", null), best, "7", "dv-1", 60.0);

        assertThat(abRunId).isNotBlank();
        ArgumentCaptor<AgentEvolveAbRunEntity> captor = ArgumentCaptor.forClass(AgentEvolveAbRunEntity.class);
        verify(abRunRepository).save(captor.capture());
        AgentEvolveAbRunEntity saved = captor.getValue();
        assertThat(saved.isSkipBaseline()).isTrue();
        assertThat(saved.getCachedBaselineRate()).isEqualTo(60.0);
        assertThat(saved.getPriorWinnerAbRunId()).isEqualTo("ab-prior");
    }

    @Test
    @DisplayName("startAgentAb rejects an out-of-range cached rate")
    void startAgentAb_cachedRateOutOfRange_throws() {
        assertThatThrownBy(() -> service.startAgentAb(
                new Bundle("pv-cand", null), new Bundle("pv-best", null), "7", "dv-1", 150.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cachedBaselineRate");
    }

    @Test
    @DisplayName("startAgentAb (skip) bundle matches but prior winner scored a DIFFERENT dataset → fail loud (§8 子点① guard 1)")
    void startAgentAb_skip_datasetMismatch_throws() throws Exception {
        Bundle best = new Bundle("pv-best", null);
        AgentEvolveAbRunEntity priorWinner = new AgentEvolveAbRunEntity();
        priorWinner.setId("ab-prior");
        priorWinner.setCandidateBundleJson(objectMapper.writeValueAsString(best));
        priorWinner.setDatasetVersionId("dv-OLD");   // prior winner scored a different dataset version
        when(abRunRepository.findFirstByAgentIdAndStatusOrderByCompletedAtDesc(
                eq("7"), eq(AgentEvolveAbRunEntity.STATUS_COMPLETED)))
                .thenReturn(Optional.of(priorWinner));

        assertThatThrownBy(() -> service.startAgentAb(
                new Bundle("pv-cand", null), best, "7", "dv-1", 60.0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dataset mismatch");
        verify(abRunRepository, never()).save(any());
    }

    // ---- assertPriorWinnerCoversSubsets (§8 子点① guard 2, role-flip) ----------

    @Test
    @DisplayName("assertPriorWinnerCoversSubsets: prior winner covers the union → no throw")
    void coverageGuard_covers_ok() {
        List<AbScenarioResult> priorWinner = List.of(
                new AbScenarioResult("t1", "t1", pass(), pass()),
                new AbScenarioResult("g1", "g1", pass(), pass()),
                new AbScenarioResult("g2", "g2", pass(), pass()));   // superset is fine
        // Should NOT throw.
        AgentEvolveAbEvalService.assertPriorWinnerCoversSubsets(
                priorWinner, Set.of("t1"), Set.of("g1"), "ab-prior");
    }

    @Test
    @DisplayName("assertPriorWinnerCoversSubsets: current id missing from prior winner JSON → fail loud (role flip)")
    void coverageGuard_missingId_throws() {
        // prior winner measured only {t1, g1}; current run's role flip introduced g2.
        List<AbScenarioResult> priorWinner = List.of(
                new AbScenarioResult("t1", "t1", pass(), pass()),
                new AbScenarioResult("g1", "g1", pass(), pass()));

        assertThatThrownBy(() -> AgentEvolveAbEvalService.assertPriorWinnerCoversSubsets(
                priorWinner, Set.of("t1"), Set.of("g1", "g2"), "ab-prior"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("id-set mismatch")
                .hasMessageContaining("g2");
    }

    // ---- resolveRoleSplit (§8 R2, W-2 recommended) ----------------------------

    @Test
    @DisplayName("resolveRoleSplit: GENERAL-role agent → regression-only (no target, full dataset)")
    void resolveRoleSplit_generalRole_regressionOnly() throws Exception {
        com.skillforge.server.entity.AgentEntity agent = new com.skillforge.server.entity.AgentEntity();
        agent.setId(7L);
        when(agentRoleResolver.resolveRole(agent))
                .thenReturn(com.skillforge.server.improve.behavior.AgentRoleConstants.GENERAL);
        when(scenarioRepository.findAllByDatasetVersionId("dv-1"))
                .thenReturn(List.of(scenarioEntity("g1"), scenarioEntity("g2")));

        Object split = invokeResolveRoleSplit(agent, "dv-1");

        assertThat(targetIdsOf(split)).isEmpty();
        assertThat(generalIdsOf(split)).containsExactlyInAnyOrder("g1", "g2");
        verify(scenarioRepository).findAllByDatasetVersionId("dv-1");
    }

    @Test
    @DisplayName("resolveRoleSplit: non-GENERAL role with NO matching scenarios → regression-only fallback")
    void resolveRoleSplit_noMatch_regressionOnlyFallback() throws Exception {
        com.skillforge.server.entity.AgentEntity agent = new com.skillforge.server.entity.AgentEntity();
        agent.setId(7L);
        when(agentRoleResolver.resolveRole(agent))
                .thenReturn(com.skillforge.server.improve.behavior.AgentRoleConstants.CODE);
        when(scenarioRepository.findByDatasetVersionAndAgentRoles(eq("dv-1"), any()))
                .thenReturn(List.of());   // no scenarios match this role
        when(scenarioRepository.findAllByDatasetVersionId("dv-1"))
                .thenReturn(List.of(scenarioEntity("g1"), scenarioEntity("g2"), scenarioEntity("g3")));

        Object split = invokeResolveRoleSplit(agent, "dv-1");

        assertThat(targetIdsOf(split)).isEmpty();
        assertThat(generalIdsOf(split)).containsExactlyInAnyOrder("g1", "g2", "g3");
        verify(scenarioRepository).findAllByDatasetVersionId("dv-1");
    }

    private com.skillforge.server.entity.EvalScenarioEntity scenarioEntity(String id) {
        com.skillforge.server.entity.EvalScenarioEntity e =
                new com.skillforge.server.entity.EvalScenarioEntity();
        e.setId(id);
        e.setName(id);
        return e;
    }

    // resolveRoleSplit + RoleSplit are private; reflect for the W-2 split tests
    // (the public surface is runAsync, which needs the full pipeline mocked).
    private Object invokeResolveRoleSplit(Object agent, String datasetVersionId) throws Exception {
        java.lang.reflect.Method m = AgentEvolveAbEvalService.class.getDeclaredMethod(
                "resolveRoleSplit", com.skillforge.server.entity.AgentEntity.class, String.class);
        m.setAccessible(true);
        return m.invoke(service, agent, datasetVersionId);
    }

    @SuppressWarnings("unchecked")
    private Set<String> targetIdsOf(Object roleSplit) throws Exception {
        java.lang.reflect.Method m = roleSplit.getClass().getDeclaredMethod("targetIds");
        m.setAccessible(true);
        return (Set<String>) m.invoke(roleSplit);
    }

    @SuppressWarnings("unchecked")
    private Set<String> generalIdsOf(Object roleSplit) throws Exception {
        java.lang.reflect.Method m = roleSplit.getClass().getDeclaredMethod("generalIds");
        m.setAccessible(true);
        return (Set<String>) m.invoke(roleSplit);
    }
}
