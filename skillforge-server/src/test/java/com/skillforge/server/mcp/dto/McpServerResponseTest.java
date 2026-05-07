package com.skillforge.server.mcp.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.server.mcp.entity.McpServerEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P11 r1 W3 (security): API responses must never echo literal env values back —
 * only intact ${VAR} placeholders pass through.
 */
class McpServerResponseTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    @DisplayName("maskEnvValue: literal value → '***'")
    void maskEnvValue_literalMasked() {
        assertThat(McpServerResponse.maskEnvValue("ghp_real_secret_value")).isEqualTo("***");
        assertThat(McpServerResponse.maskEnvValue("sk-abc123")).isEqualTo("***");
        assertThat(McpServerResponse.maskEnvValue("plain")).isEqualTo("***");
    }

    @Test
    @DisplayName("maskEnvValue: intact ${VAR} placeholder → passed through verbatim")
    void maskEnvValue_placeholderPassesThrough() {
        assertThat(McpServerResponse.maskEnvValue("${GITHUB_TOKEN}")).isEqualTo("${GITHUB_TOKEN}");
        assertThat(McpServerResponse.maskEnvValue("${MY_URL}")).isEqualTo("${MY_URL}");
        assertThat(McpServerResponse.maskEnvValue("${A_B_C_123}")).isEqualTo("${A_B_C_123}");
    }

    @Test
    @DisplayName("maskEnvValue: mixed strings ('before-${X}-after') → masked (not pure placeholder)")
    void maskEnvValue_mixedMasked() {
        assertThat(McpServerResponse.maskEnvValue("before-${VAR}-after")).isEqualTo("***");
        assertThat(McpServerResponse.maskEnvValue("${VAR}suffix")).isEqualTo("***");
        assertThat(McpServerResponse.maskEnvValue("prefix${VAR}")).isEqualTo("***");
        assertThat(McpServerResponse.maskEnvValue("${A}${B}")).isEqualTo("***");
    }

    @Test
    @DisplayName("maskEnvValue: empty / blank / weird → masked (defensive)")
    void maskEnvValue_emptyMasked() {
        // We don't pass through empty strings; admin shouldn't have stored them but if
        // they did we still don't echo them (defensive — no info leak via length signal).
        assertThat(McpServerResponse.maskEnvValue("")).isEqualTo("***");
        assertThat(McpServerResponse.maskEnvValue("${}")).isEqualTo("***");           // empty var name
        assertThat(McpServerResponse.maskEnvValue("${1NUMERIC}")).isEqualTo("***");   // bad var name
        assertThat(McpServerResponse.maskEnvValue("${var-with-dash}")).isEqualTo("***");
    }

    @Test
    @DisplayName("maskEnvValue: null in → null out (preserved for response shape)")
    void maskEnvValue_nullPreserved() {
        assertThat(McpServerResponse.maskEnvValue(null)).isNull();
    }

    @Test
    @DisplayName("maskEnv: every value masked, keys preserved in insertion order")
    void maskEnv_perEntry() {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("GITHUB_TOKEN", "ghp_real_secret");
        raw.put("URL", "${MY_URL}");
        raw.put("DEBUG", "true");
        raw.put("PASSTHROUGH", "${SOMETHING}");
        Map<String, String> masked = McpServerResponse.maskEnv(raw);
        assertThat(masked).containsExactly(
                Map.entry("GITHUB_TOKEN", "***"),
                Map.entry("URL", "${MY_URL}"),
                Map.entry("DEBUG", "***"),
                Map.entry("PASSTHROUGH", "${SOMETHING}")
        );
    }

    @Test
    @DisplayName("maskEnv: null / empty → empty map")
    void maskEnv_nullOrEmpty() {
        assertThat(McpServerResponse.maskEnv(null)).isEmpty();
        assertThat(McpServerResponse.maskEnv(Collections.emptyMap())).isEmpty();
    }

    @Test
    @DisplayName("from(entity): full response masks env literal values, leaves placeholders intact")
    void from_endToEndMasking() {
        McpServerEntity entity = new McpServerEntity();
        entity.setId(1L);
        entity.setName("github");
        entity.setCommand("npx");
        entity.setArgs("[\"-y\",\"github-mcp\"]");
        entity.setEnv("{\"TOKEN\":\"ghp_real_secret\",\"BASE_URL\":\"${GITHUB_BASE_URL}\"}");
        entity.setEnabled(true);
        McpServerResponse r = McpServerResponse.from(entity, mapper);
        assertThat(r.getEnv()).containsEntry("TOKEN", "***");
        assertThat(r.getEnv()).containsEntry("BASE_URL", "${GITHUB_BASE_URL}");
        // args still parsed normally — only env is sensitive
        assertThat(r.getArgs()).containsExactly("-y", "github-mcp");
    }

    @Test
    @DisplayName("Round-trip safety: response env never contains the original literal value")
    void roundTripNeverLeaksLiteral() {
        // Belt-and-suspenders shield — test fixture for the W3 invariant. If a future
        // refactor accidentally stops masking, this fails immediately.
        McpServerEntity entity = new McpServerEntity();
        entity.setId(1L);
        entity.setName("x");
        entity.setCommand("npx");
        entity.setArgs("[]");
        String secret = "this_must_never_appear_in_response_42";
        entity.setEnv("{\"K\":\"" + secret + "\"}");
        entity.setEnabled(true);
        McpServerResponse r = McpServerResponse.from(entity, mapper);
        assertThat(r.getEnv().get("K")).doesNotContain(secret);
        assertThat(r.getEnv().get("K")).isEqualTo("***");
    }
}
