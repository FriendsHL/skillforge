package com.skillforge.server.skill;

import com.skillforge.core.model.SkillDefinition;
import com.skillforge.core.skill.SkillPackageLoader;
import com.skillforge.server.entity.SkillDraftEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan r2 §4 — SkillCreatorService renders draft metadata into a SKILL.md that
 * SkillPackageLoader can subsequently parse without errors.
 */
class SkillCreatorServiceTest {

    @Test
    @DisplayName("renders SKILL.md that SkillPackageLoader.loadFromDirectory can parse")
    void render_producesParseableSkillMd(@TempDir Path tmp) throws IOException {
        SkillCreatorService creator = new SkillCreatorService();
        SkillDraftEntity draft = new SkillDraftEntity();
        draft.setId("draft-1");
        draft.setName("ExtractKpiPattern");
        draft.setDescription("Locate KPI definitions in a repo and summarise them.");
        draft.setRequiredTools("Grep, FileRead");
        draft.setTriggers("find KPIs, kpi extraction");
        draft.setPromptHint("1. Use Grep to find KPI mentions. 2. Read each match. 3. Summarize.");
        draft.setOwnerId(42L);

        creator.render(draft, tmp);

        Path skillMd = tmp.resolve("SKILL.md");
        assertThat(Files.exists(skillMd)).isTrue();
        String body = Files.readString(skillMd, StandardCharsets.UTF_8);
        assertThat(body).startsWith("---\n");
        assertThat(body).contains("name: ExtractKpiPattern");
        assertThat(body).contains("description:");
        assertThat(body).contains("allowed-tools: [Grep, FileRead]");

        SkillPackageLoader loader = new SkillPackageLoader();
        SkillDefinition def = loader.loadFromDirectory(tmp);
        assertThat(def.getName()).isEqualTo("ExtractKpiPattern");
        assertThat(def.getPromptContent()).isNotBlank();
    }

    @Test
    @DisplayName("description containing colon is YAML-quoted (does not break frontmatter parse)")
    void render_quotesYamlSpecial(@TempDir Path tmp) throws IOException {
        SkillCreatorService creator = new SkillCreatorService();
        SkillDraftEntity draft = new SkillDraftEntity();
        draft.setId("draft-2");
        draft.setName("Tricky");
        draft.setDescription("Run: echo hello");   // colon is YAML special
        draft.setPromptHint("Body.");

        creator.render(draft, tmp);

        SkillPackageLoader loader = new SkillPackageLoader();
        SkillDefinition def = loader.loadFromDirectory(tmp);
        assertThat(def.getDescription()).isEqualTo("Run: echo hello");
    }

    @Test
    @DisplayName("missing promptHint yields a placeholder body (still parseable)")
    void render_missingPromptHintFallback(@TempDir Path tmp) throws IOException {
        SkillCreatorService creator = new SkillCreatorService();
        SkillDraftEntity draft = new SkillDraftEntity();
        draft.setId("draft-3");
        draft.setName("Empty");
        draft.setDescription("desc");
        // promptHint left null

        creator.render(draft, tmp);

        SkillPackageLoader loader = new SkillPackageLoader();
        SkillDefinition def = loader.loadFromDirectory(tmp);
        assertThat(def.getPromptContent()).contains("not provided");
    }
}
