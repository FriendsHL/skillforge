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
    }

    @Test
    @DisplayName("kebab-case yml keys bind via relaxed binding (skillforge.evolve.thresholds.*)")
    void kebabCaseBinding() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "skillforge.evolve.thresholds.prompt-delta-pp", "7",
                "skillforge.evolve.thresholds.skill-delta-pp", "6",
                "skillforge.evolve.thresholds.skill-min-candidate-rate-pp", "45",
                "skillforge.evolve.thresholds.agent-target-min-delta-pp", "1",
                "skillforge.evolve.thresholds.agent-regression-floor-pp", "-2",
                "skillforge.evolve.thresholds.min-measured-n", "12",
                "skillforge.evolve.thresholds.anchor-erosion-floor-pp", "4"));

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
}
