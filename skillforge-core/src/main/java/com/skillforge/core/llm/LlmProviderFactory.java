package com.skillforge.core.llm;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM 提供商工厂，根据配置创建并缓存 LlmProvider 实例。
 */
public class LlmProviderFactory {

    private final Map<String, LlmProvider> providers = new ConcurrentHashMap<>();

    /**
     * 根据配置获取或创建 LlmProvider。
     * 同一 providerName 只会创建一次（首次配置生效）。
     *
     * @param config 模型配置
     * @return 对应的 LlmProvider 实例
     * @throws IllegalArgumentException 不支持的 provider 名称
     */
    public LlmProvider getProvider(ModelConfig config) {
        return providers.computeIfAbsent(config.getProviderName(), name -> createProvider(config));
    }

    private LlmProvider createProvider(ModelConfig config) {
        return switch (config.getProviderName()) {
            case "claude" -> new ClaudeProvider(
                    config.getApiKey(),
                    config.getBaseUrl(),
                    config.getModel()
            );
            case "openai" -> new OpenAiProvider(
                    config.getApiKey(),
                    config.getBaseUrl(),
                    config.getModel()
            );
            default -> throw new IllegalArgumentException(
                    "Unsupported LLM provider: " + config.getProviderName()
            );
        };
    }
}
