package com.skillforge.core.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.skillforge.core.llm.ProviderProtocolFamily.*;
import static com.skillforge.core.llm.ProviderProtocolFamilyResolver.resolve;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the longest-prefix-first ordering of the model classifier. DEEPSEEK_V4 must win
 * over DEEPSEEK_REASONER_LEGACY / DEEPSEEK_CHAT_LEGACY on "deepseek-v4*" inputs; the
 * "deepseek-v4-reasoner" hypothetical shouldn't regress.
 */
class ProviderProtocolFamilyResolverTest {

    @Test
    @DisplayName("deepseek prefixes resolve with longest-first order")
    void resolve_deepseekPrefixLongestFirst() {
        assertThat(resolve("deepseek-v4-pro")).isEqualTo(DEEPSEEK_V4);
        assertThat(resolve("deepseek-v4")).isEqualTo(DEEPSEEK_V4);
        assertThat(resolve("deepseek-chat")).isEqualTo(DEEPSEEK_CHAT_LEGACY);
        assertThat(resolve("deepseek-reasoner")).isEqualTo(DEEPSEEK_REASONER_LEGACY);
        assertThat(resolve("deepseek-coder")).isEqualTo(DEEPSEEK_CHAT_LEGACY);
    }

    @Test
    @DisplayName("qwen prefix matches flat and dotted variants")
    void resolve_qwenPrefix() {
        assertThat(resolve("qwen3.5-plus")).isEqualTo(QWEN_DASHSCOPE);
        assertThat(resolve("qwen3-max-2026-01-23")).isEqualTo(QWEN_DASHSCOPE);
        assertThat(resolve("qwen3-coder-next")).isEqualTo(QWEN_DASHSCOPE);
    }

    @Test
    @DisplayName("OpenAI o1/o3/o4 reasoning prefixes")
    void resolve_openaiReasoning() {
        assertThat(resolve("o1-preview")).isEqualTo(OPENAI_REASONING);
        assertThat(resolve("o3-mini")).isEqualTo(OPENAI_REASONING);
        assertThat(resolve("o4-general")).isEqualTo(OPENAI_REASONING);
    }

    @Test
    @DisplayName("null / blank defaults to GENERIC_OPENAI")
    void resolve_nullOrBlank_defaultsToGeneric() {
        assertThat(resolve(null)).isEqualTo(GENERIC_OPENAI);
        assertThat(resolve("")).isEqualTo(GENERIC_OPENAI);
        assertThat(resolve("   ")).isEqualTo(GENERIC_OPENAI);
    }

    @Test
    @DisplayName("unknown model defaults to GENERIC_OPENAI")
    void resolve_unknownModel_defaultsToGeneric() {
        assertThat(resolve("glm-5")).isEqualTo(GENERIC_OPENAI);
        assertThat(resolve("gpt-4o")).isEqualTo(GENERIC_OPENAI);
        assertThat(resolve("random-model")).isEqualTo(GENERIC_OPENAI);
    }

    @Test
    @DisplayName("hypothetical deepseek-v4-reasoner must resolve to DEEPSEEK_V4, not legacy R1")
    void resolve_hypotheticalDeepseekV4Reasoner_prefersV4() {
        // Guard-rail: an alias like "deepseek-v4-reasoner-20260501" must continue to match
        // the family that supports tool-calls + thinking (DEEPSEEK_V4), not the legacy
        // R1-style classification.
        assertThat(resolve("deepseek-v4-reasoner-20260501")).isEqualTo(DEEPSEEK_V4);
    }

    @Test
    @DisplayName("date suffix stripped before matching")
    void resolve_dateSuffixStripped() {
        assertThat(resolve("deepseek-v4-pro-20260123")).isEqualTo(DEEPSEEK_V4);
        assertThat(resolve("qwen3-max-2026-01-23")).isEqualTo(QWEN_DASHSCOPE);
    }

    @Test
    @DisplayName("uppercase input still classified correctly")
    void resolve_uppercaseNormalised() {
        assertThat(resolve("DEEPSEEK-V4-PRO")).isEqualTo(DEEPSEEK_V4);
        assertThat(resolve("QWEN3-MAX")).isEqualTo(QWEN_DASHSCOPE);
    }

    @Test
    @DisplayName("claude prefix classified even though ClaudeProvider handles it")
    void resolve_claudePrefix() {
        assertThat(resolve("claude-sonnet-4-20250514")).isEqualTo(CLAUDE);
    }
}
