package com.skillforge.server.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SKILL-CREATOR-WITH-EVAL Phase 1.1 (2026-05-18) — green smoke check for the
 * {@code SkillCreatorService.dispatchEvaluation} entry-point added by Phase 1.1.
 *
 * <p>Started life as a Phase 1.0 red anchor (NoSuchMethodException) so the
 * pipeline could see the missing API; once Phase 1.1 landed the method we
 * pivoted to verify the published signature didn't drift. Reflection-based
 * to keep the test compile-green regardless of which Phase the working tree
 * is currently at.
 *
 * <p>Expected signature (per Phase 1.1 implementation):
 * <pre>
 *   public List&lt;String&gt; dispatchEvaluation(String parentSessionId,
 *                                            String draftId,
 *                                            List&lt;String&gt; scenarioIds)
 * </pre>
 *
 * <p>Phase 1.1 design note: signature gained a {@code parentSessionId} arg
 * vs the original tech-design.md 2-arg stub because SubAgent dispatches
 * require a non-null parent (verified Phase 1.0 — {@code SubAgentRegistry
 * .registerRun} throws on null parent). The 4 entry-point hooks (Phase 1.2)
 * are responsible for resolving / creating an appropriate parent session.
 */
class SkillCreatorServiceEvaluateTest {

    @Test
    @DisplayName("dispatchEvaluation(String, String, List) exists with the expected return type")
    void dispatchEvaluationMethodExists() throws NoSuchMethodException {
        Method method = SkillCreatorService.class.getMethod(
                "dispatchEvaluation", String.class, String.class, List.class);

        assertThat(method.getReturnType())
                .as("dispatchEvaluation must return List<String> (runIds) per tech-design.md")
                .isEqualTo(List.class);
        assertThat(Modifier.isPublic(method.getModifiers()))
                .as("dispatchEvaluation must be public — entry-points need to call it")
                .isTrue();
    }

    @Test
    @DisplayName("SkillCreatorService legacy 0-arg render-only ctor still callable")
    void legacyConstructorPreservesRenderOnlyPath() throws NoSuchMethodException {
        // The 0-arg ctor is preserved so the V1 render-only unit test
        // (SkillCreatorServiceTest#render_producesParseableSkillMd) still
        // builds without depending on the new Phase 1.1 wiring. If we ever
        // remove it, that test will need a Spring-style fixture; document
        // the dependency here so refactors aren't surprised.
        SkillCreatorService.class.getDeclaredConstructor();
    }

    @Test
    @DisplayName("Phase 1.1 threshold + status constants exist as documented")
    void phase11ConstantsPresent() throws NoSuchFieldException, IllegalAccessException {
        // PASS_RATE_DELTA_THRESHOLD anchors the promote/reject gate;
        // STATUS_* constants are used by entry-points + listener +
        // dashboard. Reflection-asserted so a rename can't silently drift
        // them away from the FE / docs.
        assertThat(SkillCreatorService.class.getField("PASS_RATE_DELTA_THRESHOLD").get(null))
                .as("PASS_RATE_DELTA_THRESHOLD must be 0.05 (5pp) per spec D7")
                .isEqualTo(0.05);
        assertThat(SkillCreatorService.class.getField("STATUS_EVALUATED_PASSED").get(null))
                .isEqualTo("evaluated_passed");
        assertThat(SkillCreatorService.class.getField("STATUS_REJECTED").get(null))
                .isEqualTo("rejected");
    }
}
