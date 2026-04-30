package com.skillforge.core.llm;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CTX-1 — coverage for {@link ClaudeProvider#detectContextOverflow(ObjectMapper, int, String)}.
 *
 * <p>Anthropic flags context overflow as
 * {@code {"error":{"type":"invalid_request_error","message":"prompt is too long: ..."}}}.
 * Other shapes (different error type, body not JSON, empty body) must NOT be flagged.
 */
class ClaudeProviderErrorDetectionTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("Anthropic invalid_request_error + 'prompt is too long' → overflow")
    void detect_canonicalAnthropicShape() {
        String body = "{\"type\":\"error\",\"error\":{"
                + "\"type\":\"invalid_request_error\","
                + "\"message\":\"prompt is too long: 230456 tokens > 200000 maximum\"}}";
        LlmContextLengthExceededException ex =
                ClaudeProvider.detectContextOverflow(mapper, 400, body);
        assertThat(ex).isNotNull();
        assertThat(ex.getMessage()).contains("Claude context overflow", "prompt is too long");
    }

    @Test
    @DisplayName("'input is too long' message variant is also recognised")
    void detect_inputTooLongVariant() {
        String body = "{\"error\":{\"type\":\"invalid_request_error\","
                + "\"message\":\"Input is too long for the requested model\"}}";
        LlmContextLengthExceededException ex =
                ClaudeProvider.detectContextOverflow(mapper, 400, body);
        assertThat(ex).isNotNull();
    }

    @Test
    @DisplayName("non-overflow invalid_request_error returns null (e.g. invalid model)")
    void detect_otherInvalidRequest_returnsNull() {
        String body = "{\"error\":{\"type\":\"invalid_request_error\","
                + "\"message\":\"model: claude-foo not found\"}}";
        assertThat(ClaudeProvider.detectContextOverflow(mapper, 400, body)).isNull();
    }

    @Test
    @DisplayName("authentication_error / rate_limit_error are NOT overflow")
    void detect_otherErrorTypes_returnNull() {
        String authErr = "{\"error\":{\"type\":\"authentication_error\","
                + "\"message\":\"invalid x-api-key\"}}";
        String rateErr = "{\"error\":{\"type\":\"rate_limit_error\","
                + "\"message\":\"rate limit exceeded\"}}";

        assertThat(ClaudeProvider.detectContextOverflow(mapper, 401, authErr)).isNull();
        assertThat(ClaudeProvider.detectContextOverflow(mapper, 429, rateErr)).isNull();
    }

    @Test
    @DisplayName("non-JSON body returns null without throwing")
    void detect_malformedBody_returnsNull() {
        assertThat(ClaudeProvider.detectContextOverflow(mapper, 500, "<html>oops</html>")).isNull();
        assertThat(ClaudeProvider.detectContextOverflow(mapper, 502, "no body")).isNull();
    }

    @Test
    @DisplayName("null / empty body returns null")
    void detect_nullOrEmptyBody_returnsNull() {
        assertThat(ClaudeProvider.detectContextOverflow(mapper, 400, null)).isNull();
        assertThat(ClaudeProvider.detectContextOverflow(mapper, 400, "")).isNull();
    }

    @Test
    @DisplayName("error fields at top level (proxy variant) are also recognised")
    void detect_topLevelErrorObject() {
        String body = "{\"type\":\"invalid_request_error\","
                + "\"message\":\"prompt is too long: 250000 > 200000\"}";
        LlmContextLengthExceededException ex =
                ClaudeProvider.detectContextOverflow(mapper, 400, body);
        assertThat(ex).isNotNull();
    }
}
