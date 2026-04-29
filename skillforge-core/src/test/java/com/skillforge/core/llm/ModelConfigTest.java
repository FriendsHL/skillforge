package com.skillforge.core.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelConfigTest {

    @Test
    void defaultContextWindowTokens_unknownModelFallback_is64k() {
        assertThat(ModelConfig.DEFAULT_CONTEXT_WINDOW_TOKENS).isEqualTo(64_000);
        assertThat(ModelConfig.lookupKnownContextWindow("unknown-model")).isEmpty();
    }

    @Test
    void lookupKnownContextWindow_bailianCodingModels_returnsPublishedLimits() {
        assertThat(ModelConfig.lookupKnownContextWindow("qwen3.6-plus")).contains(1_000_000);
        assertThat(ModelConfig.lookupKnownContextWindow("qwen3.6-plus-2026-04-02")).contains(1_000_000);
        assertThat(ModelConfig.lookupKnownContextWindow("qwen3.5-plus")).contains(1_000_000);
        assertThat(ModelConfig.lookupKnownContextWindow("qwen3-max-2026-01-23")).contains(262_144);
        assertThat(ModelConfig.lookupKnownContextWindow("qwen3-coder-next")).contains(262_144);
        assertThat(ModelConfig.lookupKnownContextWindow("kimi-k2.5")).contains(262_144);
        assertThat(ModelConfig.lookupKnownContextWindow("glm-5")).contains(202_752);
        assertThat(ModelConfig.lookupKnownContextWindow("MiniMax-M2.5")).contains(204_800);
    }
}
