package com.skillforge.core.llm;

import java.util.HashMap;
import java.util.Map;

/**
 * 模型配置，描述一个 LLM 提供商的连接参数。
 */
public class ModelConfig {

    public static final int DEFAULT_READ_TIMEOUT_SECONDS = 60;
    public static final int DEFAULT_MAX_RETRIES = 1;
    public static final int DEFAULT_CONTEXT_WINDOW_TOKENS = 64_000;

    /**
     * Known context window sizes for common models.
     * Key = lowercase model name prefix (matched with startsWith after normalization).
     * Values are conservative published limits.
     */
    private static final Map<String, Integer> KNOWN_MODEL_WINDOWS;
    static {
        Map<String, Integer> m = new java.util.LinkedHashMap<>();
        // Anthropic Claude
        m.put("claude-opus-4",        200_000);
        m.put("claude-sonnet-4",      200_000);
        m.put("claude-haiku-4",       200_000);
        m.put("claude-3-7-sonnet",    200_000);
        m.put("claude-3-5-sonnet",    200_000);
        m.put("claude-3-5-haiku",     200_000);
        m.put("claude-3-opus",        200_000);
        m.put("claude-3-sonnet",      200_000);
        m.put("claude-3-haiku",       200_000);
        // OpenAI — more-specific prefixes MUST appear before less-specific ones
        m.put("gpt-4o",               128_000);  // gpt-4o, gpt-4o-mini — before "gpt-4"
        m.put("gpt-4-turbo",          128_000);  // gpt-4-turbo-* — before "gpt-4"
        m.put("gpt-4",                  8_192);  // gpt-4 base (non-turbo, non-4o)
        m.put("gpt-3.5-turbo",         16_385);
        m.put("o1",                   200_000);
        m.put("o3",                   200_000);
        // DeepSeek
        m.put("deepseek-chat",         64_000);
        m.put("deepseek-coder",        64_000);
        m.put("deepseek-r1",          128_000);
        m.put("deepseek-v4-pro",      128_000);
        // Alibaba Model Studio / Coding Plan published windows. Keep specific
        // prefixes before generic "qwen".
        m.put("qwen3.6-plus",       1_000_000);
        m.put("qwen3.5-plus",       1_000_000);
        m.put("qwen3-max",            262_144);
        m.put("qwen3-coder-next",     262_144);
        m.put("kimi-k2.5",            262_144);
        m.put("glm-5",                202_752);
        m.put("minimax-m2.5",         204_800);
        // Alibaba Qwen (conservative; override via YAML for exact values)
        m.put("qwen",                  32_000);
        // Gemini
        m.put("gemini-1.5",        1_000_000);
        m.put("gemini-2",          1_000_000);
        // LLaMA / Mistral (typical open weights)
        m.put("llama-3",               8_192);
        m.put("mistral",               8_192);
        KNOWN_MODEL_WINDOWS = java.util.Collections.unmodifiableMap(m);
    }

    /**
     * Returns the known context window size for a model name, using prefix matching.
     * The input is normalised: lowercased, date-suffix patterns like "-20250514" stripped.
     *
     * @param modelName raw model identifier (may be null)
     * @return non-empty Optional if a known prefix matches; empty if unknown
     */
    public static java.util.Optional<Integer> lookupKnownContextWindow(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return java.util.Optional.empty();
        }
        // Normalise: lowercase and strip date-like suffixes ("-YYYYMMDD" or "-YYYYMM")
        String normalized = modelName.toLowerCase().replaceAll("-\\d{6,8}$", "");
        for (Map.Entry<String, Integer> entry : KNOWN_MODEL_WINDOWS.entrySet()) {
            if (normalized.startsWith(entry.getKey())) {
                return java.util.Optional.of(entry.getValue());
            }
        }
        return java.util.Optional.empty();
    }

    private String providerName;
    private String type;
    private String apiKey;
    private String baseUrl;
    private String model;
    private int readTimeoutSeconds = DEFAULT_READ_TIMEOUT_SECONDS;
    private int maxRetries = DEFAULT_MAX_RETRIES;
    private int contextWindowTokens = DEFAULT_CONTEXT_WINDOW_TOKENS;
    private Map<String, Object> extraConfig = new HashMap<>();

    public ModelConfig() {
    }

    public ModelConfig(String providerName, String apiKey, String baseUrl, String model) {
        this.providerName = providerName;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
    }

    public ModelConfig(String providerName, String type, String apiKey, String baseUrl, String model) {
        this.providerName = providerName;
        this.type = type;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
    }

    public String getProviderName() {
        return providerName;
    }

    public void setProviderName(String providerName) {
        this.providerName = providerName;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getReadTimeoutSeconds() {
        return readTimeoutSeconds;
    }

    public void setReadTimeoutSeconds(int readTimeoutSeconds) {
        this.readTimeoutSeconds = readTimeoutSeconds;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public int getContextWindowTokens() {
        return contextWindowTokens;
    }

    public void setContextWindowTokens(int contextWindowTokens) {
        this.contextWindowTokens = contextWindowTokens;
    }

    public Map<String, Object> getExtraConfig() {
        return extraConfig;
    }

    public void setExtraConfig(Map<String, Object> extraConfig) {
        this.extraConfig = extraConfig;
    }
}
