package com.skillforge.observability.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * 一次 LLM 调用的 span (live 或 legacy)。
 *
 * <p>{@code blobStatus} 取值（plan §3.2 R2-W6）：
 * <ul>
 *   <li>{@code "ok"} — live 写盘成功</li>
 *   <li>{@code "legacy"} — 历史 ETL 行，无 raw payload</li>
 *   <li>{@code "write_failed"} — live 但 blob 写盘失败</li>
 *   <li>{@code "truncated"} — payload 超过 50 MB hard cap，被截断</li>
 * </ul>
 */
public record LlmSpan(
        String spanId,
        String traceId,
        String parentSpanId,
        String sessionId,
        Long agentId,
        String provider,
        String model,
        int iterationIndex,
        boolean stream,
        String inputSummary,
        String outputSummary,
        String inputBlobRef,
        String outputBlobRef,
        String rawSseBlobRef,
        String blobStatus,
        int inputTokens,
        int outputTokens,
        Integer cacheReadTokens,
        String usageJson,
        BigDecimal costUsd,
        long latencyMs,
        Instant startedAt,
        Instant endedAt,
        String finishReason,
        String requestId,
        String reasoningContent,
        String error,
        String errorType,
        String toolUseId,
        Map<String, Object> attributes,
        LlmSpanSource source
) {
    public LlmSpan {
        if (spanId == null || spanId.isBlank()) {
            throw new IllegalArgumentException("spanId is required");
        }
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("traceId is required");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        if (startedAt == null) {
            throw new IllegalArgumentException("startedAt is required");
        }
        attributes = attributes == null ? Collections.emptyMap() : Map.copyOf(attributes);
        if (source == null) source = LlmSpanSource.LIVE;
        if (blobStatus == null) blobStatus = "ok";
    }
}
