package com.skillforge.server.improve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.entity.EvalTaskEntity;
import com.skillforge.server.eval.scenario.EvalScenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

/**
 * EVAL-429-ROBUSTNESS — unit tests for the {@code isMeasured} / {@code pairwiseMeasured}
 * helpers (D1 摘 ERROR/TIMEOUT/CACHED; D2 pairwise) and the {@code computeHeldOutBaselineRate}
 * / {@code isHistoricScenarioMeasured} Fix4 path (held-out baseline rate over measured
 * historical scenarios only, D3 null sentinel).
 */
@DisplayName("AbEvalPipeline infra-exclude helpers (EVAL-429-ROBUSTNESS)")
class AbEvalPipelineInfraExcludeTest {

    @Test
    @DisplayName("T1: ERROR is NOT measured")
    void error_notMeasured() {
        assertThat(AbEvalPipeline.isMeasured("ERROR")).isFalse();
    }

    @Test
    @DisplayName("T2: TIMEOUT is NOT measured")
    void timeout_notMeasured() {
        assertThat(AbEvalPipeline.isMeasured("TIMEOUT")).isFalse();
    }

    @Test
    @DisplayName("T3: CACHED sentinel is NOT measured; null is NOT measured")
    void cachedAndNull_notMeasured() {
        assertThat(AbEvalPipeline.isMeasured(AbEvalPipeline.BASELINE_CACHED_STATUS)).isFalse();
        assertThat(AbEvalPipeline.isMeasured(null)).isFalse();
    }

    @Test
    @DisplayName("T4: PASS / FAIL / VETO / PENDING_JUDGE are all measured (VETO = quality fail, kept)")
    void qualityStatuses_measured() {
        assertThat(AbEvalPipeline.isMeasured("PASS")).isTrue();
        assertThat(AbEvalPipeline.isMeasured("FAIL")).isTrue();
        assertThat(AbEvalPipeline.isMeasured("VETO")).isTrue();
        assertThat(AbEvalPipeline.isMeasured("PENDING_JUDGE")).isTrue();
    }

    @Test
    @DisplayName("T5: pairwiseMeasured requires BOTH sides measured (either ERROR → false)")
    void pairwise_eitherInfra_false() {
        assertThat(AbEvalPipeline.pairwiseMeasured("PASS", "FAIL")).isTrue();
        assertThat(AbEvalPipeline.pairwiseMeasured("ERROR", "PASS")).isFalse();
        assertThat(AbEvalPipeline.pairwiseMeasured("PASS", "TIMEOUT")).isFalse();
        assertThat(AbEvalPipeline.pairwiseMeasured("ERROR", "TIMEOUT")).isFalse();
        // CACHED baseline (skip path) is not pairwise-measured.
        assertThat(AbEvalPipeline.pairwiseMeasured("PASS", AbEvalPipeline.BASELINE_CACHED_STATUS)).isFalse();
    }

    // ---- Fix4: computeHeldOutBaselineRate + isHistoricScenarioMeasured (W2) ----------
    //
    // computeHeldOutBaselineRate is a private instance method that only touches the
    // injected ObjectMapper — construct a pipeline with a real ObjectMapper and nulls
    // for the unused collaborators, then reflect.

    private static AbEvalPipeline pipelineWithMapper() {
        return new AbEvalPipeline(
                null, null, null, null, null, null, null,
                new ObjectMapper(), null, null, 120_000L, null, null, 3, 0);
    }

    private static Double invokeComputeHeldOutBaselineRate(String scenarioResultsJson,
                                                           double overallPassRateFallback,
                                                           List<String> heldOutIds) throws Exception {
        EvalTaskEntity baselineRun = new EvalTaskEntity();
        baselineRun.setScenarioResultsJson(scenarioResultsJson);
        baselineRun.setOverallPassRate(overallPassRateFallback);
        List<EvalScenario> heldOut = heldOutIds.stream().map(id -> {
            EvalScenario s = new EvalScenario();
            s.setId(id);
            return s;
        }).toList();
        Method m = AbEvalPipeline.class.getDeclaredMethod(
                "computeHeldOutBaselineRate", EvalTaskEntity.class, List.class);
        m.setAccessible(true);
        return (Double) m.invoke(pipelineWithMapper(), baselineRun, heldOut);
    }

    @Test
    @DisplayName("W2-a: computeHeldOutBaselineRate drops ERROR rows from the denominator (1 PASS + 1 ERROR → 100%, not 50%)")
    void computeHeldOutBaselineRate_excludesErrorRow() throws Exception {
        String json = "[{\"scenarioId\":\"s1\",\"status\":\"PASS\",\"pass\":true},"
                + "{\"scenarioId\":\"s2\",\"status\":\"ERROR\",\"pass\":false}]";
        Double rate = invokeComputeHeldOutBaselineRate(json, 0.0, List.of("s1", "s2"));
        // Only s1 is measured → heldOutTotal=1, passed=1 → 100% (s2 ERROR excluded, NOT 50%).
        assertThat(rate).isCloseTo(100.0, offset(0.01));
    }

    @Test
    @DisplayName("W2-b: TIMEOUT rows excluded too")
    void computeHeldOutBaselineRate_excludesTimeoutRow() throws Exception {
        String json = "[{\"scenarioId\":\"s1\",\"status\":\"PASS\",\"pass\":true},"
                + "{\"scenarioId\":\"s2\",\"status\":\"TIMEOUT\",\"pass\":false}]";
        Double rate = invokeComputeHeldOutBaselineRate(json, 0.0, List.of("s1", "s2"));
        assertThat(rate).isCloseTo(100.0, offset(0.01));
    }

    @Test
    @DisplayName("W2-c: legacy rows with no status field are conservatively measured (1 PASS + 1 FAIL no-status → 50%)")
    void computeHeldOutBaselineRate_legacyNoStatus_conservativelyMeasured() throws Exception {
        String json = "[{\"scenarioId\":\"s1\",\"pass\":true},"
                + "{\"scenarioId\":\"s2\",\"pass\":false}]";
        Double rate = invokeComputeHeldOutBaselineRate(json, 0.0, List.of("s1", "s2"));
        // No status → both kept (conservative) → 1/2 = 50%.
        assertThat(rate).isCloseTo(50.0, offset(0.01));
    }

    @Test
    @DisplayName("W2-d: all held-out rows infra → not-measured sentinel (null, D3)")
    void computeHeldOutBaselineRate_allInfra_nullSentinel() throws Exception {
        String json = "[{\"scenarioId\":\"s1\",\"status\":\"ERROR\",\"pass\":false},"
                + "{\"scenarioId\":\"s2\",\"status\":\"TIMEOUT\",\"pass\":false}]";
        Double rate = invokeComputeHeldOutBaselineRate(json, 0.0, List.of("s1", "s2"));
        assertThat(rate).isNull();
    }
}
