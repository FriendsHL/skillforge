package com.skillforge.core.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiring test: a {@link ModelConfig} carrying {@code chatPath} (the bridge from the
 * server's yml → LlmProperties → SkillForgeConfig → ModelConfig) must reach the
 * constructed {@link OpenAiProvider}, and the {@code "ark"} type alias must route to
 * the OpenAI-compatible path. Closes the factory→provider link the unit test didn't cover.
 */
class LlmProviderFactoryChatPathTest {

    private ModelConfig config(String name, String type, String baseUrl, String chatPath) {
        ModelConfig c = new ModelConfig();
        c.setProviderName(name);
        c.setType(type);
        c.setApiKey("k");
        c.setBaseUrl(baseUrl);
        c.setModel("minimax-latest");
        c.setChatPath(chatPath);
        return c;
    }

    @Test
    @DisplayName("type=ark + chatPath threads to OpenAiProvider → Ark coding endpoint URL")
    void arkTypeAndChatPath_threadThrough() {
        LlmProviderFactory factory = new LlmProviderFactory();
        ModelConfig cfg = config("ark", "ark",
                "https://ark.cn-beijing.volces.com/api/coding/v3", "/chat/completions");

        LlmProvider p = factory.getProvider(cfg);

        assertThat(p).isInstanceOf(OpenAiProvider.class);
        assertThat(((OpenAiProvider) p).chatCompletionsUrl())
                .isEqualTo("https://ark.cn-beijing.volces.com/api/coding/v3/chat/completions");
    }

    @Test
    @DisplayName("openai type without chatPath → default /v1/chat/completions (existing providers unchanged)")
    void openaiNoChatPath_defaults() {
        LlmProviderFactory factory = new LlmProviderFactory();
        ModelConfig cfg = config("ds", "openai", "https://api.deepseek.com", null);

        LlmProvider p = factory.getProvider(cfg);

        assertThat(((OpenAiProvider) p).chatCompletionsUrl())
                .isEqualTo("https://api.deepseek.com/v1/chat/completions");
    }
}
