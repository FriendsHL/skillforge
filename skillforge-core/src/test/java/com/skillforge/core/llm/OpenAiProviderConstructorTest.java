package com.skillforge.core.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers {@link OpenAiProvider} construction invariants:
 *  - blank / null api key fails fast with a diagnostic hint
 *  - blank / null providerDisplayName fails fast
 *  - getName() returns the caller-supplied display name, not a hard-coded "openai"
 */
class OpenAiProviderConstructorTest {

    @Test
    @DisplayName("ctor rejects blank API key with env-var hint")
    void ctor_rejectsBlankApiKey() {
        assertThatThrownBy(() -> new OpenAiProvider("", "https://x", "gpt-4o",
                "openai", "OPENAI_API_KEY", 60, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing its API key")
                .hasMessageContaining("OPENAI_API_KEY");
    }

    @Test
    @DisplayName("ctor rejects null API key")
    void ctor_rejectsNullApiKey() {
        assertThatThrownBy(() -> new OpenAiProvider(null, "https://x", "gpt-4o",
                "bailian", "DASHSCOPE_API_KEY", 60, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bailian")
                .hasMessageContaining("DASHSCOPE_API_KEY");
    }

    @Test
    @DisplayName("ctor rejects blank providerDisplayName")
    void ctor_rejectsBlankProviderDisplayName() {
        assertThatThrownBy(() -> new OpenAiProvider("k", "https://x", "gpt-4o",
                "", "OPENAI_API_KEY", 60, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("providerDisplayName");
    }

    @Test
    @DisplayName("ctor emits generic env-var hint when envVarName is null")
    void ctor_blankApiKey_withNullEnvVar_usesGenericHint() {
        assertThatThrownBy(() -> new OpenAiProvider("", "https://x", "gpt-4o",
                "bailian", null, 60, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bailian")
                .hasMessageContaining("provider-specific API key");
    }

    @Test
    @DisplayName("getName returns providerDisplayName (not hard-coded 'openai')")
    void getName_returnsProviderDisplayName() {
        OpenAiProvider p = new OpenAiProvider("k", "https://x", "gpt-4o",
                "bailian", "DASHSCOPE_API_KEY", 60, 1);
        assertThat(p.getName()).isEqualTo("bailian");
    }

    @Test
    @DisplayName("legacy 5-arg ctor still works with default 'openai' displayName")
    void legacyCtor_defaultsToOpenaiDisplayName() {
        @SuppressWarnings("deprecation")
        OpenAiProvider p = new OpenAiProvider("k", "https://x", "gpt-4o", 60, 1);
        assertThat(p.getName()).isEqualTo("openai");
    }

    @Test
    @DisplayName("ClaudeProvider also fails fast on blank API key")
    void claudeProvider_rejectsBlankApiKey() {
        assertThatThrownBy(() -> new ClaudeProvider("", "https://api.anthropic.com",
                "claude-sonnet-4-20250514", 60, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("claude")
                .hasMessageContaining("ANTHROPIC_API_KEY");
    }
}
