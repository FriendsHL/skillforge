package com.skillforge.server.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 2026-05-26 — pins the {@link SkillEntity#validateForkRowInvariantsOnInsert()}
 * guard. Background: prior to the guard, a buggy historic path persisted
 * {@code id=452} as {@code source='evolution-fork'} with
 * {@code parent_skill_id=null}. After the V118 semver backfill, that row
 * showed up as a duplicate v1 of {@code FrontendFeatureImplementation} in
 * the dashboard. The row was deleted manually; this guard prevents the
 * pattern from re-emerging.
 *
 * <p>Pure POJO test — the {@code @PrePersist} method is package-private so
 * we can invoke it directly without spinning up an EntityManager.
 */
@DisplayName("SkillEntity fork-row invariants (@PrePersist)")
class SkillEntityForkInvariantsTest {

    @Test
    @DisplayName("insert rejected when source='evolution-fork' (id=452 bug signature)")
    void reject_sourceEqualsEvolutionForkLiteral() {
        SkillEntity bad = new SkillEntity();
        bad.setName("FrontendFeatureImplementation");
        bad.setSource("evolution-fork");
        bad.setSkillPath("/data/skills/evolution-fork/1/442/somehash");
        bad.setParentSkillId(442L);
        bad.setSemver("v1");

        assertThatThrownBy(bad::validateForkRowInvariantsOnInsert)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("source='evolution-fork' is a path-allocation hint");
    }

    @Test
    @DisplayName("insert rejected when skill_path under /evolution-fork/ but parent_skill_id is null")
    void reject_forkPathWithoutParent() {
        SkillEntity bad = new SkillEntity();
        bad.setName("SomeFork");
        bad.setSource("draft-approve");
        bad.setSkillPath("/data/skills/evolution-fork/1/100/forkuuid");
        bad.setParentSkillId(null);
        bad.setSemver("v2");

        assertThatThrownBy(bad::validateForkRowInvariantsOnInsert)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("parent_skill_id is null");
    }

    @Test
    @DisplayName("legitimate fork row passes (source inherited + parent set)")
    void allow_validForkRow() {
        SkillEntity good = new SkillEntity();
        good.setName("FrontendFeatureImplementation");
        good.setSource("draft-approve");
        good.setSkillPath("/data/skills/evolution-fork/1/442/443");
        good.setParentSkillId(442L);
        good.setSemver("v2");

        assertThatCode(good::validateForkRowInvariantsOnInsert)
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("non-fork row passes (path not under evolution-fork)")
    void allow_normalImportRow() {
        SkillEntity good = new SkillEntity();
        good.setName("zhihu-search");
        good.setSource("zhihu-search");
        good.setSkillPath("/data/skills/zhihu-search");
        good.setParentSkillId(null);
        good.setSemver(null); // import default 'v1' applied by SQL literal, not by entity

        assertThatCode(good::validateForkRowInvariantsOnInsert)
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("legitimate fork with null skillPath passes (forkSkill fallback path)")
    void allow_forkRowWithNullPath() {
        // SkillService.forkSkill has a fallback when storage allocation fails:
        // child.setSkillPath(parent.getSkillPath()) which may be null for
        // very-old skills. The guard only fires when path is non-null AND
        // under /evolution-fork/.
        SkillEntity good = new SkillEntity();
        good.setName("SomeSkill");
        good.setSource("clawhub");
        good.setSkillPath(null);
        good.setParentSkillId(123L);
        good.setSemver("v2");

        assertThatCode(good::validateForkRowInvariantsOnInsert)
                .doesNotThrowAnyException();
    }
}
