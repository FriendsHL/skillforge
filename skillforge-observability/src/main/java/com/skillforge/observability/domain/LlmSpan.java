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
 *
 * <p>OBS-2 M0 新增字段：
 * <ul>
 *   <li>{@code kind} — {@code "llm"} | {@code "tool"} | {@code "event"}（应用层枚举校验）</li>
 *   <li>{@code eventType} — 仅 {@code kind="event"} 时填；{@code "ask_user"} | {@code "install_confirm"} | {@code "compact"} | {@code "agent_confirm"}</li>
 *   <li>{@code name} — tool name (kind=tool) / event name (kind=event) / null for kind=llm（走 model）</li>
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
        LlmSpanSource source,
        // OBS-2 M0 新增 — kind / eventType / name
        String kind,
        String eventType,
        String name
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
        if (kind == null || kind.isBlank()) kind = "llm";
    }

    /**
     * Backward-compatible constructor for OBS-1 callers — defaults
     * {@code kind="llm"}, {@code eventType=null}, {@code name=null}.
     * OBS-2 callers (tool / event spans) should use the full canonical constructor.
     */
    public LlmSpan(
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
            LlmSpanSource source) {
        this(spanId, traceId, parentSpanId, sessionId, agentId, provider, model,
                iterationIndex, stream, inputSummary, outputSummary,
                inputBlobRef, outputBlobRef, rawSseBlobRef, blobStatus,
                inputTokens, outputTokens, cacheReadTokens, usageJson,
                costUsd, latencyMs, startedAt, endedAt,
                finishReason, requestId, reasoningContent,
                error, errorType, toolUseId, attributes, source,
                "llm", null, null);
    }
}
