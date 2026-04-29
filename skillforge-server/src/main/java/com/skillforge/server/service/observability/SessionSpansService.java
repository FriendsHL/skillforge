package com.skillforge.server.service.observability;

import com.skillforge.observability.api.LlmTraceStore;
import com.skillforge.observability.domain.LlmSpan;
import com.skillforge.server.controller.observability.dto.LlmSpanSummaryDto;
import com.skillforge.server.controller.observability.dto.SpanSummaryDto;
import com.skillforge.server.controller.observability.dto.ToolSpanSummaryDto;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.TraceSpanEntity;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.TraceSpanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plan §7 R2-B1 + §10.1 R3-WN2 — 合并 LLM + Tool span 端点的 service 层。
 *
 * <p>W-R3-N2-a 优化：SubAgent fallback 走批量查询（去 N+1）。
 */
@Service
public class SessionSpansService {

    private static final Logger log = LoggerFactory.getLogger(SessionSpansService.class);

    private static final Pattern CHILD_ID_PATTERN =
            Pattern.compile("(?m)^\\s*childSessionId:\\s*([a-f0-9-]{36})\\s*$");
    private static final int PREVIEW_CAP = 4096;

    private final LlmTraceStore traceStore;
    private final TraceSpanRepository traceSpanRepository;
    private final SessionRepository sessionRepository;

    public SessionSpansService(LlmTraceStore traceStore,
                               TraceSpanRepository traceSpanRepository,
                               SessionRepository sessionRepository) {
        this.traceStore = traceStore;
        this.traceSpanRepository = traceSpanRepository;
        this.sessionRepository = sessionRepository;
    }

    /**
     * Plan §6.3 / §7.3 R3-W6 — defensive ownership check at the service layer.
     *
     * <p>Controllers must call {@link com.skillforge.server.controller.observability.ObservabilityOwnershipGuard}
     * before invoking this method; the {@code userId} parameter here is a belt-and-braces
     * guard so that internal callers cannot accidentally bypass tenancy isolation.
     *
     * <p>{@code userId} matches {@code SessionEntity.user_id}; we re-load the session
     * here and short-circuit to an empty list if the principal does not own it
     * (controllers will already have surfaced 403, but defense in depth is cheap).
     */
    @Transactional(readOnly = true)
    public List<SpanSummaryDto> listMergedSpans(String sessionId,
                                                Long userId,
                                                Instant since,
                                                int limit,
                                                Set<String> kinds) {
        if (userId == null) {
            // Defense in depth: never serve spans for a missing user identity.
            log.warn("listMergedSpans called with null userId for session={}", sessionId);
            return List.of();
        }
        Optional<SessionEntity> sessionOpt = sessionRepository.findById(sessionId);
        if (sessionOpt.isEmpty()
                || sessionOpt.get().getUserId() == null
                || !sessionOpt.get().getUserId().equals(userId)) {
            log.warn("listMergedSpans ownership mismatch session={} userId={}", sessionId, userId);
            return List.of();
        }

        Set<String> effectiveKinds = (kinds == null || kinds.isEmpty())
                ? Set.of("llm", "tool") : kinds;
        // Filter out illegal values silently (log warn) so unknown kinds don't surface.
        effectiveKinds = effectiveKinds.stream()
                .filter(k -> "llm".equals(k) || "tool".equals(k))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (effectiveKinds.isEmpty()) {
            log.warn("listMergedSpans: all kinds filtered out, defaulting to [llm,tool]");
            effectiveKinds = Set.of("llm", "tool");
        }

        List<SpanSummaryDto> merged = new ArrayList<>();

        if (effectiveKinds.contains("llm")) {
            List<LlmSpan> llm = traceStore.listSpansBySession(sessionId, since, limit);
            for (LlmSpan s : llm) {
                merged.add(toLlmSummary(s));
            }
        }

        List<TraceSpanEntity> toolSpans = List.of();
        if (effectiveKinds.contains("tool")) {
            toolSpans = since != null
                    ? traceSpanRepository.findBySessionIdAndSpanTypeAndStartTimeGreaterThanEqualOrderByStartTimeAsc(
                            sessionId, "TOOL_CALL", since)
                    : traceSpanRepository.findBySessionIdAndSpanTypeOrderByStartTimeAsc(
                            sessionId, "TOOL_CALL");
        }

        // R3-WN2 + W-R3-N2-a: batch fallback resolution for SubAgent rows whose output
        // text didn't carry the regex-matchable line.
        Map<String, String> fallbackMap = computeSubagentFallback(sessionId, toolSpans);
        for (TraceSpanEntity ts : toolSpans) {
            merged.add(toToolSummary(ts, fallbackMap));
        }

        // Sort by startedAt ASC, then by trace/span id for stability.
        merged.sort((a, b) -> {
            Instant sa = a.startedAt(), sb = b.startedAt();
            if (sa == null && sb == null) return 0;
            if (sa == null) return -1;
            if (sb == null) return 1;
            return sa.compareTo(sb);
        });
        if (merged.size() > limit) {
            return merged.subList(0, limit);
        }
        return merged;
    }

    private Map<String, String> computeSubagentFallback(String parentSessionId,
                                                         List<TraceSpanEntity> toolSpans) {
        Map<String, String> result = new HashMap<>();
        // Identify SubAgent rows whose output text didn't carry the marker.
        List<TraceSpanEntity> needFallback = new ArrayList<>();
        for (TraceSpanEntity ts : toolSpans) {
            if (!"SubAgent".equals(ts.getName())) continue;
            String out = ts.getOutput();
            if (out != null) {
                Matcher m = CHILD_ID_PATTERN.matcher(out);
                if (m.find()) {
                    result.put(ts.getId(), m.group(1));
                    continue;
                }
            }
            needFallback.add(ts);
        }
        if (needFallback.isEmpty()) return result;

        // Batch query: parentSessionId == sessionId, createdAt >= earliest needFallback.startTime.
        Instant earliest = needFallback.stream()
                .map(TraceSpanEntity::getStartTime)
                .filter(java.util.Objects::nonNull)
                .min(Instant::compareTo)
                .orElse(null);
        if (earliest == null) return result;
        LocalDateTime earliestLdt = LocalDateTime.ofInstant(earliest, ZoneId.systemDefault());

        List<SessionEntity> children = sessionRepository
                .findByParentSessionIdInAndCreatedAtGreaterThanEqual(
                        java.util.Collections.singleton(parentSessionId), earliestLdt);
        children.sort(java.util.Comparator.comparing(SessionEntity::getCreatedAt,
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));

        // Greedy assignment: for each needFallback span, pick the earliest child with
        // createdAt >= span.startTime that isn't already assigned.
        Set<String> assigned = new java.util.HashSet<>();
        // Sort needFallback by startTime ascending for deterministic assignment.
        needFallback.sort(java.util.Comparator.comparing(TraceSpanEntity::getStartTime,
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
        for (TraceSpanEntity ts : needFallback) {
            if (ts.getStartTime() == null) continue;
            LocalDateTime tsLdt = LocalDateTime.ofInstant(ts.getStartTime(), ZoneId.systemDefault());
            for (SessionEntity child : children) {
                if (assigned.contains(child.getId())) continue;
                if (child.getCreatedAt() == null || child.getCreatedAt().isBefore(tsLdt)) continue;
                assigned.add(child.getId());
                result.put(ts.getId(), child.getId());
                break;
            }
        }
        return result;
    }

    private LlmSpanSummaryDto toLlmSummary(LlmSpan s) {
        return new LlmSpanSummaryDto(
                "llm", s.spanId(), s.traceId(), s.parentSpanId(),
                s.startedAt(), s.endedAt(), s.latencyMs(),
                s.provider(), s.model(),
                s.inputTokens(), s.outputTokens(),
                s.source().wireValue(),
                s.stream(),
                s.inputBlobRef() != null,
                s.outputBlobRef() != null,
                s.rawSseBlobRef() != null,
                s.blobStatus(),
                s.finishReason(),
                s.error(),
                s.errorType());
    }

    private ToolSpanSummaryDto toToolSummary(TraceSpanEntity e, Map<String, String> fallback) {
        long latency = e.getDurationMs();
        // BE-W4: TOOL_CALL spans currently hang directly under the AGENT_LOOP root,
        // so parentSpanId == traceId is correct under the current architecture.
        // TODO: when TOOL_CALL nests under LLM_CALL (planned), resolve traceId from
        // t_trace_span.trace_id (currently absent column) instead of reusing parentSpanId.
        return new ToolSpanSummaryDto(
                "tool", e.getId(),
                e.getParentSpanId(),  // traceId — see BE-W4 note above
                e.getParentSpanId(),  // parentSpanId
                e.getStartTime(), e.getEndTime(), latency,
                e.getName(), e.getToolUseId(),
                e.isSuccess(), e.getError(),
                preview(e.getInput()), preview(e.getOutput()),
                fallback.get(e.getId()));
    }

    private static String preview(String s) {
        if (s == null) return null;
        return s.length() <= PREVIEW_CAP ? s : s.substring(0, PREVIEW_CAP) + "...";
    }
}
