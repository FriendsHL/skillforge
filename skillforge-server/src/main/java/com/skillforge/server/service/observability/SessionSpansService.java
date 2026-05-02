package com.skillforge.server.service.observability;

import com.skillforge.observability.api.LlmTraceStore;
import com.skillforge.observability.domain.LlmSpan;
import com.skillforge.server.controller.observability.dto.EventSpanSummaryDto;
import com.skillforge.server.controller.observability.dto.LlmSpanSummaryDto;
import com.skillforge.server.controller.observability.dto.SpanSummaryDto;
import com.skillforge.server.controller.observability.dto.ToolSpanSummaryDto;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * OBS-2 M3 — read path cut-over: {@code GET /api/observability/sessions/{id}/spans}.
 *
 * <p>Pre-OBS-2 this service merged {@code t_llm_span} (LLM kind) with {@code t_trace_span}
 * (TOOL_CALL) and applied an in-memory limit. After M3 the read path is single-table:
 * {@code t_llm_span} now holds llm + tool + event kinds with a single {@code kind} column.
 *
 * <p>An optional {@code traceId} filter narrows the response to one trace (for the per-trace
 * waterfall). The legacy two-table merge logic is gone — kind filtering happens at the
 * SQL layer via {@link LlmTraceStore#listSpansBySession(String, Set, Instant, int)}.
 */
@Service
public class SessionSpansService {

    private static final Logger log = LoggerFactory.getLogger(SessionSpansService.class);

    private static final int PREVIEW_CAP = 4096;
    private static final Set<String> ALLOWED_KINDS = Set.of("llm", "tool", "event");

    private final LlmTraceStore traceStore;
    private final SessionRepository sessionRepository;
    private final SubagentSessionResolver subagentResolver;

    public SessionSpansService(LlmTraceStore traceStore,
                               SessionRepository sessionRepository,
                               SubagentSessionResolver subagentResolver) {
        this.traceStore = traceStore;
        this.sessionRepository = sessionRepository;
        this.subagentResolver = subagentResolver;
    }

    /**
     * Plan §6.3 / §7.3 R3-W6 — defensive ownership check at the service layer.
     *
     * <p>Controllers must call {@link com.skillforge.server.controller.observability.ObservabilityOwnershipGuard}
     * before invoking this method; the {@code userId} parameter here is a belt-and-braces
     * guard so that internal callers cannot accidentally bypass tenancy isolation.
     *
     * <p>OBS-2 M3 — added {@code traceId} (optional). When supplied, only spans belonging to
     * that trace are returned. {@code kinds} now defaults to all three (llm + tool + event).
     */
    @Transactional(readOnly = true)
    public List<SpanSummaryDto> listMergedSpans(String sessionId,
                                                Long userId,
                                                String traceId,
                                                Instant since,
                                                int limit,
                                                Set<String> kinds) {
        if (userId == null) {
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

        // Filter out illegal kinds silently so unknown values don't surface.
        // The default (empty / null kinds) returns all three kinds.
        LinkedHashSet<String> effectiveKinds = new LinkedHashSet<>();
        if (kinds == null || kinds.isEmpty()) {
            effectiveKinds.addAll(ALLOWED_KINDS);
        } else {
            for (String k : kinds) {
                if (k != null && ALLOWED_KINDS.contains(k)) {
                    effectiveKinds.add(k);
                }
            }
        }
        if (effectiveKinds.isEmpty()) {
            log.warn("listMergedSpans: all kinds filtered out, defaulting to [llm,tool,event]");
            effectiveKinds.addAll(ALLOWED_KINDS);
        }

        List<LlmSpan> rows;
        if (traceId != null && !traceId.isBlank()) {
            List<LlmSpan> raw = traceStore.listSpansByTrace(traceId, effectiveKinds, limit);
            // Belt-and-braces: ensure spans returned actually belong to this session
            // (defensive against trace_id collision across sessions, which shouldn't happen
            // for live UUIDv4 but could for legacy data). Build a fresh list — caller may
            // return an immutable List.of(...).
            rows = new ArrayList<>(raw.size());
            for (LlmSpan s : raw) {
                if (s.sessionId() != null && !sessionId.equals(s.sessionId())) continue;
                rows.add(s);
            }
        } else {
            rows = traceStore.listSpansBySession(sessionId, effectiveKinds, since, limit);
        }

        List<SpanSummaryDto> out = new ArrayList<>(rows.size());
        for (LlmSpan s : rows) {
            String kind = s.kind() != null ? s.kind() : "llm";
            switch (kind) {
                case "llm" -> out.add(toLlmSummary(s));
                case "tool" -> out.add(toToolSummary(s));
                case "event" -> out.add(toEventSummary(s));
                default -> log.debug("Skipping unknown span kind={} spanId={}", kind, s.spanId());
            }
        }
        return out;
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
}
