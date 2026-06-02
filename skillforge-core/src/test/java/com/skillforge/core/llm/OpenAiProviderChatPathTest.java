package com.skillforge.core.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Configurable chat-completions path (for Volcengine Ark, whose base-url already carries
 * the {@code /api/coding/v3} version segment and wants just {@code /chat/completions}).
 *
 * <p>Backward compatibility is load-bearing: null/blank chatPath MUST keep the historical
 * {@code /v1/chat/completions} so every existing provider is byte-identical.
 */
class OpenAiProviderChatPathTest {

    private OpenAiProvider provider(String baseUrl, String chatPath) {
        return new OpenAiProvider("k", baseUrl, "m", "test", "TEST_KEY", 60, 1, chatPath);
    }

    @Test
    @DisplayName("null chatPath → default /v1/chat/completions (existing providers unchanged)")
    void nullChatPath_defaultsToV1() {
        assertThat(provider("https://api.deepseek.com", null).chatCompletionsUrl())
                .isEqualTo("https://api.deepseek.com/v1/chat/completions");
        // the 7-arg constructor (used by tests / legacy) also defaults
        OpenAiProvider sevenArg = new OpenAiProvider("k", "https://api.deepseek.com", "m", "test", "TEST_KEY", 60, 1);
        assertThat(sevenArg.chatCompletionsUrl()).isEqualTo("https://api.deepseek.com/v1/chat/completions");
    }

    @Test
    @DisplayName("blank chatPath → default /v1/chat/completions")
    void blankChatPath_defaultsToV1() {
        assertThat(provider("https://api.deepseek.com", "  ").chatCompletionsUrl())
                .isEqualTo("https://api.deepseek.com/v1/chat/completions");
    }

    @Test
    @DisplayName("custom chatPath → Ark coding endpoint (base already has /api/coding/v3)")
    void customChatPath_arkCodingEndpoint() {
        assertThat(provider("https://ark.cn-beijing.volces.com/api/coding/v3", "/chat/completions")
                .chatCompletionsUrl())
                .isEqualTo("https://ark.cn-beijing.volces.com/api/coding/v3/chat/completions");
    }
}
