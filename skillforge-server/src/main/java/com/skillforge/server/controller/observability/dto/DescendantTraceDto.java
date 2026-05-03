package com.skillforge.server.controller.observability.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * OBS-3 — metadata for a descendant child trace surfaced inside the unified trace tree.
 *
 * <p>{@code parentSpanId} points to the {@code TeamCreate} / {@code SubAgent} tool span in
 * the parent trace whose output carries {@code "  childSessionId: <uuid>\n"}. May be null
 * when the dispatch span cannot be resolved (e.g. legacy SubAgent tool runs that pre-date
 * the canonical output format) — frontend renders these descendant subtrees at the tail
 * of the parent trace.
 *
 * <p>Frontend mirror is {@code src/types/observability.ts: DescendantTraceMeta}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DescendantTraceDto(
        String traceId,
        String sessionId,
        int depth,                  // 1 = direct child, 2 = grandchild, 3 = great-grandchild
        String parentTraceId,       // points to the trace one level up
        String parentSpanId,        // dispatch tool span; nullable
        String agentName,
        String status,              // running / ok / error / cancelled
        long totalDurationMs,
        int toolCallCount,
        int eventCount
) {}
