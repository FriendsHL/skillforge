package com.skillforge.server.improve.agent;

import com.skillforge.server.improve.AbScenarioResult;
import com.skillforge.server.improve.AbScenarioResult.RunResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

/**
 * EVAL-429-ROBUSTNESS — gate-logic tests: {@link AgentEvolveAbEvalService#computeDeltas}
 * and {@link AgentEvolveAbEvalService#passRateOver} must exclude infra (ERROR/TIMEOUT)
 * scenarios from the denominator (D2 pairwise) and return the not-measured sentinel
 * (null) when nothing was measured (D3). Pure functions — no Spring/mocks.
 */
@DisplayName("AgentEvolveAbEvalService infra-exclude gate (EVAL-429-ROBUSTNESS)")
class AgentEvolveAbEvalServiceInfraExcludeTest {

    // status PASS/FAIL straddle the composite>=40 pass threshold via the score.
    private static RunResult pass() { return new RunResult("PASS", 80.0); }
    private static RunResult fail() { return new RunResult("FAIL", 10.0); }
    private static RunResult error() { return new RunResult("ERROR", 0.0); }
    private static RunResult timeout() { return new RunResult("TIMEOUT", 0.0); }
    private static RunResult cached() { return new RunResult("CACHED", 0.0); }

    @Test
    @DisplayName("T6: computeDeltas (fresh) drops pairwise-infra scenarios → measured=3, cand=base=66.67, delta=0")
    void computeDeltas_mixed_pairwiseExcludesInfra() {
        // 5 scenarios; 2 have an infra side (one candidate ERROR, one baseline TIMEOUT)
        // → both摘. Of the 3 measured: candidate 2 pass, baseline 2 pass.
        List<AbScenarioResult> run = List.of(
                new AbScenarioResult("s1", "s1", pass(), pass()),
                new AbScenarioResult("s2", "s2", pass(), pass()),
                new AbScenarioResult("s3", "s3", fail(), fail()),
                new AbScenarioResult("s4", "s4", pass(), error()),    // candidate infra → drop
                new AbScenarioResult("s5", "s5", timeout(), pass()));  // baseline infra → drop

        AgentEvolveAbEvalService.AgentAbDeltas d =
                AgentEvolveAbEvalService.computeDeltas(run, null);

        assertThat(d.candidatePassRate()).isCloseTo(66.666, offset(0.01));
        assertThat(d.baselinePassRate()).isCloseTo(66.666, offset(0.01));
        assertThat(d.deltaPassRate()).isCloseTo(0.0, offset(0.01));
    }

    @Test
    @DisplayName("T7: computeDeltas all-infra → not-measured sentinel (null,null,null) (D3)")
    void computeDeltas_allInfra_nullSentinel() {
        List<AbScenarioResult> run = List.of(
                new AbScenarioResult("s1", "s1", error(), error()),
                new AbScenarioResult("s2", "s2", timeout(), timeout()));

        AgentEvolveAbEvalService.AgentAbDeltas d =
                AgentEvolveAbEvalService.computeDeltas(run, null);

        assertThat(d.candidatePassRate()).isNull();
        assertThat(d.baselinePassRate()).isNull();
        assertThat(d.deltaPassRate()).isNull();
    }

    @Test
    @DisplayName("T8: computeDeltas backward-compat — all measured, no infra → byte-identical to old (cand66.67 base33.33 delta33.33)")
    void computeDeltas_allMeasured_backwardCompatible() {
        // AC-4: no infra → pairwise all true → denominator == total → numbers unchanged.
        List<AbScenarioResult> run = List.of(
                new AbScenarioResult("s1", "s1", pass(), pass()),
                new AbScenarioResult("s2", "s2", fail(), pass()),
                new AbScenarioResult("s3", "s3", fail(), fail()));

        AgentEvolveAbEvalService.AgentAbDeltas d =
                AgentEvolveAbEvalService.computeDeltas(run, null);

        assertThat(d.candidatePassRate()).isCloseTo(66.666, offset(0.01));
        assertThat(d.baselinePassRate()).isCloseTo(33.333, offset(0.01));
        assertThat(d.deltaPassRate()).isCloseTo(33.333, offset(0.01));
    }

    @Test
    @DisplayName("T9: computeDeltas (skip) cross-round pairwise — prior-winner ERROR overlap drops scenario, denominator=1")
    void computeDeltas_skip_priorWinnerInfra_dropsToIntersection() {
        // current run: 2 candidate-measured scenarios (baseline = CACHED sentinel).
        //   s1 candidate FAIL, s2 candidate PASS.
        // prior winner candidate side: s1 PASS (measured), s2 ERROR (infra → drop s2).
        // → measured intersection = {s1} only. candidate 0/1 = 0; baseline (prior s1) 1/1 = 100.
        // cachedBaselineRate (60.0) is IGNORED in prior-winner mode (recomputed pairwise).
        List<AbScenarioResult> currentRun = List.of(
                new AbScenarioResult("s1", "s1", cached(), fail()),
                new AbScenarioResult("s2", "s2", cached(), pass()));
        List<AbScenarioResult> priorWinner = List.of(
                new AbScenarioResult("s1", "s1", fail(), pass()),   // prior candidate side = PASS (measured)
                new AbScenarioResult("s2", "s2", fail(), error()));  // prior candidate side = ERROR → drop

        AgentEvolveAbEvalService.AgentAbDeltas d =
                AgentEvolveAbEvalService.computeDeltas(currentRun, 60.0, priorWinner);

        assertThat(d.candidatePassRate()).isCloseTo(0.0, offset(0.01));
        assertThat(d.baselinePassRate()).isCloseTo(100.0, offset(0.01));   // NOT the cached 60.0
        assertThat(d.deltaPassRate()).isCloseTo(-100.0, offset(0.01));
    }

    @Test
    @DisplayName("T10: passRateOver cross-round — own-side infra AND counterpart infra both摘 → same batch")
    void passRateOver_crossRound_excludesEitherInfra() {
        Set<String> ids = Set.of("a", "b", "c");
        // current candidate side: a PASS, b PASS, c ERROR
        List<AbScenarioResult> currentRun = List.of(
                new AbScenarioResult("a", "a", cached(), pass()),
                new AbScenarioResult("b", "b", cached(), pass()),
                new AbScenarioResult("c", "c", cached(), error()));
        // counterpart (prior winner) candidate side: a PASS, b TIMEOUT, c PASS
        List<AbScenarioResult> counterpart = List.of(
                new AbScenarioResult("a", "a", fail(), pass()),
                new AbScenarioResult("b", "b", fail(), timeout()),
                new AbScenarioResult("c", "c", fail(), pass()));

        // candidate side over current, pairwise vs counterpart:
        //   a: own PASS measured ∧ counterpart PASS measured → counted, pass.
        //   b: counterpart TIMEOUT → drop.
        //   c: own ERROR → drop.
        // → 1 measured, 1 pass = 100.0
        double candRate = AgentEvolveAbEvalService.passRateOver(currentRun, ids, true, counterpart);
        assertThat(candRate).isCloseTo(100.0, offset(0.01));

        // symmetric: counterpart candidate side, pairwise vs current → same single
        // scenario (a), proving both rates compare over the identical batch.
        double bestRate = AgentEvolveAbEvalService.passRateOver(counterpart, ids, true, currentRun);
        assertThat(bestRate).isCloseTo(100.0, offset(0.01));
    }
}
