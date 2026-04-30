package com.skillforge.core.llm;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CTX-1 — coverage for {@link OpenAiProvider#detectContextOverflow(ObjectMapper, String, int, String)}.
 *
 * <p>OpenAI's canonical signal is {@code error.code == "context_length_exceeded"}; many
 * compat layers (DeepSeek, vLLM, bailian) only put it in the message string, so the
 * detector accepts both code matches and message keyword matches.
 */
class OpenAiProviderErrorDetectionTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("canonical OpenAI shape: error.code = context_length_exceeded")
    void detect_openAiCanonical() {
        String body = "{\"error\":{\"message\":\"This model's maximum context length is 8192 tokens. "
                + "However, your messages resulted in 9001 tokens.\","
                + "\"type\":\"invalid_request_error\","
                + "\"code\":\"context_length_exceeded\"}}";
        LlmContextLengthExceededException ex =
                OpenAiProvider.detectContextOverflow(mapper, "openai", 400, body);
        assertThat(ex).isNotNull();
        assertThat(ex.getMessage()).contains("openai context overflow");
    }

    @Test
    @DisplayName("DeepSeek-style: only the message contains 'context length' / 'context window'")
    void detect_deepseekMessageOnly() {
        String body = "{\"error\":{\"message\":\"Request exceeds maximum context length of 32768\","
                + "\"type\":\"invalid_request_error\"}}";
        LlmContextLengthExceededException ex =
                OpenAiProvider.detectContextOverflow(mapper, "deepseek", 400, body);
        assertThat(ex).isNotNull();
    }

    @Test
    @DisplayName("'context window' wording is recognised (vLLM-ish)")
    void detect_contextWindowWording() {
        String body = "{\"error\":{\"message\":\"prompt size exceeds the configured context window of 4096 tokens\"}}";
        LlmContextLengthExceededException ex =
                OpenAiProvider.detectContextOverflow(mapper, "vllm", 400, body);
        assertThat(ex).isNotNull();
    }

    @Test
    @DisplayName("error.type = context_length_exceeded (alt schema) is recognised")
    void detect_typeFieldVariant() {
        String body = "{\"error\":{\"type\":\"context_length_exceeded\","
                + "\"message\":\"too long\"}}";
        LlmContextLengthExceededException ex =
                OpenAiProvider.detectContextOverflow(mapper, "openai", 400, body);
        assertThat(ex).isNotNull();
    }

    @Test
    @DisplayName("invalid_api_key / rate_limit_exceeded are NOT overflow")
    void detect_unrelatedErrorsReturnNull() {
        String authErr = "{\"error\":{\"code\":\"invalid_api_key\","
                + "\"message\":\"Incorrect API key provided\"}}";
        String rateErr = "{\"error\":{\"code\":\"rate_limit_exceeded\","
                + "\"message\":\"You exceeded the rate limit\"}}";

        assertThat(OpenAiProvider.detectContextOverflow(mapper, "openai", 401, authErr)).isNull();
        assertThat(OpenAiProvider.detectContextOverflow(mapper, "openai", 429, rateErr)).isNull();
    }

    @Test
    @DisplayName("malformed JSON body returns null without throwing")
    void detect_malformed_returnsNull() {
        assertThat(OpenAiProvider.detectContextOverflow(mapper, "openai", 500, "<oops>")).isNull();
    }

    @Test
    @DisplayName("null / empty body returns null")
    void detect_nullOrEmptyBody_returnsNull() {
        assertThat(OpenAiProvider.detectContextOverflow(mapper, "openai", 400, null)).isNull();
        assertThat(OpenAiProvider.detectContextOverflow(mapper, "openai", 400, "")).isNull();
    }

    @Test
    @DisplayName("provider name is included in the surfaced exception message")
    void detect_includesProviderName() {
        String body = "{\"error\":{\"code\":\"context_length_exceeded\",\"message\":\"foo\"}}";
        LlmContextLengthExceededException ex =
                OpenAiProvider.detectContextOverflow(mapper, "bailian", 400, body);
        assertThat(ex).isNotNull();
        assertThat(ex.getMessage()).startsWith("bailian context overflow");
    }
}
