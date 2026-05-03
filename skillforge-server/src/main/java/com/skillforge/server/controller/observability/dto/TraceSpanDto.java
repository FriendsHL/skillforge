package com.skillforge.server.controller.observability.dto;

import java.time.Instant;

/**
 * OBS-4 M2: one span inside a {@link TraceNodeDto}.
 *
 * <p>Shape mirrors the existing {@code GET /api/traces/{traceId}/spans} payload so
 * the M3 unified waterfall renderer can reuse the same span row component. Fields
 * cover all three kinds (llm / tool / event); kind-specific fields stay null when
 * not applicable (e.g. {@code model} is null for tool/event spans).
 */
public record TraceSpanDto(
        String spanId,
        String parentSpanId,
        String kind,
        String eventType,
        String name,
        String model,
        String inputSummary,
        String outputSummary,
        Instant startedAt,
        Instant endedAt,
        long latencyMs,
        int iterationIndex,
        int inputTokens,
        int outputTokens,
        String status,
        String error) {

    /**
     * Derive {@code status} from the persisted {@code error} column. {@code LlmSpanEntity}
     * has no status column (unlike {@code LlmTraceEntity}); per OBS-2 §M0 a span is "ok"
     * iff its error is null. M3 FE code is expected to consume this derived field instead
     * of recomputing the same predicate.
     */
    public static String deriveStatus(String error) {
        return error == null ? "ok" : "error";
    }
}
