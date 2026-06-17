package com.skillforge.server.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Workflow-fix F5 (2026-06-07) — pins the evolve threshold defaults (S5 smoke):
 * prompt/skill delta 15→5, agent dual-criteria 0/−3 moved from constants,
 * min-measured-n=10 (F3), anchor-erosion-floor-pp=5 (F6). Also proves the
 * relaxed-binding kebab-case yml keys bind onto the camelCase fields.
 */
@DisplayName("EvolveThresholdProperties")
class EvolveThresholdPropertiesTest {

    @Test
    @DisplayName("defaults: prompt/skill=5, skill-min-candidate=40, agent 0/−3, minMeasuredN=10, anchor=5")
    void defaults() {
        EvolveThresholdProperties p = new EvolveThresholdProperties();

        assertThat(p.getPromptDeltaPp()).isEqualTo(5.0);
        assertThat(p.getSkillDeltaPp()).isEqualTo(5.0);
        assertThat(p.getSkillMinCandidateRatePp()).isEqualTo(40.0);
        assertThat(p.getAgentTargetMinDeltaPp()).isEqualTo(0.0);
        assertThat(p.getAgentRegressionFloorPp()).isEqualTo(-3.0);
        assertThat(p.getMinMeasuredN()).isEqualTo(10);
        assertThat(p.getAnchorErosionFloorPp()).isEqualTo(5.0);
        // EVOLVE-LOOP-HILLCLIMB 阶段 A defaults.
        assertThat(p.getWeightGeneral()).isEqualTo(0.6);
        assertThat(p.getWeightHarvest()).isEqualTo(0.4);
        assertThat(p.getMinImprovePp()).isEqualTo(0.0);
        assertThat(p.getNoImproveStreakLimit()).isEqualTo(3);
        assertThat(p.getTargetWeightedScore()).isNull();   // null = no target-stop
        // EVOLVE-JUDGE-GROUNDING Phase 1 comparative keep gate defaults.
        assertThat(p.getMinNetWins()).isEqualTo(2);
        assertThat(p.isPairwiseSignTest()).isFalse();
        assertThat(p.getPairwiseAlpha()).isEqualTo(0.05);
    }

    @Test
    @DisplayName("kebab-case yml keys bind via relaxed binding (skillforge.evolve.thresholds.*)")
    void kebabCaseBinding() {
        Map<String, Object> props = new java.util.LinkedHashMap<>();
        props.put("skillforge.evolve.thresholds.prompt-delta-pp", "7");
        props.put("skillforge.evolve.thresholds.skill-delta-pp", "6");
        props.put("skillforge.evolve.thresholds.skill-min-candidate-rate-pp", "45");
        props.put("skillforge.evolve.thresholds.agent-target-min-delta-pp", "1");
        props.put("skillforge.evolve.thresholds.agent-regression-floor-pp", "-2");
        props.put("skillforge.evolve.thresholds.min-measured-n", "12");
        props.put("skillforge.evolve.thresholds.anchor-erosion-floor-pp", "4");
        props.put("skillforge.evolve.thresholds.weight-general", "0.7");
        props.put("skillforge.evolve.thresholds.weight-harvest", "0.3");
        props.put("skillforge.evolve.thresholds.min-improve-pp", "1");
        props.put("skillforge.evolve.thresholds.no-improve-streak-limit", "4");
        props.put("skillforge.evolve.thresholds.target-weighted-score", "88");
        props.put("skillforge.evolve.thresholds.min-net-wins", "3");
        props.put("skillforge.evolve.thresholds.pairwise-sign-test", "true");
        props.put("skillforge.evolve.thresholds.pairwise-alpha", "0.1");
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(props);

        EvolveThresholdProperties p = new Binder(source)
                .bind("skillforge.evolve.thresholds", EvolveThresholdProperties.class)
                .get();

        assertThat(p.getPromptDeltaPp()).isEqualTo(7.0);
        assertThat(p.getSkillDeltaPp()).isEqualTo(6.0);
        assertThat(p.getSkillMinCandidateRatePp()).isEqualTo(45.0);
        assertThat(p.getAgentTargetMinDeltaPp()).isEqualTo(1.0);
        assertThat(p.getAgentRegressionFloorPp()).isEqualTo(-2.0);
        assertThat(p.getMinMeasuredN()).isEqualTo(12);
        assertThat(p.getAnchorErosionFloorPp()).isEqualTo(4.0);
        assertThat(p.getWeightGeneral()).isEqualTo(0.7);
        assertThat(p.getWeightHarvest()).isEqualTo(0.3);
        assertThat(p.getMinImprovePp()).isEqualTo(1.0);
        assertThat(p.getNoImproveStreakLimit()).isEqualTo(4);
        assertThat(p.getTargetWeightedScore()).isEqualTo(88.0);
        assertThat(p.getMinNetWins()).isEqualTo(3);
        assertThat(p.isPairwiseSignTest()).isTrue();
        assertThat(p.getPairwiseAlpha()).isEqualTo(0.1);
    }

    // ── LOW-2 (review r1): @Validated fail-fast on illegal values ──

    @Configuration
    @EnableConfigurationProperties(EvolveThresholdProperties.class)
    static class PropsConfig {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropsConfig.class);

    @Test
    @DisplayName("LOW-2: negative min-measured-n would silently disable the F3 guard → startup fails fast")
    void negativeMinMeasuredN_failsStartup() {
        runner.withPropertyValues("skillforge.evolve.thresholds.min-measured-n=-1")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .hasMessageContaining("skillforge.evolve.thresholds");
                });
    }

    @Test
    @DisplayName("LOW-2: negative prompt-delta-pp (auto-promote on decline) → startup fails fast")
    void negativePromptDeltaPp_failsStartup() {
        runner.withPropertyValues("skillforge.evolve.thresholds.prompt-delta-pp=-5")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    @DisplayName("LOW-2: skill-min-candidate-rate-pp above 100 (not a rate) → startup fails fast")
    void outOfRangeSkillMinCandidateRate_failsStartup() {
        runner.withPropertyValues("skillforge.evolve.thresholds.skill-min-candidate-rate-pp=101")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    @DisplayName("LOW-2 control: defaults + legitimate negative agent-regression-floor-pp bind fine")
    void validValues_startupSucceeds() {
        runner.withPropertyValues("skillforge.evolve.thresholds.agent-regression-floor-pp=-3")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(EvolveThresholdProperties.class).getMinMeasuredN())
                            .isEqualTo(10);
                });
    }

    // ── EVOLVE-LOOP-HILLCLIMB 阶段 A: @Validated fail-fast on illegal hill-climb values ──

    @Test
    @DisplayName("HILLCLIMB: weight-general above 1.0 (not a [0,1] weight) → startup fails fast")
    void weightGeneralOutOfRange_failsStartup() {
        runner.withPropertyValues("skillforge.evolve.thresholds.weight-general=1.5")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .hasMessageContaining("skillforge.evolve.thresholds");
                });
    }

    @Test
    @DisplayName("HILLCLIMB: no-improve-streak-limit=0 (would converge-stop instantly) → startup fails fast")
    void zeroStreakLimit_failsStartup() {
        runner.withPropertyValues("skillforge.evolve.thresholds.no-improve-streak-limit=0")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    @DisplayName("HILLCLIMB: min-improve-pp negative (would keep on a decline) → startup fails fast")
    void negativeMinImprovePp_failsStartup() {
        runner.withPropertyValues("skillforge.evolve.thresholds.min-improve-pp=-1")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    @DisplayName("HILLCLIMB: target-weighted-score above 100 (not a rate) → startup fails fast")
    void targetWeightedScoreOutOfRange_failsStartup() {
        runner.withPropertyValues("skillforge.evolve.thresholds.target-weighted-score=150")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    @DisplayName("HILLCLIMB control: target-weighted-score absent → null (no target-stop), startup ok")
    void targetWeightedScoreAbsent_isNull() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(EvolveThresholdProperties.class).getTargetWeightedScore())
                    .isNull();
        });
    }

    // ── EVOLVE-JUDGE-GROUNDING Phase 1: @Validated fail-fast on illegal comparative-gate values ──

    @Test
    @DisplayName("JUDGE-GROUNDING: negative min-net-wins (would keep on a net loss) → startup fails fast")
    void negativeMinNetWins_failsStartup() {
        runner.withPropertyValues("skillforge.evolve.thresholds.min-net-wins=-1")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .hasMessageContaining("skillforge.evolve.thresholds");
                });
    }

    @Test
    @DisplayName("JUDGE-GROUNDING: pairwise-alpha=0 (degenerate sign test) → startup fails fast")
    void zeroPairwiseAlpha_failsStartup() {
        runner.withPropertyValues("skillforge.evolve.thresholds.pairwise-alpha=0")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    @DisplayName("JUDGE-GROUNDING: pairwise-alpha=1 (always-significant degenerate) → startup fails fast")
    void onePairwiseAlpha_failsStartup() {
        runner.withPropertyValues("skillforge.evolve.thresholds.pairwise-alpha=1")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    @DisplayName("JUDGE-GROUNDING control: min-net-wins=0 + sign test on + valid alpha bind fine")
    void validComparativeGate_startupSucceeds() {
        runner.withPropertyValues(
                        "skillforge.evolve.thresholds.min-net-wins=0",
                        "skillforge.evolve.thresholds.pairwise-sign-test=true",
                        "skillforge.evolve.thresholds.pairwise-alpha=0.01")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    EvolveThresholdProperties p = ctx.getBean(EvolveThresholdProperties.class);
                    assertThat(p.getMinNetWins()).isEqualTo(0);
                    assertThat(p.isPairwiseSignTest()).isTrue();
                    assertThat(p.getPairwiseAlpha()).isEqualTo(0.01);
                });
    }
}
