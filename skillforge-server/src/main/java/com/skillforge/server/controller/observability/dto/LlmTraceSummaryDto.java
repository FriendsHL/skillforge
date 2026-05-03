package com.skillforge.server.controller.observability.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * OBS-3 — slim {@code LlmTrace} summary (no spans). Used as {@code rootTrace} in
 * {@link TraceWithDescendantsDto} where spans are returned separately as a unified
 * cross-trace timeline (see {@link UnifiedSpanDto}).
 *
 * <p>Frontend mirror is {@code src/types/observability.ts: LlmTraceSummary}. Any
 * change here MUST send a SendMessage to the OBS-3 Frontend Dev.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LlmTraceSummaryDto(
        String traceId,
        String sessionId,
        Long agentId,
        Long userId,
        String rootName,
        String agentName,
        String status,
        String error,
        Instant startedAt,
        Instant endedAt,
        long totalDurationMs,
        int toolCallCount,
        int eventCount,
        int totalInputTokens,
        int totalOutputTokens,
        BigDecimal totalCostUsd,
        String source
) {}
