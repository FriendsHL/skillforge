package com.skillforge.observability.etl;

import com.skillforge.observability.domain.ProviderName;

/**
 * Plan §3.3 R2-B4 — provider name resolution for legacy {@code t_trace_span} LLM_CALL rows.
 *
 * <p>Output is verified against {@link ProviderName#CANONICAL} by
 * {@code ProviderNameAlignmentTest}.
 */
public final class LegacyLlmCallMapper {

    private LegacyLlmCallMapper() {}

    /**
     * @param modelId         LLM model id (e.g. "claude-sonnet-4", "qwen-max", "deepseek-v3")
     * @param qwenDisplayName configured override for qwen models: {@code "bailian"} (default)
     *                        or {@code "dashscope"} (when application.yml override set)
     */
    public static String resolveProviderFromModelId(String modelId, String qwenDisplayName) {
        if (modelId == null || modelId.isBlank()) return ProviderName.UNKNOWN;
        String m = modelId.toLowerCase();
        if (m.startsWith("claude")) return "claude";
        if (m.startsWith("gpt-") || m.startsWith("o1-") || m.startsWith("o3-")) return "openai";
        if (m.startsWith("deepseek")) return "deepseek";
        if (m.startsWith("qwen")) {
            if ("dashscope".equals(qwenDisplayName)) return "dashscope";
            return "bailian";
        }
        if (m.startsWith("vllm")) return "vllm";
        if (m.startsWith("ollama:")) return "ollama";
        return ProviderName.UNKNOWN;
    }
}
