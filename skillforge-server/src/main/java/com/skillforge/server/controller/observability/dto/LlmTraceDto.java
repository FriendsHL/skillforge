package com.skillforge.server.controller.observability.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Plan §7 — OBS 视角 trace 详情。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LlmTraceDto(
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
        String source,
        List<LlmSpanSummaryDto> spans
) {}
