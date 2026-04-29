package com.skillforge.server.controller.observability.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * Plan §7.2 R2-B1 + R3-WN2 — Tool span 详情。
 *
 * <p>{@code subagentSessionId} 由 {@code SubagentSessionResolver} 在 service 层解析后填入。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ToolSpanDetailDto(
        String spanId,
        String traceId,
        String parentSpanId,
        String sessionId,
        String toolName,
        String toolUseId,
        boolean success,
        String error,
        String input,
        String output,
        Instant startedAt,
        Instant endedAt,
        long latencyMs,
        int iterationIndex,
        String subagentSessionId
) {}
