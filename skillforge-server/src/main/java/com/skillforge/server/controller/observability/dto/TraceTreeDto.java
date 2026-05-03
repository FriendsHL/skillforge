package com.skillforge.server.controller.observability.dto;

import java.util.List;

/**
 * OBS-4 M2: response shape for {@code GET /api/traces/{rootTraceId}/tree}.
 *
 * <p>One TraceTreeDto = one investigation = all traces (across sessions) sharing the
 * same {@code root_trace_id}, plus their spans. Frontend consumes this in M3 to render
 * a unified two-level-collapsible waterfall (parent agent timeline + nested subagent
 * sub-trees inline).
 *
 * <p>Trace ordering: {@code traces} is sorted by {@code started_at} ascending so the
 * UI can render a chronological timeline directly without re-sorting.
 */
public record TraceTreeDto(
        String rootTraceId,
        List<TraceNodeDto> traces) {
}
