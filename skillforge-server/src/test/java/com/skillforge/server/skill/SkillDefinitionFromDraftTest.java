package com.skillforge.server.skill;

import com.skillforge.core.model.SkillDefinition;
import com.skillforge.server.entity.SkillDraftEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AUTOEVOLVE-AGENT-LEVEL-BUNDLE Phase 4 (§10 #2) — unit tests for the shared
 * {@link SkillDefinitionFromDraft} builder. The CRITICAL invariant
 * (allowedTools == requiredTools, else the agent loop's tool-gate rejects the
 * skill's tool calls) is pinned here.
 */
@DisplayName("SkillDefinitionFromDraft")
class SkillDefinitionFromDraftTest {

    private final SkillDefinitionFromDraft builder = new SkillDefinitionFromDraft();

    private SkillDraftEntity draft() {
        SkillDraftEntity d = new SkillDraftEntity();
        d.setId("d-1");
        d.setName("MySkill");
        d.setDescription("does a thing");
        d.setTriggers("alpha, beta");
        d.setRequiredTools("Bash, Read");
        d.setPromptHint("Step 1. Step 2.");
        return d;
    }

    @Test
    @DisplayName("allowedTools mirrors requiredTools (tool-gate invariant)")
    void allowedToolsMirrorsRequiredTools() {
        SkillDefinition def = builder.build(draft());
        assertThat(def.getRequiredTools()).containsExactly("Bash", "Read");
        assertThat(def.getAllowedTools()).containsExactly("Bash", "Read");
    }

    @Test
    @DisplayName("name + id + triggers come from the draft; prompt body embeds the hint")
    void mapsCoreFields() {
        SkillDefinition def = builder.build(draft());
        assertThat(def.getId()).isEqualTo("d-1");
        assertThat(def.getName()).isEqualTo("MySkill");
        assertThat(def.getTriggers()).containsExactly("alpha", "beta");
        assertThat(def.getPromptContent())
                .contains("# MySkill")
                .contains("does a thing")
                .contains("Step 1. Step 2.")
                .contains("**Use when:** alpha, beta")
                .contains("**Required tools:** Bash, Read");
        // in-memory eval candidate: never a disk path
        assertThat(def.getSkillPath()).isNull();
    }

    @Test
    @DisplayName("blank triggers / tools → empty lists, no allowedTools (no tools to gate)")
    void blankToolsLeaveAllowedToolsNull() {
        SkillDraftEntity d = draft();
        d.setTriggers("");
        d.setRequiredTools("");
        SkillDefinition def = builder.build(d);
        assertThat(def.getRequiredTools()).isEmpty();
        assertThat(def.getAllowedTools()).isNull();
    }
}
