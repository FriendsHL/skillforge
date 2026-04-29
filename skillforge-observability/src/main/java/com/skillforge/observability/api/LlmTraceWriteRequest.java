package com.skillforge.observability.api;

import com.skillforge.observability.domain.LlmSpan;
import com.skillforge.observability.domain.LlmTrace;

/**
 * 一次 LLM call 完成后由 observer 提交到 store 的写入包。
 *
 * <p>store 层先 {@code upsertTrace(trace)}（DB 端累加 token / GREATEST endedAt），
 * 再 {@code insertSpan(span)}，全过程在同一 JDBC 事务内。
 */
public record LlmTraceWriteRequest(LlmTrace trace, LlmSpan span) {
    public LlmTraceWriteRequest {
        if (trace == null) throw new IllegalArgumentException("trace is required");
        if (span == null) throw new IllegalArgumentException("span is required");
        if (!trace.traceId().equals(span.traceId())) {
            throw new IllegalArgumentException(
                    "trace.traceId must equal span.traceId: " + trace.traceId() + " vs " + span.traceId());
        }
    }
}
