package com.skillforge.server.controller.observability.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * Plan §7.2 R2-B1 + R3-WN2 — discriminated by {@code kind="tool"}.
 *
 * <p>{@code subagentSessionId} 仅当 {@code toolName='SubAgent'} 触发时填入，由
 * {@code SubagentSessionResolver} 双源解析（output text regex 主路径 + parentSessionId fallback）。
 * 解析失败时保持 null，前端不渲染跳转链接。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ToolSpanSummaryDto(
        String kind,
        String spanId,
        String traceId,
        String parentSpanId,
        Instant startedAt,
        Instant endedAt,
        long latencyMs,
        String toolName,
        String toolUseId,
        boolean success,
        String error,
        String inputPreview,
        String outputPreview,
        String subagentSessionId
) implements SpanSummaryDto {

    public ToolSpanSummaryDto {
        if (kind == null) kind = "tool";
    }
}
