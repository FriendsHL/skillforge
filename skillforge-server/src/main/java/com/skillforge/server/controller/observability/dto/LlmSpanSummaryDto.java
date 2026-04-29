package com.skillforge.server.controller.observability.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * Plan §7.2 R3-WN2 — discriminated by {@code kind="llm"}.
 *
 * <p>NOTE：{@code subagentSessionId} 已显式从此 DTO 删除（plan §7.2 R3-WN2）。SubAgent
 * 在 codebase 里就是 TOOL_CALL span（{@code AgentLoopEngine.java:948} +
 * {@code SubAgentTool.java:51}），跳转字段在 {@link ToolSpanSummaryDto} 上。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LlmSpanSummaryDto(
        String kind,
        String spanId,
        String traceId,
        String parentSpanId,
        Instant startedAt,
        Instant endedAt,
        long latencyMs,
        String provider,
        String model,
        int inputTokens,
        int outputTokens,
        String source,
        boolean stream,
        boolean hasRawRequest,
        boolean hasRawResponse,
        boolean hasRawSse,
        String blobStatus,
        String finishReason,
        String error,
        String errorType
) implements SpanSummaryDto {

    public LlmSpanSummaryDto {
        if (kind == null) kind = "llm";
    }
}
