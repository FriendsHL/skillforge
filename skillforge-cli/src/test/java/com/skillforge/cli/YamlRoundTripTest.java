package com.skillforge.cli;

import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class YamlRoundTripTest {

    @Test
    void generalAssistantRoundTrips() throws Exception {
        assertRoundTrip("/examples/general-assistant.yaml");
    }

    @Test
    void calculatorRoundTrips() throws Exception {
        assertRoundTrip("/examples/calculator.yaml");
    }

    /**
     * Special-chars fixture: literal block scalars with colons, quotes,
     * backslashes and trailing text. If Jackson ever silently switches
     * to folded-scalar or single-quoted emission this test breaks.
     * Asserts byte-equivalence of the systemPrompt across a parse →
     * re-serialize → parse cycle.
     */
    @Test
    void specialCharsRoundTripPreservesSystemPrompt() throws Exception {
        Map<String, Object> first = readMap("/examples/with-special-chars.yaml");
        String originalPrompt = (String) first.get("systemPrompt");
        assertThat(originalPrompt).isNotNull();
        assertThat(originalPrompt).contains(": \" ` \\ /");
        assertThat(originalPrompt).contains("Multiple lines");

        String reSerialized = YamlMapper.yaml().writeValueAsString(first);
        Map<String, Object> second = YamlMapper.yaml().readValue(reSerialized, new TypeReference<Map<String, Object>>() {});

        String secondPrompt = (String) second.get("systemPrompt");
        assertThat(secondPrompt).isEqualTo(originalPrompt);
        assertThat(second.get("name")).isEqualTo(first.get("name"));
        assertThat(second.get("modelId")).isEqualTo(first.get("modelId"));
        assertThat(second.get("skills")).isEqualTo(first.get("skills"));
    }

    private void assertRoundTrip(String resource) throws Exception {
        Map<String, Object> first = readMap(resource);
        String reSerialized = YamlMapper.yaml().writeValueAsString(first);
        Map<String, Object> second = YamlMapper.yaml().readValue(reSerialized, new TypeReference<Map<String, Object>>() {});

        assertThat(second.get("name")).isEqualTo(first.get("name"));
        assertThat(second.get("modelId")).isEqualTo(first.get("modelId"));
        assertThat(second.get("executionMode")).isEqualTo(first.get("executionMode"));
        assertThat(second.get("public")).isEqualTo(first.get("public"));
        assertThat(second.get("systemPrompt")).isEqualTo(first.get("systemPrompt"));
        assertThat(second.get("description")).isEqualTo(first.get("description"));
        @SuppressWarnings("unchecked")
        List<String> skillsA = (List<String>) first.get("skills");
        @SuppressWarnings("unchecked")
        List<String> skillsB = (List<String>) second.get("skills");
        assertThat(skillsB).isEqualTo(skillsA);
    }

    private Map<String, Object> readMap(String resource) throws Exception {
        try (InputStream in = YamlRoundTripTest.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return YamlMapper.yaml().readValue(text, new TypeReference<Map<String, Object>>() {});
        }
    }
}
