package com.skillforge.server.controller.observability.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * OBS-3 — response body for {@code GET /api/traces/{traceId}/with_descendants}.
 *
 * <p>Carries the root trace summary plus DFS-discovered descendant child traces and a
 * single timeline of unified spans (parent + descendants merged, sorted by
 * {@code startedAt} ASC). When {@code truncated == true} the {@code descendants} list was
 * capped at {@code max_descendants}; the frontend may lazy-load deeper subtrees with a
 * second {@code with_descendants} request rooted at the truncated child.
 *
 * <p>Frontend mirror is {@code src/types/observability.ts: TraceWithDescendants}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TraceWithDescendantsDto(
        LlmTraceSummaryDto rootTrace,
        List<DescendantTraceDto> descendants,
        List<UnifiedSpanDto> spans,
        boolean truncated
) {}
