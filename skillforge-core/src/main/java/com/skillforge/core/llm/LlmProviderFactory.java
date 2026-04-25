package com.skillforge.core.llm;

import java.util.Locale;
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
     * @throws IllegalArgumentException 不支持的 provider 类型
     */
    public LlmProvider getProvider(ModelConfig config) {
        return providers.computeIfAbsent(config.getProviderName(), name -> createProvider(config));
    }

    /**
     * 根据名称获取已缓存的 LlmProvider。
     *
     * @param name provider 名称
     * @return 对应的 LlmProvider 实例，如果不存在则返回 null
     */
    public LlmProvider getProvider(String name) {
        return providers.get(name);
    }

    /**
     * 注册一个已创建的 LlmProvider。
     *
     * @param name     provider 名称
     * @param provider LlmProvider 实例
     */
    public void registerProvider(String name, LlmProvider provider) {
        providers.put(name, provider);
    }

    private LlmProvider createProvider(ModelConfig config) {
        String type = resolveType(config);
        String displayName = (config.getProviderName() != null && !config.getProviderName().isBlank())
                ? config.getProviderName()
                : type;
        String envHint = resolveEnvVarHint(displayName);
        return switch (type) {
            case "claude" -> new ClaudeProvider(
                    config.getApiKey(),
                    config.getBaseUrl(),
                    config.getModel(),
                    config.getReadTimeoutSeconds(),
                    config.getMaxRetries()
            );
            case "openai", "deepseek" -> new OpenAiProvider(
                    config.getApiKey(),
                    config.getBaseUrl(),
                    config.getModel(),
                    displayName,
                    envHint,
                    config.getReadTimeoutSeconds(),
                    config.getMaxRetries()
            );
            default -> throw new IllegalArgumentException(
                    "Unsupported LLM provider type: " + type
            );
        };
    }

    /**
     * 解析 provider 类型。优先使用 config.getType()，如果为空则回退到 config.getProviderName()，
     * 保持向后兼容。
     */
    private String resolveType(ModelConfig config) {
        String type = config.getType();
        if (type != null && !type.isEmpty()) {
            return type;
        }
        return config.getProviderName();
    }

    /**
     * Best-effort hint for the env var that carries the API key. Used purely in the
     * fail-fast diagnostic message; not a source of truth. Spring {@code @Value}
     * placeholders are not reflectable at runtime, so we keep a small hand-maintained
     * mapping here. Unknown provider names fall back to
     * {@code <UPPER>_API_KEY}.
     */
    private static String resolveEnvVarHint(String providerName) {
        if (providerName == null || providerName.isBlank()) {
            return null;
        }
        switch (providerName.toLowerCase(Locale.ROOT)) {
            case "bailian":
                return "DASHSCOPE_API_KEY";
            case "deepseek":
                return "DEEPSEEK_API_KEY";
            case "claude":
            case "anthropic":
                return "ANTHROPIC_API_KEY";
            case "openai":
                return "OPENAI_API_KEY";
            default:
                return providerName.toUpperCase(Locale.ROOT).replace('-', '_') + "_API_KEY";
        }
    }
}
