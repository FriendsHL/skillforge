package com.skillforge.server.controller.observability.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * OBS-2 M3 — discriminated by {@code kind="event"}.
 *
 * <p>Covers the four legacy event span types now stored in {@code t_llm_span where kind='event'}:
 * {@code ask_user} / {@code install_confirm} / {@code compact} / {@code agent_confirm}.
 *
 * <p>Frontend mirror is {@code src/types/observability.ts: EventSpanSummary}. Any change here
 * MUST send a SendMessage to Frontend Dev.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventSpanSummaryDto(
        String kind,
        String spanId,
        String traceId,
        String parentSpanId,
        Instant startedAt,
        Instant endedAt,
        long latencyMs,
        String eventType,
        String name,
        boolean success,
        String error,
        String inputPreview,
        String outputPreview
) implements SpanSummaryDto {

    public EventSpanSummaryDto {
        if (kind == null) kind = "event";
    }
}
