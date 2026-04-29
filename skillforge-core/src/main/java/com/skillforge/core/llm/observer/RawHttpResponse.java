package com.skillforge.core.llm.observer;

import java.util.Map;

/**
 * 已脱敏的 LLM HTTP 响应快照（非流式 / 流式聚合后均可使用）。
 */
public record RawHttpResponse(
        int statusCode,
        Map<String, String> headers,
        byte[] body,
        String contentType
) {
    public RawHttpResponse {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        body = body == null ? new byte[0] : body;
    }
}
