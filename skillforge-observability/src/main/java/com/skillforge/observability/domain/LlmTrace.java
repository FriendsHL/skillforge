package com.skillforge.observability.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 一次 AGENT_LOOP 对应的 LLM trace 维度聚合。
 *
 * <p>持久化时通过 {@code PgLlmTraceStore.upsertTrace} 用 {@code ON CONFLICT (trace_id) DO UPDATE SET}
 * 累加 token / cost、用 {@code GREATEST} 推进 {@code endedAt}（plan §3.1 R2-B3）。
 *
 * <p>对单次写入而言，{@link #totalInputTokens} / {@link #totalOutputTokens} / {@link #totalCostUsd}
 * 表示**本次 LLM call 的 delta**；DB 端用 SUM 累加，避免应用层 read-modify-write race。
 */
public record LlmTrace(
        String traceId,
        String sessionId,
        Long agentId,
        Long userId,
        String rootName,
        Instant startedAt,
        Instant endedAt,
        int totalInputTokens,
        int totalOutputTokens,
        BigDecimal totalCostUsd,
        LlmSpanSource source
) {
    public LlmTrace {
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("traceId is required");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        if (startedAt == null) {
            throw new IllegalArgumentException("startedAt is required");
        }
        if (source == null) source = LlmSpanSource.LIVE;
    }
}
