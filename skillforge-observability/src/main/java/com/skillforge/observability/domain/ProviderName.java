package com.skillforge.observability.domain;

import java.util.Set;

/**
 * Authoritative provider name set (plan §3.3 / R2-B4).
 *
 * <p>Both live ({@code LlmProvider.getName()}) and legacy ETL
 * ({@code resolveProviderFromModelId}) MUST only emit values from this set.
 * Out-of-set values get coerced to {@link #UNKNOWN} + log warn.
 */
public final class ProviderName {

    public static final Set<String> CANONICAL = Set.of(
            "claude", "openai", "deepseek", "dashscope", "bailian",
            "vllm", "ollama", "unknown"
    );
    public static final String UNKNOWN = "unknown";

    private ProviderName() {}

    /** True if {@code name} is a canonical provider name. */
    public static boolean isCanonical(String name) {
        return name != null && CANONICAL.contains(name);
    }

    /** Coerce {@code raw} to a canonical name, falling back to {@link #UNKNOWN}. */
    public static String coerce(String raw) {
        return isCanonical(raw) ? raw : UNKNOWN;
    }
}
