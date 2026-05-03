package com.skillforge.core.engine;

import java.time.Instant;

/**
 * OBS-2 M1 — engine-facing trace lifecycle sink.
 *
 * <p>{@link AgentLoopEngine} 调用本接口写入 OBS-2 M1 引入的 trace lifecycle 行
 * （{@code t_llm_trace} status 与 {@code t_llm_span} kind=tool / kind=event 双写）。
 * core 模块通过此接口与 skillforge-observability 解耦，与 {@link TraceCollector} 同模式。
 *
 * <p>所有方法的实现侧（{@code skillforge-observability.PgLlmTraceStore}）：
 * <ul>
 *   <li>{@link #upsertTraceStub} 同步执行（保证 trace 行存在），但 engine 调用处必须用
 *       try-catch 隔离 DB 故障，避免传播主路径（plan §C.2 R2-B1）。</li>
 *   <li>{@link #writeToolSpan} / {@link #writeEventSpan} / {@link #finalizeTrace}
 *       异步执行（{@code llmObservabilityExecutor}），失败 log.warn + drop。</li>
 *   <li>{@link #finalizeTrace} 必须包含 {@code WHERE status = 'running'} 守卫
 *       让 terminal 状态不被覆盖（幂等）。</li>
 * </ul>
 */
public interface TraceLifecycleSink {

    /**
     * rootSpan 创建时同步调用 — INSERT ON CONFLICT (trace_id) DO NOTHING。
     *
     * <p>OBS-4 §2.3: 加 {@code rootTraceId} 参数 — 首次 INSERT 时写入 t_llm_trace.root_trace_id；
     * ON CONFLICT 时不更新 root_trace_id（INV-2: immutable, 一次写定终身不变）。
     */
    void upsertTraceStub(String traceId,
                         String rootTraceId,
                         String sessionId,
                         Long agentId,
                         Long userId,
                         String agentName,
                         Instant startedAt);

    /** tool 执行完成后异步写入 t_llm_span (kind='tool')。 */
    void writeToolSpan(String spanId,
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
                       String error);

    /** 4 类 event span 异步写入 t_llm_span (kind='event')。 */
    void writeEventSpan(String spanId,
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
                        String error);

    /** trace 退出时异步 finalize 终态（含 status='running' 守卫的幂等 UPDATE）。 */
    void finalizeTrace(String traceId,
                       String status,
                       String error,
                       long totalDurationMs,
                       int toolCallCount,
                       int eventCount,
                       Instant endedAt);
}
