package com.skillforge.core.llm.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.core.llm.ProviderProtocolFamily;

/**
 * Normalize provider-specific {@code usage} JSON nodes into a canonical
 * {@link LlmResponse.Usage} (PROMPT-CACHE-MVP Phase 3 / INV-7).
 *
 * <p>Field-name landscape per protocol family (see mrd.md "5 Provider Cache 协议总表"):
 * <ul>
 *   <li>{@link ProviderProtocolFamily#CLAUDE} — {@code input_tokens / output_tokens /
 *       cache_read_input_tokens / cache_creation_input_tokens}. Anthropic's
 *       {@code input_tokens} already excludes cache hits.</li>
 *   <li>DeepSeek (V3 chat / V4 / R1 reasoner) — OpenAI shape
 *       ({@code prompt_tokens / completion_tokens}) plus {@code prompt_cache_hit_tokens}
 *       (read; auto-cache server-side, no creation token). {@code prompt_tokens} on the
 *       wire is TOTAL — includes cached portion.</li>
 *   <li>{@link ProviderProtocolFamily#QWEN_DASHSCOPE} — OpenAI shape with
 *       {@code prompt_tokens_details.cached_tokens} (read). {@code prompt_tokens} TOTAL.</li>
 *   <li>{@link ProviderProtocolFamily#GENERIC_OPENAI} (incl. OpenAI / xiaomi-mimo / vLLM
 *       / Ollama) — OpenAI shape with {@code prompt_tokens_details.cached_tokens} (read);
 *       creation tracked server-side only. {@code prompt_tokens} TOTAL.</li>
 *   <li>{@link ProviderProtocolFamily#OPENAI_REASONING} — same as GENERIC_OPENAI for
 *       cache fields.</li>
 * </ul>
 *
 * <p><strong>Semantic contract (r3, FE r2 push-back fix)</strong>: across all provider
 * families the resulting {@link LlmResponse.Usage#getInputTokens() inputTokens} represents
 * the <em>non-cached</em> input tokens. For OpenAI-shape providers we explicitly subtract
 * {@code cacheRead} from the wire {@code prompt_tokens} so callers can compute total prompt
 * size as {@code inputTokens + cacheRead + cacheCreation} without double-counting. Claude
 * needs no such subtraction — its wire {@code input_tokens} is already non-cached.
 *
 * <p>Dirty wire data (e.g. {@code cached_tokens > prompt_tokens} due to upstream bugs) is
 * clamped to 0 with {@link Math#max(int, int)} — never returns a negative {@code inputTokens}.
 */
public final class UsageNormalizer {

    private UsageNormalizer() {}

    /**
     * Parse a provider {@code usage} JSON node into the canonical Usage object.
     * Tolerates missing nodes (returns a Usage with zeros) — INV-9 enables first-call cache
     * detection even when usage was empty.
     *
     * @param usageNode raw usage node (may be null / missing — treated as empty)
     * @param family    resolved protocol family for the model in question
     */
    public static LlmResponse.Usage parse(JsonNode usageNode, ProviderProtocolFamily family) {
        if (family == null) family = ProviderProtocolFamily.GENERIC_OPENAI;
        int input;
        int output;
        int cacheRead = 0;
        int cacheCreation = 0;

        if (usageNode == null || usageNode.isMissingNode() || usageNode.isNull()) {
            return new LlmResponse.Usage(0, 0, 0, 0);
        }

        // Anthropic uses input_tokens / output_tokens at the top level; OpenAI-compat uses
        // prompt_tokens / completion_tokens. asInt() with a fallback default lets either
        // wire shape land here without family-specific gating.
        input = usageNode.path("input_tokens").asInt(usageNode.path("prompt_tokens").asInt(0));
        output = usageNode.path("output_tokens").asInt(usageNode.path("completion_tokens").asInt(0));

        switch (family) {
            case CLAUDE -> {
                cacheRead = usageNode.path("cache_read_input_tokens").asInt(0);
                cacheCreation = usageNode.path("cache_creation_input_tokens").asInt(0);
                // Claude wire input_tokens already EXCLUDES cached/creation portions —
                // do NOT subtract again or we'd double-discount.
            }
            case DEEPSEEK_V4, DEEPSEEK_CHAT_LEGACY, DEEPSEEK_REASONER_LEGACY -> {
                // DeepSeek auto-cache: hit tokens reported, no creation indicator.
                cacheRead = usageNode.path("prompt_cache_hit_tokens").asInt(0);
                cacheCreation = 0;
                // Wire prompt_tokens is TOTAL (cached + non-cached) — subtract to keep
                // inputTokens semantics aligned with Claude (non-cached only).
                input = Math.max(0, input - cacheRead);
            }
            case QWEN_DASHSCOPE -> {
                // OpenAI-shape envelope: prompt_tokens_details.cached_tokens
                cacheRead = usageNode.path("prompt_tokens_details").path("cached_tokens").asInt(0);
                cacheCreation = 0;
                input = Math.max(0, input - cacheRead);
            }
            case OPENAI_REASONING, GENERIC_OPENAI -> {
                // OpenAI / mimo / vLLM / Ollama all advertise cache via cached_tokens.
                cacheRead = usageNode.path("prompt_tokens_details").path("cached_tokens").asInt(0);
                cacheCreation = 0;
                input = Math.max(0, input - cacheRead);
            }
            default -> {
                // Defensive: unknown family — leave cache fields at 0 (no false positives).
                cacheRead = 0;
                cacheCreation = 0;
            }
        }

        return new LlmResponse.Usage(input, output, cacheRead, cacheCreation);
    }
}
