package com.skillforge.core.llm;

import java.util.HashMap;
import java.util.Map;

/**
 * 模型配置，描述一个 LLM 提供商的连接参数。
 */
public class ModelConfig {

    private String providerName;
    private String type;
    private String apiKey;
    private String baseUrl;
    private String model;
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

    public Map<String, Object> getExtraConfig() {
        return extraConfig;
    }

    public void setExtraConfig(Map<String, Object> extraConfig) {
        this.extraConfig = extraConfig;
    }
}
