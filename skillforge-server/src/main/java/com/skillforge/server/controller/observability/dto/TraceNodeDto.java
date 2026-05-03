package com.skillforge.server.controller.observability.dto;

import java.time.Instant;
import java.util.List;

/**
 * OBS-4 M2: one trace node inside a {@link TraceTreeDto}.
 *
 * <p>{@code depth} is computed from {@code t_session.parent_session_id} chain:
 * the trace whose session is the investigation root has depth=0; spawned children
 * are depth=1; grandchildren depth=2; etc. Frontend uses depth for indent + colour
 * banding when rendering the nested waterfall.
 *
 * <p>{@code parentSessionId} is the spawn parent session id (if any) so the FE can
 * group child traces under the spawn point in the parent timeline.
 *
 * <p>{@code spans} contains every span belonging to this trace (kind = llm | tool
 * | event) ordered by startedAt ascending. Span shape mirrors
 * {@code GET /api/traces/{traceId}/spans} so existing FE rendering logic carries
 * over.
 */
public record TraceNodeDto(
        String traceId,
        String sessionId,
        Long agentId,
        String agentName,
        int depth,
        String parentSessionId,
        String status,
        Instant startedAt,
        Instant endedAt,
        long totalDurationMs,
        int llmCallCount,
        int toolCallCount,
        int eventCount,
        int totalInputTokens,
        int totalOutputTokens,
        String error,
        List<TraceSpanDto> spans) {
}
