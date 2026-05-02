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
 *
 * <p>OBS-2 M0 新增字段（trace lifecycle aggregation）：
 * <ul>
 *   <li>{@code status} — {@code "running"} | {@code "ok"} | {@code "error"} | {@code "cancelled"}</li>
 *   <li>{@code error} — 失败时摘要错误信息</li>
 *   <li>{@code totalDurationMs} — trace 结束时 finalize 写回</li>
 *   <li>{@code toolCallCount} — 该 trace 内 t_llm_span where kind=tool 计数</li>
 *   <li>{@code eventCount} — 该 trace 内 t_llm_span where kind=event 计数</li>
 *   <li>{@code agentName} — trace 主 agent 名（与 root_name 同语义；新加为字段自描述）</li>
 * </ul>
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
        LlmSpanSource source,
        // OBS-2 M0 新增字段
        String status,
        String error,
        long totalDurationMs,
        int toolCallCount,
        int eventCount,
        String agentName
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
        if (status == null || status.isBlank()) status = "running";
    }

    /**
     * Backward-compatible constructor for OBS-1 callers — defaults
     * {@code status="running"}, {@code error=null}, aggregate counts to 0,
     * {@code agentName=null}.
     */
    public LlmTrace(
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
            LlmSpanSource source) {
        this(traceId, sessionId, agentId, userId, rootName,
                startedAt, endedAt,
                totalInputTokens, totalOutputTokens, totalCostUsd, source,
                "running", null, 0L, 0, 0, null);
    }
}
