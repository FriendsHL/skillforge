package com.skillforge.observability.observer;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Plan §6.1 — HTTP header 黑名单脱敏。完全删除一组敏感 header；
 * 含 {@code "Bearer "} 的 value 替换为 {@code [REDACTED]}；保留无敏感语义 header。
 */
public final class HeaderSanitizer {

    private static final Set<String> DROP_HEADERS = Set.of(
            "authorization",
            "x-api-key",
            "api-key",
            "apikey",
            "anthropic-api-key",
            "x-anthropic-api-key",
            "openai-api-key",
            "x-amzn-aws-accesskey",
            "cookie",
            "set-cookie",
            "proxy-authorization"
    );

    private HeaderSanitizer() {}

    public static Map<String, String> sanitize(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return Map.of();
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            String key = e.getKey();
            String lower = key.toLowerCase(Locale.ROOT);
            if (DROP_HEADERS.contains(lower)) continue;
            String value = e.getValue();
            if (value != null && value.contains("Bearer ")) {
                value = "[REDACTED]";
            }
            out.put(key, value);
        }
        return out;
    }
}
