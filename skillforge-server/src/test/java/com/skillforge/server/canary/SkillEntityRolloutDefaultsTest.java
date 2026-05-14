package com.skillforge.server.canary;

import com.skillforge.server.entity.SkillEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SKILL-CANARY-ROLLOUT V2 Phase 1.1: regression test for the V78 column
 * additions to {@link SkillEntity}.
 *
 * <p>The V78 migration adds {@code rollout_stage} and {@code rollout_percentage}
 * with backward-compatible defaults ({@code production} / {@code 100}), so the
 * existing {@code SkillAbEvalService.promoteCandidate} pipeline (which never
 * sets these fields explicitly) continues to produce skill rows that
 * effectively bypass the canary allocator. This test pins that contract:
 * <ul>
 *   <li>A freshly-constructed {@link SkillEntity} (mirroring what
 *       {@code SkillAbEvalService.createAndTrigger} produces when staging a
 *       candidate skill) has {@code rolloutStage='production'} and
 *       {@code rolloutPercentage=100} without any explicit setter call.</li>
 *   <li>The setters override these defaults, used by the Phase 1.2+
 *       {@code CanaryRolloutService.startCanary} flow.</li>
 * </ul>
 *
 * <p>Why a pure-POJO unit test instead of an IT: the JPA default values are
 * a property of the entity field initializer (Java side) and the DDL DEFAULT
 * (DB side). The DDL default is already exercised by {@link CanaryPersistenceIT}
 * via the same Flyway migrations; this test pins the Java initializer
 * side so callers that {@code new SkillEntity()} (not via DB) still see the
 * back-compat defaults.
 */
@DisplayName("SkillEntity V78 rollout-column defaults")
class SkillEntityRolloutDefaultsTest {

    @Test
    @DisplayName("freshly-constructed SkillEntity defaults to rolloutStage=production / rolloutPercentage=100 (preserves existing promote pipeline)")
    void newSkillEntity_defaultsToProductionAndFullRollout() {
        SkillEntity skill = new SkillEntity();
        assertThat(skill.getRolloutStage())
                .as("V78 default rolloutStage — protects SkillAbEvalService.promoteCandidate from regressions")
                .isEqualTo("production");
        assertThat(skill.getRolloutPercentage())
                .as("V78 default rolloutPercentage — 100 = '一刀切' backward-compatible semantics")
                .isEqualTo(100);
    }

    @Test
    @DisplayName("setters override the rollout defaults for canary opt-in")
    void rolloutSetters_override_defaults() {
        SkillEntity skill = new SkillEntity();
        skill.setRolloutStage("canary");
        skill.setRolloutPercentage(10);

        assertThat(skill.getRolloutStage()).isEqualTo("canary");
        assertThat(skill.getRolloutPercentage()).isEqualTo(10);
    }
}
