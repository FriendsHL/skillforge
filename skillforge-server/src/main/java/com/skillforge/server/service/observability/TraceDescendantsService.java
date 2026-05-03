package com.skillforge.server.service.observability;

import com.skillforge.observability.api.LlmTraceStore;
import com.skillforge.observability.api.LlmTraceStore.TraceWithSpans;
import com.skillforge.observability.domain.LlmSpan;
import com.skillforge.observability.domain.LlmTrace;
import com.skillforge.server.controller.observability.ObservabilityOwnershipGuard;
import com.skillforge.server.controller.observability.dto.DescendantTraceDto;
import com.skillforge.server.controller.observability.dto.EventSpanSummaryDto;
import com.skillforge.server.controller.observability.dto.LlmSpanSummaryDto;
import com.skillforge.server.controller.observability.dto.LlmTraceSummaryDto;
import com.skillforge.server.controller.observability.dto.SpanSummaryDto;
import com.skillforge.server.controller.observability.dto.ToolSpanSummaryDto;
import com.skillforge.server.controller.observability.dto.TraceWithDescendantsDto;
import com.skillforge.server.controller.observability.dto.UnifiedSpanDto;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * OBS-3 — assemble a unified trace tree across session boundaries.
 *
 * <p>Algorithm (see {@code tech-design.md §1.3}):
 * <ol>
 *   <li>Ownership check on the root trace's session.</li>
 *   <li>DFS via {@code t_session.parent_session_id} (children visited in
 *       {@code createdAt ASC}) capped by {@code maxDepth} and {@code maxDescendants}.
 *       A {@code visited} set on session id catches accidental cycles even though
 *       {@code parent_session_id} should be a DAG.</li>
 *   <li>For each descendant, take the earliest trace of that session
 *       ({@link LlmTraceStore#listTracesBySessionAsc}). Resolve the dispatch span in
 *       the parent trace by scanning {@code kind='tool'} spans whose
 *       {@code outputSummary} contains the literal {@code "childSessionId: <uuid>"}
 *       line emitted by {@code SubAgentTool} / {@code TeamCreateTool}; on miss the
 *       dispatch span id is {@code null} and the frontend renders the subtree at
 *       the tail of the parent trace.</li>
 *   <li>Aggregate all spans (parent + descendants) into a single timeline sorted by
 *       {@code startedAt ASC}, tagging each span with its enclosing trace's depth and
 *       {@code parentTraceId}.</li>
 * </ol>
 *
 * <p>Hard limits: {@code maxDepth} clamps DFS recursion (default 3, cap 10);
 * {@code maxDescendants} clamps total descendants (default 20, cap 100);
 * {@link #MAX_SPANS_PER_TRACE} clamps spans fetched per individual trace.
 */
@Service
public class TraceDescendantsService {

    private static final Logger log = LoggerFactory.getLogger(TraceDescendantsService.class);

    /** Hard upper bound on per-trace span fetch (matches the OBS-2 read-path defaults). */
    static final int MAX_SPANS_PER_TRACE = 500;

    /** Defensive ceiling — even if a caller passes max_depth=99, we never recurse deeper. */
    static final int HARD_MAX_DEPTH = 10;

    /** Defensive ceiling on max_descendants. */
    static final int HARD_MAX_DESCENDANTS = 100;

    private static final int PREVIEW_CAP = 4096;

    private final LlmTraceStore traceStore;
    private final SessionRepository sessionRepository;
    private final ObservabilityOwnershipGuard ownershipGuard;
    private final SubagentSessionResolver subagentResolver;

    public TraceDescendantsService(LlmTraceStore traceStore,
                                   SessionRepository sessionRepository,
                                   ObservabilityOwnershipGuard ownershipGuard,
                                   SubagentSessionResolver subagentResolver) {
        this.traceStore = traceStore;
        this.sessionRepository = sessionRepository;
        this.ownershipGuard = ownershipGuard;
        this.subagentResolver = subagentResolver;
    }

    @Transactional(readOnly = true)
    public TraceWithDescendantsDto fetch(String traceId, int maxDepth, int maxDescendants, Long userId) {
        if (traceId == null || traceId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "traceId required");
        }
        int effectiveMaxDepth = Math.min(Math.max(1, maxDepth), HARD_MAX_DEPTH);
        int effectiveMaxDescendants = Math.min(Math.max(0, maxDescendants), HARD_MAX_DESCENDANTS);

        // 1. Fetch root trace.
        TraceWithSpans rootBundle = traceStore.readByTraceId(traceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Trace not found"));
        LlmTrace root = rootBundle.trace();

        // 2. Ownership guard against the root trace's session.
        ownershipGuard.requireOwned(root.sessionId(), userId);

        // 3. DFS find descendants.
        List<DescendantTraceDto> descendants = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        visited.add(root.sessionId());
        boolean truncated = dfsDescendants(root, 1, effectiveMaxDepth, effectiveMaxDescendants,
                visited, descendants);

        // 4. Materialize spans (parent + each descendant).
        List<UnifiedSpanDto> allSpans = new ArrayList<>();
        // Reuse spans loaded with the root trace to avoid a second DB hit.
        for (LlmSpan s : rootBundle.spans()) {
            allSpans.add(new UnifiedSpanDto(toSummary(s), 0, null));
        }
        for (DescendantTraceDto d : descendants) {
            List<LlmSpan> dspans = traceStore.listSpansByTrace(d.traceId(), null, MAX_SPANS_PER_TRACE);
            for (LlmSpan s : dspans) {
                allSpans.add(new UnifiedSpanDto(toSummary(s), d.depth(), d.parentTraceId()));
            }
        }

        // 5. Sort across all traces by startedAt ASC (nulls last for safety).
        allSpans.sort(Comparator.comparing(
                u -> u.span() == null ? null : u.span().startedAt(),
                Comparator.nullsLast(Comparator.naturalOrder())));

        return new TraceWithDescendantsDto(toRootSummary(root), descendants, allSpans, truncated);
    }

    /**
     * @return {@code true} if {@code maxDescendants} cap was hit (truncated)
     */
    private boolean dfsDescendants(LlmTrace currentTrace, int depth, int maxDepth,
                                   int maxDescendants,
                                   Set<String> visited, List<DescendantTraceDto> out) {
        if (depth > maxDepth) return false;
        if (out.size() >= maxDescendants) return true;

        List<SessionEntity> children = sessionRepository
                .findByParentSessionIdOrderByCreatedAtAsc(currentTrace.sessionId());

        for (SessionEntity child : children) {
            if (child.getId() == null) continue;
            if (visited.contains(child.getId())) {
                log.warn("OBS-3 DFS cycle guard hit: child session {} already visited "
                        + "(parent trace {}, depth {})", child.getId(), currentTrace.traceId(), depth);
                continue;
            }
            visited.add(child.getId());

            List<LlmTrace> childTraces = traceStore.listTracesBySessionAsc(child.getId(), 1);
            if (childTraces.isEmpty()) {
                // Child session exists but has no live trace — skip silently. The frontend
                // can still surface it via the existing SubagentJumpLink.
                continue;
            }
            LlmTrace childTrace = childTraces.get(0);

            String parentSpanId = resolveDispatchSpan(currentTrace.traceId(), child.getId());

            DescendantTraceDto d = new DescendantTraceDto(
                    childTrace.traceId(),
                    child.getId(),
                    depth,
                    currentTrace.traceId(),
                    parentSpanId,
                    childTrace.agentName() != null ? childTrace.agentName() : childTrace.rootName(),
                    childTrace.status(),
                    childTrace.totalDurationMs(),
                    childTrace.toolCallCount(),
                    childTrace.eventCount());
            out.add(d);

            if (out.size() >= maxDescendants) return true;

            // Recurse into this child's subtree.
            if (dfsDescendants(childTrace, depth + 1, maxDepth, maxDescendants, visited, out)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Scan tool spans in {@code parentTraceId} whose {@code outputSummary} carries the
     * canonical {@code "childSessionId: <uuid>"} line emitted by
     * {@code SubAgentTool.java:174} / {@code TeamCreateTool.java:140}. Returns the
     * matching span_id, or {@code null} if no match (graceful fallback — the frontend
     * renders the subtree at the tail of the parent trace; no exception leaks).
     */
    String resolveDispatchSpan(String parentTraceId, String childSessionId) {
        if (parentTraceId == null || childSessionId == null) return null;
        try {
            String needle = "childSessionId: " + childSessionId;
            List<LlmSpan> toolSpans = traceStore.listSpansByTrace(
                    parentTraceId, Set.of("tool"), MAX_SPANS_PER_TRACE);
            for (LlmSpan s : toolSpans) {
                String out = s.outputSummary();
                if (out != null && out.contains(needle)) {
                    return s.spanId();
                }
            }
        } catch (Exception e) {
            // Fail-soft — never let dispatch resolution break the unified-tree response.
            log.debug("resolveDispatchSpan swallowed exception: parent={} child={}",
                    parentTraceId, childSessionId, e);
        }
        return null;
    }

    // ===== Mappers =====

    private LlmTraceSummaryDto toRootSummary(LlmTrace t) {
        return new LlmTraceSummaryDto(
                t.traceId(),
                t.sessionId(),
                t.agentId(),
                t.userId(),
                t.rootName(),
                t.agentName(),
                t.status(),
                t.error(),
                t.startedAt(),
                t.endedAt(),
                t.totalDurationMs(),
                t.toolCallCount(),
                t.eventCount(),
                t.totalInputTokens(),
                t.totalOutputTokens(),
                t.totalCostUsd(),
                t.source() != null ? t.source().wireValue() : null);
    }

    private SpanSummaryDto toSummary(LlmSpan s) {
        String kind = s.kind() != null ? s.kind() : "llm";
        return switch (kind) {
            case "llm" -> toLlmSummary(s);
            case "tool" -> toToolSummary(s);
            case "event" -> toEventSummary(s);
            default -> {
                log.debug("OBS-3 skipping unknown span kind={} spanId={}", kind, s.spanId());
                yield toLlmSummary(s); // defensive fallback
            }
        };
    }

    private LlmSpanSummaryDto toLlmSummary(LlmSpan s) {
        return new LlmSpanSummaryDto(
                "llm", s.spanId(), s.traceId(), s.parentSpanId(),
                s.startedAt(), s.endedAt(), s.latencyMs(),
                s.provider(), s.model(),
                s.inputTokens(), s.outputTokens(),
                s.source() != null ? s.source().wireValue() : null,
                s.stream(),
                s.inputBlobRef() != null,
                s.outputBlobRef() != null,
                s.rawSseBlobRef() != null,
                s.blobStatus(),
                s.finishReason(),
                s.error(),
                s.errorType());
    }

    private ToolSpanSummaryDto toToolSummary(LlmSpan s) {
        boolean success = s.error() == null;
        return new ToolSpanSummaryDto(
                "tool", s.spanId(), s.traceId(), s.parentSpanId(),
                s.startedAt(), s.endedAt(), s.latencyMs(),
                s.name(), s.toolUseId(),
                success, s.error(),
                preview(s.inputSummary()), preview(s.outputSummary()),
                subagentResolver.resolve(s));
    }

    private EventSpanSummaryDto toEventSummary(LlmSpan s) {
        boolean success = s.error() == null;
        return new EventSpanSummaryDto(
                "event", s.spanId(), s.traceId(), s.parentSpanId(),
                s.startedAt(), s.endedAt(), s.latencyMs(),
                s.eventType(), s.name(),
                success, s.error(),
                preview(s.inputSummary()), preview(s.outputSummary()));
    }

    private static String preview(String s) {
        if (s == null) return null;
        return s.length() <= PREVIEW_CAP ? s : s.substring(0, PREVIEW_CAP) + "...";
    }

    // Visible for testing — pass-through to the resolver path.
    @SuppressWarnings("unused")
    private static Instant nullsafeStart(LlmSpan s) {
        return s == null ? null : s.startedAt();
    }
}
