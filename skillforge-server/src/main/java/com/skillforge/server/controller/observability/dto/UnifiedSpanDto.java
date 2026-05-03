package com.skillforge.server.controller.observability.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * OBS-3 — a single span in the unified cross-trace timeline.
 *
 * <p>Wraps an existing {@link SpanSummaryDto} (sealed: llm / tool / event) and tags it with
 * the depth of its enclosing trace and the {@code parentTraceId} so the frontend can
 * render nested sub-trees with the correct indentation and parent linkage.
 *
 * <ul>
 *   <li>{@code depth = 0} — root trace span, {@code parentTraceId} is null.</li>
 *   <li>{@code depth >= 1} — descendant child trace span; {@code parentTraceId} is the
 *       trace_id one level up the tree (the trace that triggered the child via
 *       TeamCreate / SubAgent).</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UnifiedSpanDto(
        SpanSummaryDto span,
        int depth,
        String parentTraceId
) {}
