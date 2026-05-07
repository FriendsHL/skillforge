package com.skillforge.core.llm.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.core.llm.ProviderProtocolFamily;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PROMPT-CACHE-MVP Phase 3 — INV-7 unit coverage of {@link UsageNormalizer}.
 *
 * <p>One test per protocol-family branch (CLAUDE / DEEPSEEK_V4 / DEEPSEEK_REASONER_LEGACY
 * / QWEN_DASHSCOPE / GENERIC_OPENAI / OPENAI_REASONING). Plus null/missing usage handling
 * + r3 dirty-data clamp (cached &gt; total → inputTokens=0, never negative).
 *
 * <p><strong>r3 semantic contract</strong>: across all OpenAI-shape providers the wire
 * {@code prompt_tokens} is TOTAL (cached + non-cached). UsageNormalizer subtracts
 * {@code cacheRead} so {@link LlmResponse.Usage#getInputTokens() inputTokens} represents
 * the non-cached portion, matching Claude's already-non-cached {@code input_tokens}.
 * Total prompt size for caller computations is then
 * {@code inputTokens + cacheRead + cacheCreation}.
 */
@DisplayName("UsageNormalizer — 5-provider cache field parsing (Phase 3 / r3 non-cached semantic)")
class UsageNormalizerTest {

    private final ObjectMapper m = new ObjectMapper().findAndRegisterModules();

    @Test
    @DisplayName("CLAUDE: cache_creation_input_tokens + cache_read_input_tokens parsed")
    void claudeUsage() throws Exception {
        JsonNode usage = m.readTree("""
                {
                  "input_tokens": 100,
                  "output_tokens": 50,
                  "cache_creation_input_tokens": 200,
                  "cache_read_input_tokens": 800
                }
                """);

        LlmResponse.Usage out = UsageNormalizer.parse(usage, ProviderProtocolFamily.CLAUDE);

        assertThat(out.getInputTokens()).isEqualTo(100);
        assertThat(out.getOutputTokens()).isEqualTo(50);
        assertThat(out.getCacheReadInputTokens()).isEqualTo(800);
        assertThat(out.getCacheCreationInputTokens()).isEqualTo(200);
    }

    @Test
    @DisplayName("DEEPSEEK_V4: prompt_tokens=TOTAL → inputTokens = total - cacheRead (r3 non-cached semantic)")
    void deepseekV4Usage() throws Exception {
        // Wire prompt_tokens = TOTAL (1200), prompt_cache_hit_tokens = 1100 (cached portion).
        // Expected non-cached input = 1200 - 1100 = 100.
        JsonNode usage = m.readTree("""
                {
                  "prompt_tokens": 1200,
                  "completion_tokens": 60,
                  "prompt_cache_hit_tokens": 1100,
                  "prompt_cache_miss_tokens": 100
                }
                """);

        LlmResponse.Usage out = UsageNormalizer.parse(usage, ProviderProtocolFamily.DEEPSEEK_V4);

        assertThat(out.getInputTokens()).isEqualTo(100); // 1200 - 1100
        assertThat(out.getOutputTokens()).isEqualTo(60);
        assertThat(out.getCacheReadInputTokens()).isEqualTo(1100);
        assertThat(out.getCacheCreationInputTokens()).isZero();
        // Sanity: caller computing total prompt = inputTokens + cacheRead + cacheCreation
        assertThat(out.getInputTokens() + out.getCacheReadInputTokens()
                + out.getCacheCreationInputTokens()).isEqualTo(1200);
    }

    @Test
    @DisplayName("DEEPSEEK_REASONER_LEGACY: same subtraction rule applies")
    void deepseekReasonerLegacyUsage() throws Exception {
        JsonNode usage = m.readTree("""
                {
                  "prompt_tokens": 500,
                  "completion_tokens": 40,
                  "prompt_cache_hit_tokens": 250
                }
                """);

        LlmResponse.Usage out = UsageNormalizer.parse(usage,
                ProviderProtocolFamily.DEEPSEEK_REASONER_LEGACY);

        assertThat(out.getInputTokens()).isEqualTo(250); // 500 - 250
        assertThat(out.getCacheReadInputTokens()).isEqualTo(250);
        assertThat(out.getCacheCreationInputTokens()).isZero();
    }

    @Test
    @DisplayName("QWEN_DASHSCOPE: nested cached_tokens subtracted from prompt_tokens TOTAL")
    void qwenDashscopeUsage() throws Exception {
        JsonNode usage = m.readTree("""
                {
                  "prompt_tokens": 800,
                  "completion_tokens": 30,
                  "prompt_tokens_details": { "cached_tokens": 600 }
                }
                """);

        LlmResponse.Usage out = UsageNormalizer.parse(usage,
                ProviderProtocolFamily.QWEN_DASHSCOPE);

        assertThat(out.getInputTokens()).isEqualTo(200); // 800 - 600
        assertThat(out.getCacheReadInputTokens()).isEqualTo(600);
        assertThat(out.getCacheCreationInputTokens()).isZero();
    }

    @Test
    @DisplayName("GENERIC_OPENAI: nested cached_tokens subtracted (covers OpenAI / mimo / vLLM)")
    void genericOpenAiUsage() throws Exception {
        JsonNode usage = m.readTree("""
                {
                  "prompt_tokens": 400,
                  "completion_tokens": 20,
                  "prompt_tokens_details": { "cached_tokens": 380 }
                }
                """);

        LlmResponse.Usage out = UsageNormalizer.parse(usage,
                ProviderProtocolFamily.GENERIC_OPENAI);

        assertThat(out.getInputTokens()).isEqualTo(20); // 400 - 380
        assertThat(out.getCacheReadInputTokens()).isEqualTo(380);
        assertThat(out.getCacheCreationInputTokens()).isZero();
    }

    @Test
    @DisplayName("OPENAI_REASONING: independently asserted (regression guard if family arms split)")
    void openAiReasoningUsage() throws Exception {
        // OpenAI o1 / o3 / o4 reasoning models share the gpt-4o cached_tokens shape today
        // — but the family enum is distinct, so we keep an independent test in case
        // OpenAI ever splits the response shape (e.g. adds reasoning_tokens to cache).
        JsonNode usage = m.readTree("""
                {
                  "prompt_tokens": 700,
                  "completion_tokens": 25,
                  "prompt_tokens_details": { "cached_tokens": 500 }
                }
                """);

        LlmResponse.Usage out = UsageNormalizer.parse(usage,
                ProviderProtocolFamily.OPENAI_REASONING);

        assertThat(out.getInputTokens()).isEqualTo(200); // 700 - 500
        assertThat(out.getOutputTokens()).isEqualTo(25);
        assertThat(out.getCacheReadInputTokens()).isEqualTo(500);
        assertThat(out.getCacheCreationInputTokens()).isZero();
    }

    @Test
    @DisplayName("r3 dirty wire data: cached_tokens > prompt_tokens → inputTokens clamped to 0 (no negative)")
    void dirtyWireDataClampedToZero() throws Exception {
        // Upstream bug scenario: cached_tokens reported as larger than prompt_tokens.
        // Math.max(0, total - cacheRead) keeps inputTokens non-negative — caller never
        // sees a nonsense "-300 input tokens" leak into hit-rate / billing math.
        JsonNode usage = m.readTree("""
                {
                  "prompt_tokens": 500,
                  "completion_tokens": 10,
                  "prompt_tokens_details": { "cached_tokens": 800 }
                }
                """);

        LlmResponse.Usage out = UsageNormalizer.parse(usage,
                ProviderProtocolFamily.GENERIC_OPENAI);

        assertThat(out.getInputTokens()).isZero();
        assertThat(out.getCacheReadInputTokens()).isEqualTo(800);
    }

    @Test
    @DisplayName("null usage node returns zero-filled Usage (INV-9 first call has no break false-positive)")
    void nullUsageReturnsZeros() {
        LlmResponse.Usage out = UsageNormalizer.parse(null, ProviderProtocolFamily.CLAUDE);

        assertThat(out.getInputTokens()).isZero();
        assertThat(out.getOutputTokens()).isZero();
        assertThat(out.getCacheReadInputTokens()).isZero();
        assertThat(out.getCacheCreationInputTokens()).isZero();
    }

    @Test
    @DisplayName("missing nested cached_tokens defaults to 0 (no NPE on partial usage)")
    void missingNestedCachedTokens() throws Exception {
        JsonNode usage = m.readTree("""
                {
                  "prompt_tokens": 200,
                  "completion_tokens": 10,
                  "prompt_tokens_details": {}
                }
                """);

        LlmResponse.Usage out = UsageNormalizer.parse(usage,
                ProviderProtocolFamily.GENERIC_OPENAI);

        assertThat(out.getCacheReadInputTokens()).isZero();
    }

    @Test
    @DisplayName("CLAUDE with no cache fields: cache fields stay 0 (first-call scenario)")
    void claudeFirstCallNoCache() throws Exception {
        JsonNode usage = m.readTree("""
                {
                  "input_tokens": 100,
                  "output_tokens": 50
                }
                """);

        LlmResponse.Usage out = UsageNormalizer.parse(usage, ProviderProtocolFamily.CLAUDE);

        assertThat(out.getInputTokens()).isEqualTo(100);
        assertThat(out.getCacheReadInputTokens()).isZero();
        assertThat(out.getCacheCreationInputTokens()).isZero();
    }
}
