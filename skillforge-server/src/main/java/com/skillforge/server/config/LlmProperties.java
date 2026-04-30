package com.skillforge.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 提供商配置属性，支持配置任意数量的 provider。
 * <p>
 * 配置示例:
 * <pre>
 * skillforge:
 *   llm:
 *     default-provider: openai
 *     providers:
 *       claude:
 *         type: claude
 *         api-key: xxx
 *         base-url: https://api.anthropic.com
 *         model: claude-sonnet-4-20250514
 *       openai:
 *         type: openai
 *         api-key: xxx
 *         base-url: https://api.deepseek.com
 *         model: deepseek-chat
 * </pre>
 */
@ConfigurationProperties(prefix = "skillforge.llm")
public class LlmProperties {

    private String defaultProvider;
    private Map<String, ProviderConfig> providers = new LinkedHashMap<>();

    public String getDefaultProvider() {
        return defaultProvider;
    }

    public void setDefaultProvider(String defaultProvider) {
        this.defaultProvider = defaultProvider;
    }

    public Map<String, ProviderConfig> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, ProviderConfig> providers) {
        this.providers = providers;
    }

    public static class ProviderConfig {

        private String type;
        private String apiKey;
        private String baseUrl;
        private String model;
        /**
         * Optional selectable models for UI/model picker.
         * Empty means fallback to the single {@link #model}.
         */
        private List<String> models = new ArrayList<>();
        /** Optional read timeout override (seconds). Null = use ModelConfig default. */
        private Integer readTimeoutSeconds;
        /** Optional max retries override (on SocketTimeoutException only). Null = use ModelConfig default. */
        private Integer maxRetries;
        /** Optional context window (tokens) override. Null = use ModelConfig default (32000). */
        private Integer contextWindowTokens;
        /** CTX-1: Optional per-provider compact thresholds. Null → defaults (0.60/0.80/0.85). */
        private CompactThresholds compactThresholds;

        public CompactThresholds getCompactThresholds() {
            return compactThresholds;
        }

        public void setCompactThresholds(CompactThresholds compactThresholds) {
            this.compactThresholds = compactThresholds;
        }

        public Integer getReadTimeoutSeconds() {
            return readTimeoutSeconds;
        }

        public void setReadTimeoutSeconds(Integer readTimeoutSeconds) {
            this.readTimeoutSeconds = readTimeoutSeconds;
        }

        public Integer getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(Integer maxRetries) {
            this.maxRetries = maxRetries;
        }

        public Integer getContextWindowTokens() {
            return contextWindowTokens;
        }

        public void setContextWindowTokens(Integer contextWindowTokens) {
            this.contextWindowTokens = contextWindowTokens;
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

        public List<String> getModels() {
            return models;
        }

        public void setModels(List<String> models) {
            this.models = models != null ? models : new ArrayList<>();
        }
    }

    /**
     * CTX-1 — per-provider compact threshold ratios. Mapped to
     * {@link com.skillforge.core.llm.CompactThresholds} when wiring providers.
     *
     * <pre>
     * skillforge:
     *   llm:
     *     providers:
     *       claude:
     *         compactThresholds:
     *           softRatio: 0.60       # B1 light trigger
     *           hardRatio: 0.80       # B2 full trigger (B1-gated)
     *           preemptiveRatio: 0.85 # preemptive full trigger
     * </pre>
     *
     * Each ratio is optional; null falls back to the default in
     * {@link com.skillforge.core.llm.CompactThresholds#DEFAULTS}.
     */
    public static class CompactThresholds {
        private Double softRatio;
        private Double hardRatio;
        private Double preemptiveRatio;

        public Double getSoftRatio() {
            return softRatio;
        }

        public void setSoftRatio(Double softRatio) {
            this.softRatio = softRatio;
        }

        public Double getHardRatio() {
            return hardRatio;
        }

        public void setHardRatio(Double hardRatio) {
            this.hardRatio = hardRatio;
        }

        public Double getPreemptiveRatio() {
            return preemptiveRatio;
        }

        public void setPreemptiveRatio(Double preemptiveRatio) {
            this.preemptiveRatio = preemptiveRatio;
        }

        /**
         * Materialise into the core value object, filling in defaults for any null field.
         */
        public com.skillforge.core.llm.CompactThresholds toCore() {
            com.skillforge.core.llm.CompactThresholds defaults =
                    com.skillforge.core.llm.CompactThresholds.DEFAULTS;
            return new com.skillforge.core.llm.CompactThresholds(
                    softRatio != null ? softRatio : defaults.getSoftRatio(),
                    hardRatio != null ? hardRatio : defaults.getHardRatio(),
                    preemptiveRatio != null ? preemptiveRatio : defaults.getPreemptiveRatio());
        }
    }
}
