package com.skillforge.observability.api;

import com.skillforge.observability.domain.LlmSpan;
import com.skillforge.observability.domain.LlmTrace;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * LLM trace 持久化接口。
 *
 * <p>{@link #write} 是 OBS-1 observer 主路径入口；内部需保证：
 * <ol>
 *   <li>upsertTrace（{@code ON CONFLICT (trace_id) DO UPDATE SET …} 累加 + GREATEST）</li>
 *   <li>insertSpan</li>
 * </ol>
 * 在同一事务内执行（plan §3.1 / §4.5）。
 *
 * <p>OBS-2 M1 新增的 5 个方法负责 trace lifecycle 与 tool/event span 双写：
 * <ul>
 *   <li>{@link #upsertTraceStub} — rootSpan 创建时同步调用，保证 trace 行存在（status='running'）</li>
 *   <li>{@link #writeToolSpan} — tool 执行完成后异步写入 t_llm_span (kind='tool')</li>
 *   <li>{@link #writeEventSpan} — 4 类 event span 异步写入 t_llm_span (kind='event')</li>
 *   <li>{@link #finalizeTrace} — engine 退出时异步更新 t_llm_trace 终态（含幂等保护）</li>
 *   <li>{@link #listSpansByTrace} — M3 读路径按 trace_id + kind filter 查询</li>
 * </ul>
 */
public interface LlmTraceStore {

    /** 写入一个 LLM call（trace upsert + span insert，同一事务）。OBS-1 主路径。 */
    void write(LlmTraceWriteRequest request);

    /** 读取一条 trace（含 spans）。 */
    Optional<TraceWithSpans> readByTraceId(String traceId);

    /** 列出某 session 的 LLM spans（按 startedAt ASC）。 */
    List<LlmSpan> listSpansBySession(String sessionId, Instant since, int limit);

    /**
     * OBS-2 M3 — list spans for a session, narrowed to {@code kinds} when provided, paginated.
     *
     * <p>Used by {@code GET /api/observability/sessions/{id}/spans} after the M3 cut-over.
     * {@code kinds=null} or empty means all kinds (llm + tool + event); the implementation
     * MUST push the limit down to SQL via Pageable, not slice in Java.
     */
    List<LlmSpan> listSpansBySession(String sessionId, Set<String> kinds, Instant since, int limit);

    /** 单 span 详情。 */
    Optional<LlmSpan> readSpan(String spanId);

    record TraceWithSpans(LlmTrace trace, List<LlmSpan> spans) {}

    // ===== OBS-2 M1: 新增方法 =====

    /**
     * OBS-2 M1: 创建 trace stub 行（status='running'），由 AgentLoopEngine 在 rootSpan 创建时同步调用。
     *
     * <p>SQL 语义：{@code INSERT ... ON CONFLICT (trace_id) DO NOTHING}。同步执行，但调用方
     * 须用 try-catch 隔离，DB 故障不能传播主路径。
     */
    void upsertTraceStub(TraceStubRequest request);

    /**
     * OBS-2 M1: 写入 t_llm_span (kind='tool') — 异步执行（{@code llmObservabilityExecutor}）。
     *
     * <p>tool 执行完成后由 AgentLoopEngine 调用，与现有 t_trace_span TOOL_CALL 双写。
     * 失败 → log.warn + drop（与 OBS-1 pattern 一致）。
     */
    void writeToolSpan(ToolSpanWriteRequest request);

    /**
     * OBS-2 M1: 写入 t_llm_span (kind='event') — 异步执行（{@code llmObservabilityExecutor}）。
     *
     * <p>覆盖 4 类 event：ask_user / install_confirm / compact / agent_confirm。
     * 失败 → log.warn + drop。
     */
    void writeEventSpan(EventSpanWriteRequest request);

    /**
     * OBS-2 M1: trace 退出时 finalize 终态 — 异步执行（{@code llmObservabilityExecutor}）。
     *
     * <p>SQL 语义：{@code UPDATE ... WHERE trace_id = ? AND status = 'running'}（幂等保护，
     * terminal 状态不被覆盖）。失败 → log.warn + drop。
     */
    void finalizeTrace(TraceFinalizeRequest request);

    /**
     * OBS-2 M3: 按 trace_id + kind filter 查询 spans（M3 读路径使用）。
     *
     * @param traceId 必传
     * @param kinds   要包含的 kind 集合；null 或空集 → 不过滤（返回全部）
     * @param limit   最大返回行数
     */
    List<LlmSpan> listSpansByTrace(String traceId, Set<String> kinds, int limit);

    /**
     * OBS-3: list traces for a session, ordered by {@code startedAt} ascending, capped at
     * {@code limit}. Used by {@code TraceDescendantsService} to pick the first trace of a
     * descendant child session (child sessions can carry multiple traces if multi-turn;
     * OBS-3 simplification: take the earliest one for the unified tree).
     */
    List<LlmTrace> listTracesBySessionAsc(String sessionId, int limit);

    // ===== request records =====

    /** OBS-2 M1 §B.1.1: trace stub 创建请求。 */
    record TraceStubRequest(
            String traceId,
            String sessionId,
            Long agentId,
            Long userId,
            String agentName,
            Instant startedAt
    ) {
        public TraceStubRequest {
            if (traceId == null || traceId.isBlank()) {
                throw new IllegalArgumentException("traceId is required");
            }
            if (sessionId == null || sessionId.isBlank()) {
                throw new IllegalArgumentException("sessionId is required");
            }
            if (startedAt == null) {
                throw new IllegalArgumentException("startedAt is required");
            }
        }
    }

    /** OBS-2 M1 §B.1.2: tool span 写入请求。 */
    record ToolSpanWriteRequest(
            String spanId,
            String traceId,
            String parentSpanId,
            String sessionId,
            Long agentId,
            String name,
            String toolUseId,
            String inputSummary,
            String outputSummary,
            Instant startedAt,
            Instant endedAt,
            long latencyMs,
            int iterationIndex,
            boolean success,
            String error
    ) {
        public ToolSpanWriteRequest {
            if (spanId == null || spanId.isBlank()) {
                throw new IllegalArgumentException("spanId is required");
            }
            if (traceId == null || traceId.isBlank()) {
                throw new IllegalArgumentException("traceId is required");
            }
            if (sessionId == null || sessionId.isBlank()) {
                throw new IllegalArgumentException("sessionId is required");
            }
        }
    }

    /** OBS-2 M1 §B.1.3: event span 写入请求。 */
    record EventSpanWriteRequest(
            String spanId,
            String traceId,
            String parentSpanId,
            String sessionId,
            Long agentId,
            String eventType,
            String name,
            String inputSummary,
            String outputSummary,
            Instant startedAt,
            Instant endedAt,
            long latencyMs,
            int iterationIndex,
            boolean success,
            String error
    ) {
        public EventSpanWriteRequest {
            if (spanId == null || spanId.isBlank()) {
                throw new IllegalArgumentException("spanId is required");
            }
            if (traceId == null || traceId.isBlank()) {
                throw new IllegalArgumentException("traceId is required");
            }
            if (sessionId == null || sessionId.isBlank()) {
                throw new IllegalArgumentException("sessionId is required");
            }
            if (eventType == null || eventType.isBlank()) {
                throw new IllegalArgumentException("eventType is required");
            }
        }
    }

    /** OBS-2 M1 §B.1.4: trace finalize 请求。 */
    record TraceFinalizeRequest(
            String traceId,
            String status,
            String error,
            long totalDurationMs,
            int toolCallCount,
            int eventCount,
            Instant endedAt
    ) {
        public TraceFinalizeRequest {
            if (traceId == null || traceId.isBlank()) {
                throw new IllegalArgumentException("traceId is required");
            }
            if (status == null || status.isBlank()) {
                throw new IllegalArgumentException("status is required");
            }
        }
    }
}
