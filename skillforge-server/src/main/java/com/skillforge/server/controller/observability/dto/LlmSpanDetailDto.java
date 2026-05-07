package com.skillforge.server.controller.observability.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Plan §7.2 — LlmSpan 详情 DTO（含 ≤32KB summary + usage + blob meta + reasoning）。
 *
 * <p>R3-WN2: 不含 {@code subagentSessionId}（迁到 ToolSpanDetailDto）。
 *
 * <p>PROMPT-CACHE-MVP Phase 4 r2: a single {@code metadata} map carries
 * cache-related telemetry. Wire keys are <em>snake_case</em> matching the
 * {@code attributes_json} column of {@code t_llm_span} so FE consumers and any future
 * ad-hoc telemetry attribute can be added without bumping the DTO contract again.
 *
 * <p>Currently emitted metadata keys:
 * <ul>
 *   <li>{@code cache_break} — boolean, present only when CacheBreakDetector flagged a break</li>
 *   <li>{@code cache_break_reason} — string, paired with {@code cache_break}</li>
 * </ul>
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
        /** PROMPT-CACHE-MVP Phase 4: Anthropic cache_creation_input_tokens; null when
         *  unavailable (DeepSeek/Qwen/OpenAI/mimo families auto-cache server-side). */
        Integer cacheCreationTokens,
        /** PROMPT-CACHE-MVP Phase 4 r2: snake_case keyed metadata bag (cache_break /
         *  cache_break_reason / future telemetry). Always non-null — empty map when
         *  no flags are set so FE can use `metadata?.foo` without optional-chain
         *  surprises. */
        Map<String, Object> metadata,
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
