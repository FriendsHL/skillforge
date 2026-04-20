package com.skillforge.server.channel.spi;

import java.util.Map;

/**
 * Webhook 请求快照：headers (lowercase key) + rawBody。
 * 替代 HttpServletRequest，消除 Servlet API 依赖。
 */
public record WebhookContext(
        Map<String, String> headers,
        byte[] rawBody
) {
    /** Read header, case-insensitive. */
    public String header(String name) {
        return headers.get(name.toLowerCase());
    }
}
