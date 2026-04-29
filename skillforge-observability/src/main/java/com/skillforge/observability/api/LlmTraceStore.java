package com.skillforge.observability.api;

import com.skillforge.observability.domain.LlmSpan;
import com.skillforge.observability.domain.LlmTrace;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * LLM trace 持久化接口。
 *
 * <p>{@link #write} 是 observer 主路径入口；内部需保证：
 * <ol>
 *   <li>upsertTrace（{@code ON CONFLICT (trace_id) DO UPDATE SET …} 累加 + GREATEST）</li>
 *   <li>insertSpan</li>
 * </ol>
 * 在同一事务内执行（plan §3.1 / §4.5）。
 */
public interface LlmTraceStore {

    /** 写入一个 LLM call（trace upsert + span insert，同一事务）。 */
    void write(LlmTraceWriteRequest request);

    /** 读取一条 trace（含 spans）。 */
    Optional<TraceWithSpans> readByTraceId(String traceId);

    /** 列出某 session 的 LLM spans（按 startedAt ASC）。 */
    List<LlmSpan> listSpansBySession(String sessionId, Instant since, int limit);

    /** 单 span 详情。 */
    Optional<LlmSpan> readSpan(String spanId);

    record TraceWithSpans(LlmTrace trace, List<LlmSpan> spans) {}
}
