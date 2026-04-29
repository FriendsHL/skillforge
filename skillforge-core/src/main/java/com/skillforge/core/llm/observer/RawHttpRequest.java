package com.skillforge.core.llm.observer;

import java.util.Collections;
import java.util.Map;

/**
 * 已脱敏的 LLM HTTP 请求快照。
 * 字段尽量保持 provider 中立（不带 OkHttp 类型）。
 */
public record RawHttpRequest(
        String method,
        String url,
        Map<String, String> headers,
        byte[] body,
        String contentType
) {
    public RawHttpRequest {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        body = body == null ? new byte[0] : body;
    }
}
