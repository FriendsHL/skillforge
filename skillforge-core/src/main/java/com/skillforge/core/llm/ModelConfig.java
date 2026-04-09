package com.skillforge.core.llm;

import java.util.HashMap;
import java.util.Map;

/**
 * 模型配置，描述一个 LLM 提供商的连接参数。
 */
public class ModelConfig {

    public static final int DEFAULT_READ_TIMEOUT_SECONDS = 60;
    public static final int DEFAULT_MAX_RETRIES = 1;

    private String providerName;
    private String type;
    private String apiKey;
    private String baseUrl;
    private String model;
    private int readTimeoutSeconds = DEFAULT_READ_TIMEOUT_SECONDS;
    private int maxRetries = DEFAULT_MAX_RETRIES;
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

    public Map<String, Object> getExtraConfig() {
        return extraConfig;
    }

    public void setExtraConfig(Map<String, Object> extraConfig) {
        this.extraConfig = extraConfig;
    }
}
