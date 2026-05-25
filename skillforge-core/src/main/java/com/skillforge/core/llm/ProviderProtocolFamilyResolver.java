package com.skillforge.core.llm;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Classifies a model name into a {@link ProviderProtocolFamily}.
 *
 * <p>Identification strategy: lower-case the model name, strip a trailing
 * {@code -YYYYMMDD} / {@code -YYYYMM} date suffix, then longest-prefix match against a
 * hand-maintained table. {@link java.util.LinkedHashMap} insertion order matters —
 * {@code deepseek-reasoner} must be checked before {@code deepseek-v4} before
 * {@code deepseek-chat} to avoid misclassifying variants.</p>
 *
 * <p>Why model name, not base URL: dashscope hosts non-qwen models (e.g.
 * {@code bailian:glm-5}); identifying by URL would misclassify those.</p>
 *
 * <p>Unknown models fall back to {@link ProviderProtocolFamily#GENERIC_OPENAI}
 * (no thinking, no reasoning_effort). Operators will see a warn-level log in
 * {@code OpenAiProvider} when thinkingMode is set but the family is generic.</p>
 */
public final class ProviderProtocolFamilyResolver {

    /** Long prefixes first — order is significant. */
    private static final Map<String, ProviderProtocolFamily> PREFIX = new LinkedHashMap<>();

    static {
        // Claude is not served by OpenAiProvider; listed to complete the return domain.
        PREFIX.put("claude",            ProviderProtocolFamily.CLAUDE);
        // DeepSeek variants — longest first.
        PREFIX.put("deepseek-reasoner", ProviderProtocolFamily.DEEPSEEK_REASONER_LEGACY);
        PREFIX.put("deepseek-v4",       ProviderProtocolFamily.DEEPSEEK_V4);
        PREFIX.put("deepseek-chat",     ProviderProtocolFamily.DEEPSEEK_CHAT_LEGACY);
        PREFIX.put("deepseek-coder",    ProviderProtocolFamily.DEEPSEEK_CHAT_LEGACY);
        // Qwen (DashScope).
        PREFIX.put("qwen",              ProviderProtocolFamily.QWEN_DASHSCOPE);
        // Xiaomi MiMo (xiaomimimo.com) — DeepSeek-V4-style thinking dialect.
        // Initial 2026-05-24 mapping to QWEN_DASHSCOPE was wrong: xiaomimimo.com completely
        // ignores dashscope's {@code enable_thinking} field (verified 2026-05-25 curl: A
        // with enable_thinking:false → content='' + reasoning_content; B with
        // thinking.type:disabled → content present, reasoning empty). XIAOMI_MIMO carries
        // defaultsThinkingOn=true so the OpenAiProvider still writes thinking.type:disabled
        // when ThinkingMode is null/AUTO (same guard intent as QWEN_DASHSCOPE — prevents
        // SessionTitleService rendering "首先，用户要求..." as title for non-explicit flows).
        PREFIX.put("mimo",              ProviderProtocolFamily.XIAOMI_MIMO);
        // OpenAI reasoning series.
        PREFIX.put("o1",                ProviderProtocolFamily.OPENAI_REASONING);
        PREFIX.put("o3",                ProviderProtocolFamily.OPENAI_REASONING);
        PREFIX.put("o4",                ProviderProtocolFamily.OPENAI_REASONING);
    }

    private ProviderProtocolFamilyResolver() {}

    /**
     * Resolve the family from a model identifier (e.g. {@code "qwen3.5-plus"},
     * {@code "deepseek-v4-pro-20260123"}). Never returns null; unknown inputs degrade to
     * {@link ProviderProtocolFamily#GENERIC_OPENAI}.
     */
    public static ProviderProtocolFamily resolve(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return ProviderProtocolFamily.GENERIC_OPENAI;
        }
        String normalized = modelName.toLowerCase(Locale.ROOT)
                .replaceAll("-\\d{6,8}$", "");
        for (Map.Entry<String, ProviderProtocolFamily> entry : PREFIX.entrySet()) {
            if (normalized.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return ProviderProtocolFamily.GENERIC_OPENAI;
    }
}
