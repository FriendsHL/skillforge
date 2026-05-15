package com.skillforge.server.improve.surface;

import com.skillforge.server.entity.BehaviorRuleVersionEntity;
import com.skillforge.server.improve.BehaviorRuleImproverService;
import com.skillforge.server.improve.BehaviorRulePromotionService;
import com.skillforge.server.repository.BehaviorRuleVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MULTI-SURFACE-FLYWHEEL V4 Phase 1.3 — unit test for
 * {@link BehaviorRuleSurface#injectForSandbox} + the session-scoped registry
 * it populates. Parallel to {@code SkillSurfaceInjectTest} /
 * {@code PromptSurfaceInjectTest}.
 *
 * <p>BehaviorRuleSurface is a 246-line **full new implementation** (vs the
 * thin adapter pattern of SkillSurface / PromptSurface), so the inject-side
 * cache surface needs unit-level coverage independent of the inherited /
 * Phase 1.2 r1 inject contract. Three cases match the locked Skill/Prompt
 * contract:
 * <ol>
 *   <li>inject stashes a version per sessionId; cross-session reads return
 *       null (isolation between sandbox sessions)</li>
 *   <li>inject with {@code version=null} removes the entry (sandbox tear-down)</li>
 *   <li>blank / null sessionId throws (defensive — same as Skill / Prompt)</li>
 * </ol>
 *
 * <p>Note: this is **inject-side cache** unit test, deliberately separate from
 * {@code loadActive}'s 5-min TTL **active-side cache** (covered indirectly by
 * Phase 1.1 {@code BehaviorRuleImproverServiceTest}). Mixing the two would
 * couple two orthogonal concerns and make a regression on either side noisy.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BehaviorRuleSurface.injectForSandbox")
class BehaviorRuleSurfaceInjectTest {

    @Mock private BehaviorRuleVersionRepository versionRepository;
    @Mock private BehaviorRuleImproverService improverService;
    @Mock private BehaviorRulePromotionService promotionService;

    private BehaviorRuleSurface surface;

    @BeforeEach
    void setUp() {
        surface = new BehaviorRuleSurface(versionRepository, improverService, promotionService);
    }

    private BehaviorRuleVersionEntity version(String id, int n) {
        BehaviorRuleVersionEntity v = new BehaviorRuleVersionEntity();
        v.setId(id);
        v.setAgentId("7");
        v.setVersionNumber(n);
        v.setStatus(BehaviorRuleVersionEntity.STATUS_CANDIDATE);
        v.setRulesJson("[]");
        return v;
    }

    @Test
    @DisplayName("inject stashes version under sessionId; getInjectedVersion retrieves it; "
            + "different sessionIds isolated; unknown / null sessionId returns null")
    void inject_stashesAndRetrieves_perSessionIsolation() {
        BehaviorRuleVersionEntity v1 = version("br-v1", 1);
        BehaviorRuleVersionEntity v2 = version("br-v2", 2);
        SandboxContext ctxA = new SandboxContext(7L, "session-a", null);
        SandboxContext ctxB = new SandboxContext(7L, "session-b", null);

        surface.injectForSandbox(ctxA, v1);
        surface.injectForSandbox(ctxB, v2);

        // Different sessions on the same agent stash to different slots.
        assertThat(surface.getInjectedVersion("session-a")).isSameAs(v1);
        assertThat(surface.getInjectedVersion("session-b")).isSameAs(v2);
        // Unknown session returns null (no entry).
        assertThat(surface.getInjectedVersion("session-c")).isNull();
        // null sessionId returns null without NPE.
        assertThat(surface.getInjectedVersion(null)).isNull();
    }

    @Test
    @DisplayName("inject with version=null removes the existing entry (sandbox tear-down)")
    void inject_nullVersion_removes() {
        BehaviorRuleVersionEntity v1 = version("br-v1", 1);
        SandboxContext ctx = new SandboxContext(7L, "session-a", null);

        surface.injectForSandbox(ctx, v1);
        assertThat(surface.getInjectedVersion("session-a")).isSameAs(v1);

        // Tear-down: passing null removes the entry, getInjectedVersion → null.
        surface.injectForSandbox(ctx, null);
        assertThat(surface.getInjectedVersion("session-a")).isNull();
    }

    @Test
    @DisplayName("inject with blank / null sessionId / null ctx throws (defensive — same as Skill/Prompt)")
    void inject_blankSessionId_throws() {
        BehaviorRuleVersionEntity v = version("br-v1", 1);
        assertThatThrownBy(() ->
                surface.injectForSandbox(new SandboxContext(7L, "", null), v))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sessionId");
        assertThatThrownBy(() ->
                surface.injectForSandbox(new SandboxContext(7L, null, null), v))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sessionId");
        assertThatThrownBy(() -> surface.injectForSandbox(null, v))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
