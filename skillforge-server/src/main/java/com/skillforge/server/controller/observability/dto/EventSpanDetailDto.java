package com.skillforge.server.controller.observability.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * OBS-2 M3 — Event span detail (kind=event).
 *
 * <p>Returned by {@code GET /api/observability/event-spans/{spanId}} after the M3 cut-over.
 * Covers the four legacy event types (ask_user / install_confirm / compact / agent_confirm)
 * now persisted in {@code t_llm_span where kind='event'}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventSpanDetailDto(
        String spanId,
        String traceId,
        String parentSpanId,
        String sessionId,
        String eventType,
        String name,
        boolean success,
        String error,
        String input,
        String output,
        Instant startedAt,
        Instant endedAt,
        long latencyMs,
        int iterationIndex
) {}
