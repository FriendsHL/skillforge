package com.skillforge.server.improve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.eval.EvalJudgeOutput;
import com.skillforge.server.eval.scenario.EvalScenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BC-M2a multi-round aggregation + rounds resolution. These exercise the pure,
 * engine-free parts of the behavioral multi-round path:
 * {@link AbEvalPipeline#aggregateBehavioralRounds(List)} (recurrence-rate → score
 * mapping) and {@link AbEvalPipeline#resolveBehavioralRounds(ObjectMapper, EvalScenario)}
 * (default/absent/clamp behavior, single-round backward compatibility).
 */
class AbEvalPipelineBehavioralRoundsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Per-round behavioral judge output: outcome is a hard 0/100; efficiency 100 (fast). */
    private static EvalJudgeOutput round(double outcome) {
        return round(outcome, 100.0);
    }

    private static EvalJudgeOutput round(double outcome, double efficiency) {
        EvalJudgeOutput o = new EvalJudgeOutput();
        o.setOutcomeScore(outcome);
        o.setEfficiencyScore(efficiency);
        return o;
    }

    private static List<EvalJudgeOutput> rounds(double... outcomes) {
        List<EvalJudgeOutput> list = new ArrayList<>();
        for (double o : outcomes) list.add(round(o));
        return list;
    }

    @Test
    @DisplayName("all 5 rounds reproduce the failure (outcome 0) → aggregate outcome 0, FAIL")
    void aggregate_allRecur_outcomeZero() {
        EvalJudgeOutput out = AbEvalPipeline.aggregateBehavioralRounds(rounds(0, 0, 0, 0, 0));

        assertThat(out.getOutcomeScore()).isEqualTo(0.0);
        // composite = 0.7*0 + 0.3*100 = 30 < 40 → FAIL
        assertThat(out.getCompositeScore()).isEqualTo(30.0);
        assertThat(out.isPass()).isFalse();
    }

    @Test
    @DisplayName("all 5 rounds clean (outcome 100) → aggregate outcome 100, PASS")
    void aggregate_allClean_outcome100() {
        EvalJudgeOutput out = AbEvalPipeline.aggregateBehavioralRounds(rounds(100, 100, 100, 100, 100));

        assertThat(out.getOutcomeScore()).isEqualTo(100.0);
        assertThat(out.getCompositeScore()).isEqualTo(100.0);
        assertThat(out.isPass()).isTrue();
    }

    @Test
    @DisplayName("partial: 3/5 clean → outcome 60, composite 0.7*60 + 0.3*100 = 72, PASS")
    void aggregate_partial_rateCorrect() {
        EvalJudgeOutput out = AbEvalPipeline.aggregateBehavioralRounds(rounds(100, 0, 100, 0, 100));

        assertThat(out.getOutcomeScore()).isEqualTo(60.0);
        assertThat(out.getCompositeScore()).isEqualTo(72.0);
        assertThat(out.isPass()).isTrue();
        // recurrence/no-engagement rate = (1 - 3/5)*100 = 40%
        assertThat(out.getMetaJudgeRationale()).contains("3/5").contains("40%");
    }

    @Test
    @DisplayName("FIX-C: a zeroed infra round drags efficiency down, does not inflate it")
    void aggregate_infraRoundZeroed_doesNotInflateEfficiency() {
        // One clean round (outcome 100, eff 100) + one infra round zeroed to (0, 0):
        // outcome mean 50, efficiency mean 50, composite = 0.7*50 + 0.3*50 = 50.
        // If the infra round had instead carried efficiency 100, efficiency mean
        // would be 100 and composite 65 — the inflation FIX-C closes.
        EvalJudgeOutput out = AbEvalPipeline.aggregateBehavioralRounds(
                List.of(round(100.0, 100.0), round(0.0, 0.0)));

        assertThat(out.getOutcomeScore()).isEqualTo(50.0);
        assertThat(out.getEfficiencyScore()).isEqualTo(50.0);
        assertThat(out.getCompositeScore()).isEqualTo(50.0);
    }

    @Test
    @DisplayName("rounds absent (BC-M1 oracle) → single round (backward compatible)")
    void resolveRounds_absent_singleRound() {
        EvalScenario sc = behavioral(
                "{\"tool\":\"Edit\",\"errorSignature\":\"old_string not found\",\"passWhen\":\"no_match\"}");
        assertThat(AbEvalPipeline.resolveBehavioralRounds(objectMapper, sc)).isEqualTo(1);
    }

    @Test
    @DisplayName("rounds=5 → 5 rounds")
    void resolveRounds_explicit_returnsValue() {
        EvalScenario sc = behavioral(
                "{\"tool\":\"Edit\",\"errorSignature\":\"x\",\"passWhen\":\"no_match\",\"rounds\":5}");
        assertThat(AbEvalPipeline.resolveBehavioralRounds(objectMapper, sc)).isEqualTo(5);
    }

    @Test
    @DisplayName("rounds<=1 → single round")
    void resolveRounds_le1_singleRound() {
        EvalScenario sc = behavioral(
                "{\"tool\":\"Edit\",\"errorSignature\":\"x\",\"passWhen\":\"no_match\",\"rounds\":1}");
        assertThat(AbEvalPipeline.resolveBehavioralRounds(objectMapper, sc)).isEqualTo(1);
        EvalScenario zero = behavioral(
                "{\"tool\":\"Edit\",\"errorSignature\":\"x\",\"passWhen\":\"no_match\",\"rounds\":0}");
        assertThat(AbEvalPipeline.resolveBehavioralRounds(objectMapper, zero)).isEqualTo(1);
    }

    @Test
    @DisplayName("malformed oracle JSON → single round (safe default)")
    void resolveRounds_malformed_singleRound() {
        EvalScenario sc = behavioral("not json");
        assertThat(AbEvalPipeline.resolveBehavioralRounds(objectMapper, sc)).isEqualTo(1);
    }

    private static EvalScenario behavioral(String oracleExpected) {
        EvalScenario sc = new EvalScenario();
        sc.setId("badcase-1");
        EvalScenario.ScenarioOracle oracle = new EvalScenario.ScenarioOracle();
        oracle.setType("tool_error_absence");
        oracle.setExpected(oracleExpected);
        sc.setOracle(oracle);
        return sc;
    }
}
