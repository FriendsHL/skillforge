package com.skillforge.server.service;

import com.skillforge.server.entity.AgentEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Targeted tests for {@link AgentYamlMapper}'s handling of malformed
 * {@code skillIds} TEXT-column values. The previous implementation silently
 * ate parse errors and exported {@code skills: []}, hiding data corruption.
 *
 * <p>Current contract:
 * <ul>
 *   <li>{@code null} or blank → {@code skills: []}, no warning</li>
 *   <li>{@code "[]"} → {@code skills: []}, no warning</li>
 *   <li>Valid JSON array → {@code skills:} list with each element</li>
 *   <li>Non-array JSON or invalid JSON → {@code skills: []} PLUS a
 *       {@code skillIdsRaw:} field carrying the raw value verbatim, AND a
 *       {@code WARN} log line. The {@code skillIdsRaw} field is honored on
 *       round-trip back through {@code fromYaml} so corrupt rows are not
 *       silently destroyed.</li>
 * </ul>
 */
class AgentYamlMapperMalformedTest {

    private AgentEntity newAgent(String skillIds) {
        AgentEntity a = new AgentEntity();
        a.setId(1L);
        a.setName("test");
        a.setModelId("m");
        a.setSkillIds(skillIds);
        return a;
    }

    @Test
    void toYaml_nullSkillIds_emitsEmptySkillsListNoRaw() {
        String yaml = AgentYamlMapper.toYaml(newAgent(null));
        assertThat(yaml).contains("skills: []");
        assertThat(yaml).doesNotContain("skillIdsRaw");
    }

    @Test
    void toYaml_blankSkillIds_emitsEmptySkillsListNoRaw() {
        String yaml = AgentYamlMapper.toYaml(newAgent(""));
        assertThat(yaml).contains("skills: []");
        assertThat(yaml).doesNotContain("skillIdsRaw");
    }

    @Test
    void toYaml_emptyJsonArray_emitsEmptySkillsListNoRaw() {
        String yaml = AgentYamlMapper.toYaml(newAgent("[]"));
        assertThat(yaml).contains("skills: []");
        assertThat(yaml).doesNotContain("skillIdsRaw");
    }

    @Test
    void toYaml_validJsonArray_extractsList() {
        String yaml = AgentYamlMapper.toYaml(newAgent("[\"Bash\",\"FileRead\"]"));
        assertThat(yaml).contains("skills:");
        assertThat(yaml).contains("- Bash");
        assertThat(yaml).contains("- FileRead");
        assertThat(yaml).doesNotContain("skillIdsRaw");
    }

    @Test
    void toYaml_garbageNonJsonString_preservesRawAndEmitsEmptyList() {
        String corrupt = "garbage,not,json";
        String yaml = AgentYamlMapper.toYaml(newAgent(corrupt));
        // Empty skills list (so consumers don't see invalid entries)
        assertThat(yaml).contains("skills: []");
        // BUT the raw value MUST be preserved so the user sees what's wrong
        // and a round-trip through fromYaml() doesn't lose data.
        assertThat(yaml).contains("skillIdsRaw");
        assertThat(yaml).contains(corrupt);
    }

    @Test
    void toYaml_nonArrayJson_preservesRaw() {
        // Valid JSON but not an array — e.g. an object got stored by mistake
        String corrupt = "{\"oops\":true}";
        String yaml = AgentYamlMapper.toYaml(newAgent(corrupt));
        assertThat(yaml).contains("skills: []");
        assertThat(yaml).contains("skillIdsRaw");
    }

    // ============ Round-trip preservation of corrupt data ============

    @Test
    void roundTrip_corruptSkillIds_preservedThroughFromYaml() {
        String corrupt = "garbage,not,json";
        // export
        String yaml = AgentYamlMapper.toYaml(newAgent(corrupt));
        assertThat(yaml).contains("skillIdsRaw");
        // re-import
        AgentEntity reimported = AgentYamlMapper.fromYaml(yaml);
        // The raw corrupt value must round-trip verbatim, NOT be replaced by "[]"
        assertThat(reimported.getSkillIds()).isEqualTo(corrupt);
    }
}
