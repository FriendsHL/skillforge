package com.skillforge.server.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EvalScoreFormula")
class EvalScoreFormulaTest {

    @Test
    @DisplayName("calculate: combines quality efficiency latency and cost with M4_V1 weights")
    void calculate_combinesDimensionScoresWithM4Weights() {
        EvalScoreFormula.Result result = EvalScoreFormula.calculate(
                80.0,
                70.0,
                12_000L,
                10_000L,
                new BigDecimal("0.006"),
                new BigDecimal("0.010"),
                4,
                3
        );

        assertThat(result.formulaVersion()).isEqualTo("M4_V1");
        assertThat(result.qualityScore()).isEqualTo(80.0);
        assertThat(result.efficiencyScore()).isEqualTo(70.0);
        assertThat(result.latencyScore()).isEqualTo(80.0);
        assertThat(result.costScore()).isEqualTo(100.0);
        assertThat(result.compositeScore()).isEqualTo(80.0);
        assertThat(result.breakdownJson()).contains("\"formulaVersion\":\"M4_V1\"");
        assertThat(result.breakdownJson()).contains("\"quality\":0.55");
        assertThat(result.breakdownJson()).contains("\"latencyMs\":12000");
        assertThat(result.breakdownJson()).contains("\"costUsd\":0.006");
        assertThat(result.breakdownJson()).contains("\"qualityFloorApplied\":false");
    }

    @Test
    @DisplayName("calculate: low quality caps composite below pass threshold")
    void calculate_lowQualityCapsCompositeBelowPassThreshold() {
        EvalScoreFormula.Result result = EvalScoreFormula.calculate(
                20.0,
                100.0,
                1_000L,
                10_000L,
                new BigDecimal("0.001"),
                new BigDecimal("0.010"),
                1,
                1
        );

        assertThat(result.compositeScore()).isEqualTo(39.0);
        assertThat(result.breakdownJson()).contains("\"qualityFloorApplied\":true");
    }
}
