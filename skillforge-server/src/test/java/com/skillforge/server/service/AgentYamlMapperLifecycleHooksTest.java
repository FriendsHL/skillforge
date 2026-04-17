package com.skillforge.server.service;

import com.skillforge.server.entity.AgentEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trip tests specifically targeting {@code lifecycleHooks} preservation through
 * {@link AgentYamlMapper}.
 */
class AgentYamlMapperLifecycleHooksTest {

    @Test
    @DisplayName("lifecycleHooks JSON round-trips through YAML as nested map")
    void lifecycleHooks_jsonRoundTrip() {
        AgentEntity a = new AgentEntity();
        a.setName("HookedAgent");
        a.setSkillIds("[]");
        a.setLifecycleHooks("{\"version\":1,\"hooks\":{\"UserPromptSubmit\":[{" +
                "\"handler\":{\"type\":\"skill\",\"skillName\":\"ContentFilter\"}," +
                "\"timeoutSeconds\":5,\"failurePolicy\":\"ABORT\",\"async\":false}]}}");

        String yaml = AgentYamlMapper.toYaml(a);
        assertThat(yaml).contains("lifecycleHooks");
        assertThat(yaml).contains("ContentFilter");

        AgentEntity back = AgentYamlMapper.fromYaml(yaml);
        assertThat(back.getLifecycleHooks()).isNotNull();
        assertThat(back.getLifecycleHooks()).contains("ContentFilter");
        assertThat(back.getLifecycleHooks()).contains("UserPromptSubmit");
    }

    @Test
    @DisplayName("Corrupt lifecycleHooks JSON is exported as raw and re-imported verbatim")
    void lifecycleHooks_corruptJsonPreserved() {
        AgentEntity a = new AgentEntity();
        a.setName("HookedAgent");
        a.setSkillIds("[]");
        a.setLifecycleHooks("definitely not json");

        String yaml = AgentYamlMapper.toYaml(a);
        assertThat(yaml).contains("lifecycleHooksRaw");

        AgentEntity back = AgentYamlMapper.fromYaml(yaml);
        assertThat(back.getLifecycleHooks()).isEqualTo("definitely not json");
    }

    @Test
    @DisplayName("Null lifecycleHooks produces no YAML key and round-trips to null")
    void lifecycleHooks_nullIsOmitted() {
        AgentEntity a = new AgentEntity();
        a.setName("A");
        a.setSkillIds("[]");
        // lifecycleHooks left null

        String yaml = AgentYamlMapper.toYaml(a);
        assertThat(yaml).doesNotContain("lifecycleHooks");

        AgentEntity back = AgentYamlMapper.fromYaml(yaml);
        assertThat(back.getLifecycleHooks()).isNull();
    }
}
