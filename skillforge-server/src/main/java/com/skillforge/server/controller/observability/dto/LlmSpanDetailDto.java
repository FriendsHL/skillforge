package com.skillforge.server.controller.observability.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Plan §7.2 — LlmSpan 详情 DTO（含 ≤32KB summary + usage + blob meta + reasoning）。
 *
 * <p>R3-WN2: 不含 {@code subagentSessionId}（迁到 ToolSpanDetailDto）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LlmSpanDetailDto(
        String spanId,
        String traceId,
        String parentSpanId,
        String sessionId,
        String provider,
        String model,
        int iterationIndex,
        boolean stream,
        String inputSummary,
        String outputSummary,
        Integer cacheReadTokens,
        Object usage,
        BigDecimal costUsd,
        long latencyMs,
        Instant startedAt,
        Instant endedAt,
        String finishReason,
        String requestId,
        String reasoningContent,
        String error,
        String errorType,
        String source,
        String blobStatus,
        BlobMetaDto blobs
) {}
