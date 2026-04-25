package com.skillforge.server.service;

import com.skillforge.server.entity.AgentEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * YAML round-trip for {@code thinkingMode} / {@code reasoningEffort} fields.
 *
 * <p>Per plan D11 + reviewer W8: {@code thinkingMode} is only emitted when explicitly
 * {@code enabled}/{@code disabled}; {@code auto} and {@code null} stay off the wire so
 * a round-trip against legacy YAML stays idempotent.</p>
 */
class AgentYamlMapperThinkingTest {

    @Test
    @DisplayName("fromYaml without thinkingMode key → entity field null")
    void fromYaml_absentField_nullEntity() {
        String yaml = """
                name: test-agent
                modelId: bailian:qwen3.5-plus
                """;
        AgentEntity e = AgentYamlMapper.fromYaml(yaml);
        assertThat(e.getThinkingMode()).isNull();
        assertThat(e.getReasoningEffort()).isNull();
    }

    @Test
    @DisplayName("fromYaml thinkingMode=enabled → entity field 'enabled'")
    void fromYaml_enabled_setsField() {
        String yaml = """
                name: test-agent
                thinkingMode: enabled
                reasoningEffort: high
                """;
        AgentEntity e = AgentYamlMapper.fromYaml(yaml);
        assertThat(e.getThinkingMode()).isEqualTo("enabled");
        assertThat(e.getReasoningEffort()).isEqualTo("high");
    }

    @Test
    @DisplayName("toYaml null thinkingMode omits key")
    void toYaml_null_omitsKey() {
        AgentEntity e = new AgentEntity();
        e.setName("a");
        String yaml = AgentYamlMapper.toYaml(e);
        assertThat(yaml).doesNotContain("thinkingMode");
        assertThat(yaml).doesNotContain("reasoningEffort");
    }

    @Test
    @DisplayName("toYaml 'auto' thinkingMode omits key (plan D11 / reviewer W8)")
    void toYaml_autoString_omitsKey() {
        AgentEntity e = new AgentEntity();
        e.setName("a");
        e.setThinkingMode("auto");
        String yaml = AgentYamlMapper.toYaml(e);
        assertThat(yaml).doesNotContain("thinkingMode");
    }

    @Test
    @DisplayName("toYaml 'enabled' thinkingMode writes field")
    void toYaml_enabled_writesField() {
        AgentEntity e = new AgentEntity();
        e.setName("a");
        e.setThinkingMode("enabled");
        e.setReasoningEffort("medium");
        String yaml = AgentYamlMapper.toYaml(e);
        assertThat(yaml).contains("thinkingMode: enabled");
        assertThat(yaml).contains("reasoningEffort: medium");
    }

    @Test
    @DisplayName("round-trip disabled/low preserves fields")
    void roundTrip_disabledLow_preserves() {
        AgentEntity src = new AgentEntity();
        src.setName("round-trip");
        src.setThinkingMode("disabled");
        src.setReasoningEffort("low");

        String yaml = AgentYamlMapper.toYaml(src);
        AgentEntity parsed = AgentYamlMapper.fromYaml(yaml);

        assertThat(parsed.getThinkingMode()).isEqualTo("disabled");
        assertThat(parsed.getReasoningEffort()).isEqualTo("low");
    }
}
