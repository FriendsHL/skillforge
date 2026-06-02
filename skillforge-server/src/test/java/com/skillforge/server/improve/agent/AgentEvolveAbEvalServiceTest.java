package com.skillforge.server.improve.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.server.entity.AgentEvolveAbRunEntity;
import com.skillforge.server.improve.AbEvalPipeline;
import com.skillforge.server.improve.AbScenarioResult;
import com.skillforge.server.improve.AbScenarioResult.RunResult;
import com.skillforge.server.repository.AgentEvolveAbRunRepository;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.service.EvalDatasetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
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
    @Mock private EvalDatasetService evalDatasetService;
    @Mock private ChatEventBroadcaster broadcaster;
    @Mock private ExecutorService coordinatorExecutor;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AgentEvolveAbEvalService service;

    @BeforeEach
    void setUp() {
        service = new AgentEvolveAbEvalService(
                abRunRepository, agentRepository, bundleApplicator, abEvalPipeline,
                evalDatasetService, broadcaster, objectMapper, coordinatorExecutor);
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
}
