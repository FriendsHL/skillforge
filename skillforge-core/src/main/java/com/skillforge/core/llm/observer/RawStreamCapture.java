package com.skillforge.core.llm.observer;

/**
 * 流式响应在 observer 这一层累积起来的快照。
 *
 * <p>{@code rawSse} 是字节级 SSE 文本（去除敏感 header 的副本）；
 * {@code accumulatedJson} 是 observer 内部边解析边累积的 final 响应 JSON
 * （等价于非流式调用的 response.body），用于落 blob 时跟非流式格式统一。
 *
 * <p>{@code sseTruncated=true} 表示 buf 命中 5 MB hard cap，被截断。
 */
public record RawStreamCapture(
        byte[] rawSse,
        byte[] accumulatedJson,
        boolean sseTruncated,
        long sseByteCount
) {
    public RawStreamCapture {
        rawSse = rawSse == null ? new byte[0] : rawSse;
        accumulatedJson = accumulatedJson == null ? new byte[0] : accumulatedJson;
    }
}
