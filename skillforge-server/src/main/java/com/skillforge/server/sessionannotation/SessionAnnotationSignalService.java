package com.skillforge.server.sessionannotation;

import com.skillforge.observability.entity.LlmSpanEntity;
import com.skillforge.observability.entity.LlmTraceEntity;
import com.skillforge.observability.repository.LlmSpanRepository;
import com.skillforge.observability.repository.LlmTraceRepository;
import com.skillforge.server.entity.SessionAnnotationEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.SessionAnnotationRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.service.TraceScenarioImportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * PROD-LABEL-CLUSTER V1 (Phase 1.2): signal-stage annotation pipeline.
 *
 * <p>For each production top-level session that finished inside the window:
 * <ol>
 *   <li>load its traces + spans (origin='production' already enforced at the
 *       session row by upstream invariants — child traces inherit)</li>
 *   <li>call {@link TraceScenarioImportService#detectReasons} for the same
 *       6-reason output the SmartImport UI uses</li>
 *   <li>persist one {@code t_session_annotation} row per reason (UNIQUE on
 *       {@code (session_id, annotation_type, annotation_value, source)}
 *       backs idempotent re-runs)</li>
 * </ol>
 *
 * <p>Boundary: this service intentionally does <b>no LLM judgment</b>. Sessions
 * that earn a signal reason are surfaced via {@link #findSessionsNeedingLlmAnnotation(int)}
 * for the session-annotator agent's STEP 2 (AnnotateSessionTool, Phase 1.3) to
 * handle with LLM-derived {@code outcome} + {@code suspect_surface}.
 *
 * <p>Idempotency: the {@code uq_session_annotation} UNIQUE constraint (see
 * {@link SessionAnnotationEntity} {@code @Table} declaration) prevents duplicate
 * rows on re-run; we go through
 * {@link SessionAnnotationRepository#upsertSkipDuplicate} which uses Postgres'
 * native {@code ON CONFLICT DO NOTHING RETURNING id} — conflicts return
 * {@code null} and are silently skipped without aborting the surrounding
 * transaction (V1 W2 fix: the prior {@code saveAndFlush + catch DIVE} loop
 * silently lost subsequent rows once PG marked the tx aborted). This keeps
 * the hourly cron safe even if it re-runs a window that already produced
 * annotations (e.g. after a crash / restart).
 *
 * <p>Window semantics: {@code window} is interpreted as "sessions completed
 * within the last {@code window} duration". Default 1h matches the V75 hourly
 * cron. The window is intentionally a sliding overlap with whatever the prior
 * run covered — UNIQUE dedupe makes that safe.
 */
@Service
public class SessionAnnotationSignalService {

    private static final Logger log = LoggerFactory.getLogger(SessionAnnotationSignalService.class);

    /**
     * Same default as {@link TraceScenarioImportService} (line 35) — when the
     * caller doesn't override {@code minTokens}, anything ≥ 2000 tokens trips
     * the {@code high_token} reason. Kept private so a single value source
     * (this constant) governs the V1 pipeline; configurability lives at the
     * Tool input level, not yaml, to keep the config surface minimal per
     * Phase 1.2 brief.
     */
    private static final int DEFAULT_MIN_TOKENS = 2000;

    /** Tool returns at most this many "needs LLM" sessions per detect call — matches §4.1 STEP 2 cap. */
    static final int DEFAULT_LLM_QUEUE_LIMIT = 10;

    private final SessionRepository sessionRepository;
    private final LlmTraceRepository llmTraceRepository;
    private final LlmSpanRepository llmSpanRepository;
    private final SessionAnnotationRepository sessionAnnotationRepository;

    public SessionAnnotationSignalService(SessionRepository sessionRepository,
                                          LlmTraceRepository llmTraceRepository,
                                          LlmSpanRepository llmSpanRepository,
                                          SessionAnnotationRepository sessionAnnotationRepository) {
        this.sessionRepository = sessionRepository;
        this.llmTraceRepository = llmTraceRepository;
        this.llmSpanRepository = llmSpanRepository;
        this.sessionAnnotationRepository = sessionAnnotationRepository;
    }

    /**
     * Detect signal reasons for production sessions completed within {@code window}
     * and persist one annotation row per (session × reason) into
     * {@code t_session_annotation}.
     *
     * @param window look-back window (e.g. {@code Duration.ofHours(1)}).
     *               Must be positive; non-positive values throw IllegalArgumentException
     *               to avoid silently scanning the entire history.
     * @return total annotation rows written (sum across all sessions × reasons).
     *         A row that conflicts on the UNIQUE constraint counts as 0 (already
     *         existed from prior run).
     */
    @Transactional
    public int detectAndPersist(Duration window) {
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be a positive Duration; got " + window);
        }
        Instant since = Instant.now().minus(window);
        List<SessionEntity> sessions = sessionRepository.findCompletedByOriginSince(
                SessionEntity.ORIGIN_PRODUCTION, since);
        if (sessions.isEmpty()) {
            log.info("[signal] no production sessions completed in window {} since {}", window, since);
            return 0;
        }

        int totalWritten = 0;
        for (SessionEntity session : sessions) {
            try {
                totalWritten += annotateOne(session);
            } catch (Exception e) {
                // Per-session isolation: a single bad session shouldn't fail the whole batch.
                // Logged at WARN so the hourly cron op surface can see it, but the cron keeps
                // running (matches §4.1 CONSTRAINT "If a tool returns an error, log it and
                // proceed; never abort the pipeline").
                log.warn("[signal] sessionId={} annotation failed: {}", session.getId(), e.getMessage(), e);
            }
        }
        log.info("[signal] window={} since={} sessions={} annotationsWritten={}",
                window, since, sessions.size(), totalWritten);
        return totalWritten;
    }

    /**
     * Returns up to {@code limit} sessions that earned ≥ 1 signal annotation and
     * have NOT yet been annotated by the LLM pass (no {@code source='llm'} row).
     * Used by {@link com.skillforge.server.tool.sessionannotation.DetectSignalAnnotationsTool}
     * to forward the queue to STEP 2 of the agent pipeline.
     *
     * <p>Implementation note (V1): we scan recent {@code source='signal'} rows + filter
     * out sessions that already have an {@code source='llm'} row. This is a small-N
     * query — V1 cap is 10 per invocation, and the signal table is hourly-bounded.
     * A native JOIN would be cleaner but is deferred to Phase 1.4 if the volume
     * exceeds 1K rows/hr (well outside any plausible V1 dogfood load).
     */
    @Transactional(readOnly = true)
    public List<SessionNeedingLlmDto> findSessionsNeedingLlmAnnotation(int limit) {
        int capped = Math.max(1, Math.min(limit, DEFAULT_LLM_QUEUE_LIMIT));
        // Pull most-recent signal rows. We over-fetch ~3x then filter, then truncate.
        // Volume in V1: ≤ hundreds of rows/hr × 6 reasons = a few thousand rows;
        // an over-fetch factor of 3 is fine here, and the result is sorted by
        // signal createdAt desc (newest first).
        List<SessionAnnotationEntity> signalRows = sessionAnnotationRepository
                .findRecentByLimit(SessionAnnotationEntity.SOURCE_SIGNAL, capped * 3);
        if (signalRows.isEmpty()) {
            return Collections.emptyList();
        }

        // Preserve insertion order = most-recent-first.
        Map<String, List<String>> reasonsBySession = new LinkedHashMap<>();
        for (SessionAnnotationEntity row : signalRows) {
            reasonsBySession.computeIfAbsent(row.getSessionId(), k -> new ArrayList<>())
                    .add(row.getAnnotationType());
        }

        // Sessions that already have an LLM annotation are excluded so the agent
        // doesn't repeatedly process the same session each hour.
        List<String> sessionIds = new ArrayList<>(reasonsBySession.keySet());
        List<String> withLlm = sessionAnnotationRepository
                .findSessionIdsWithSource(sessionIds, SessionAnnotationEntity.SOURCE_LLM);
        reasonsBySession.keySet().removeAll(withLlm);

        if (reasonsBySession.isEmpty()) {
            return Collections.emptyList();
        }

        // Load minimal session fields to fill the DTO (agentName needed by the agent prompt).
        List<String> remaining = new ArrayList<>(reasonsBySession.keySet());
        Map<String, SessionEntity> byId = sessionRepository.findAllById(remaining).stream()
                .collect(Collectors.toMap(SessionEntity::getId, s -> s, (a, b) -> a));

        List<SessionNeedingLlmDto> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : reasonsBySession.entrySet()) {
            if (out.size() >= capped) break;
            SessionEntity s = byId.get(e.getKey());
            String agentName = (s != null && s.getAgentId() != null)
                    ? "agent#" + s.getAgentId()  // V1: we only have agentId in SessionEntity, not name
                    : null;
            out.add(new SessionNeedingLlmDto(e.getKey(), agentName, List.copyOf(e.getValue())));
        }
        return out;
    }

    /**
     * Inner helper — annotate one session. Returns the number of rows newly written
     * (UNIQUE conflicts count as 0).
     */
    private int annotateOne(SessionEntity session) {
        String sessionId = session.getId();
        // All traces for this session (root + child). detectReasons uses the primary
        // (root) trace's status + the aggregate of all spans.
        List<LlmTraceEntity> traces = llmTraceRepository.findBySessionIdAndOriginOrderByStartedAtDesc(
                sessionId, SessionEntity.ORIGIN_PRODUCTION);
        if (traces.isEmpty()) {
            // No production traces — nothing to signal. Note we don't try to handle
            // the "session completed but traces still ingesting" race here; the
            // next hourly run will pick it up via overlapping window.
            return 0;
        }

        // Pick the root trace = the one whose rootTraceId == its own traceId (or
        // fallback to the most recent if none self-identify; the SQL ORDER BY
        // already favors recent).
        LlmTraceEntity primary = traces.stream()
                .filter(t -> Objects.equals(t.getTraceId(), t.getRootTraceId()))
                .findFirst()
                .orElse(traces.get(0));

        List<String> traceIds = traces.stream().map(LlmTraceEntity::getTraceId).toList();
        List<LlmSpanEntity> spans = llmSpanRepository.findByTraceIdInOrderByStartedAtAsc(traceIds);

        int totalTokens = traces.stream()
                .mapToInt(t -> t.getTotalInputTokens() + t.getTotalOutputTokens())
                .sum();
        int totalToolCalls = traces.stream().mapToInt(LlmTraceEntity::getToolCallCount).sum();
        int totalLlmCalls = (int) spans.stream().filter(sp -> "llm".equals(sp.getKind())).count();

        List<String> reasons = TraceScenarioImportService.detectReasons(
                primary, spans, totalTokens, totalToolCalls, totalLlmCalls, DEFAULT_MIN_TOKENS);

        if (reasons.isEmpty()) {
            return 0;
        }

        int written = 0;
        BigDecimal fullConfidence = new BigDecimal("1.00");
        for (String reason : reasons) {
            // V1 W2 fix: use native ON CONFLICT DO NOTHING to keep the per-row
            // dedup signal without aborting the outer transaction on conflict.
            // null = already-existed (UNIQUE skip); non-null = newly inserted id.
            Long insertedId = sessionAnnotationRepository.upsertSkipDuplicate(
                    sessionId,
                    reason,
                    "true",
                    SessionAnnotationEntity.SOURCE_SIGNAL,
                    fullConfidence,
                    null);
            if (insertedId != null) {
                written++;
            } else {
                log.debug("[signal] sessionId={} reason={} already annotated — skipping",
                        sessionId, reason);
            }
        }
        return written;
    }

    /**
     * Tool-facing DTO for the LLM-annotation queue. Kept here (not in {@code dto/})
     * because it's an internal hand-off type between this service and
     * {@link com.skillforge.server.tool.sessionannotation.DetectSignalAnnotationsTool} —
     * not an external REST contract.
     */
    public record SessionNeedingLlmDto(String sessionId, String agentName, List<String> signalReasons) {}
}
