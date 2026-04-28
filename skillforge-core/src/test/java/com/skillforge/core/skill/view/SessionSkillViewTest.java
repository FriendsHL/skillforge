package com.skillforge.core.skill.view;

import com.skillforge.core.model.SkillDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan r2 §5 — boundary tests for {@link SessionSkillView}.
 * Covers B-3: view 仅管 skill 包，不管内置 Tool。
 */
class SessionSkillViewTest {

    @Test
    @DisplayName("isAllowed returns false for built-in tool name (e.g. Bash)")
    void isAllowed_builtinTool_returnsFalse() {
        SessionSkillView view = new SessionSkillView(Map.of(), Set.of(), Set.of());
        assertThat(view.isAllowed("Bash")).isFalse();
        assertThat(view.isAllowed("FileRead")).isFalse();
    }

    @Test
    @DisplayName("isAllowed returns true only for explicitly authorized skill packages")
    void isAllowed_skillPackage_respectsAuthorization() {
        SkillDefinition foo = new SkillDefinition();
        foo.setName("user-skill-foo");
        Map<String, SkillDefinition> allowed = new LinkedHashMap<>();
        allowed.put("user-skill-foo", foo);

        SessionSkillView view = new SessionSkillView(allowed, Set.of(), Set.of("user-skill-foo"));

        assertThat(view.isAllowed("user-skill-foo")).isTrue();
        assertThat(view.isAllowed("user-skill-bar")).isFalse();
        assertThat(view.resolve("user-skill-foo")).isPresent();
        assertThat(view.resolve("user-skill-bar")).isEmpty();
    }

    @Test
    @DisplayName("EMPTY view rejects every name and reports empty all()")
    void emptyView_rejectsAll() {
        assertThat(SessionSkillView.EMPTY.isAllowed("anything")).isFalse();
        assertThat(SessionSkillView.EMPTY.all()).isEmpty();
        assertThat(SessionSkillView.EMPTY.resolve(null)).isEmpty();
    }

    @Test
    @DisplayName("constructor defensively copies allowedSkills — caller mutation does not leak")
    void constructor_isImmutable() {
        SkillDefinition def = new SkillDefinition();
        def.setName("alpha");
        Map<String, SkillDefinition> input = new LinkedHashMap<>();
        input.put("alpha", def);

        SessionSkillView view = new SessionSkillView(input, Set.of(), Set.of());

        // Mutate caller-side map; view must not change.
        SkillDefinition trojan = new SkillDefinition();
        trojan.setName("trojan");
        input.put("trojan", trojan);

        assertThat(view.isAllowed("trojan")).isFalse();
        assertThat(view.all()).hasSize(1);
    }
}
