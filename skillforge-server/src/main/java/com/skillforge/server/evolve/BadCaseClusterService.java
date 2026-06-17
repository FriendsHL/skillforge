package com.skillforge.server.evolve;

import com.skillforge.observability.entity.LlmSpanEntity;
import com.skillforge.observability.repository.LlmSpanRepository;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.tool.optreport.LoadErrorSpanBatchTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AUTOEVOLVE-CLOSE-LOOP Phase BC-M2b — clusters an agent's failed tool-call
 * spans and returns ONE representative {@code (sessionId, failingSpanId)} per
 * cluster, so the harvest endpoint can rebuild at most one bad-case scenario per
 * distinct failure mode (not one per occurrence).
 *
 * <p>Deterministic and read-only: it groups by the same {@code (toolName + error
 * signature)} key {@link LoadErrorSpanBatchTool} uses (reusing its
 * {@link LoadErrorSpanBatchTool#errorSignature} masker) over the same session
 * universe (top-level {@code origin='production'} within {@code windowDays}). No
 * LLM, no remedy logic — it only locates representative failures; whether a
 * given representative is harvestable is decided downstream by
 * {@link BadCaseHarvestService} (unsupported tools are skipped there).
 */
@Service
public class BadCaseClusterService {

    private static final Logger log = LoggerFactory.getLogger(BadCaseClusterService.class);

    static final int DEFAULT_WINDOW_DAYS = 30;
    static final int MIN_WINDOW_DAYS = 1;
    static final int MAX_WINDOW_DAYS = 30;
    /** Cap on representative clusters returned (sorted by occurrence count DESC). */
    static final int MAX_CLUSTERS = 50;

    private static final String KIND_TOOL = "tool";

    private final SessionRepository sessionRepository;
    private final LlmSpanRepository spanRepository;

    public BadCaseClusterService(SessionRepository sessionRepository,
                                 LlmSpanRepository spanRepository) {
        this.sessionRepository = sessionRepository;
        this.spanRepository = spanRepository;
    }

    /** One representative failed span per failure cluster. */
    public record RepresentativeSpan(String sessionId, String failingSpanId,
                                     String toolName, int occurrenceCount) {}

    /**
     * Return one representative failed tool span per {@code (toolName + error
     * signature)} cluster for the agent's recent production sessions, ordered by
     * occurrence count DESC (hottest failure first). The representative is the
     * EARLIEST span in its cluster (spans are scanned in time order).
     */
    @Transactional(readOnly = true)
    public List<RepresentativeSpan> representativeSpans(Long agentId, int windowDays) {
        if (agentId == null || agentId <= 0L) {
            return List.of();
        }
        int days = clamp(windowDays, MIN_WINDOW_DAYS, MAX_WINDOW_DAYS);
        Instant windowStart = Instant.now().minus(days, ChronoUnit.DAYS);

        List<String> sessionIds = productionSessionIds(agentId, windowStart);
        if (sessionIds.isEmpty()) {
            return List.of();
        }

        List<LlmSpanEntity> spans = spanRepository.findBySessionIdInOrderByStartedAtAsc(sessionIds);
        // Group by (toolName + error signature); keep the first (earliest) span as
        // the representative and count occurrences. LinkedHashMap preserves first-
        // seen order for stable output before the count sort.
        Map<String, Cluster> byKey = new LinkedHashMap<>();
        for (LlmSpanEntity s : spans) {
            if (!KIND_TOOL.equals(s.getKind())) continue;
            if (s.getError() == null || s.getError().isBlank()) continue;
            if (s.getSpanId() == null || s.getSessionId() == null) continue;
            String signature = LoadErrorSpanBatchTool.errorSignature(
                    s.getName(), s.getErrorType(), s.getError());
            String key = (s.getName() == null ? "?" : s.getName()) + " | " + signature;
            Cluster c = byKey.computeIfAbsent(key, k -> new Cluster(s));
            c.count++;
        }

        List<Cluster> sorted = new ArrayList<>(byKey.values());
        sorted.sort((a, b) -> Integer.compare(b.count, a.count));

        List<RepresentativeSpan> out = new ArrayList<>();
        for (Cluster c : sorted) {
            if (out.size() >= MAX_CLUSTERS) break;
            out.add(new RepresentativeSpan(
                    c.representative.getSessionId(), c.representative.getSpanId(),
                    c.representative.getName(), c.count));
        }
        log.info("BadCaseClusterService: agentId={} windowDays={} sessions={} clusters={}",
                agentId, days, sessionIds.size(), out.size());
        return out;
    }

    /**
     * Top-level production session ids for the agent within the window. Mirrors
     * {@code LoadErrorSpanBatchTool.productionSessionIds} (parent_session_id IS
     * NULL, origin='production', createdAt >= windowStart) — keep in sync.
     */
    private List<String> productionSessionIds(Long agentId, Instant windowStart) {
        List<SessionEntity> all = sessionRepository.findByAgentId(agentId);
        List<String> ids = new ArrayList<>();
        for (SessionEntity s : all) {
            if (s.getParentSessionId() != null) continue;
            if (!SessionEntity.ORIGIN_PRODUCTION.equals(s.getOrigin())) continue;
            Instant created = toInstant(s.getCreatedAt());
            if (created == null || created.isBefore(windowStart)) continue;
            ids.add(s.getId());
        }
        return ids;
    }

    private static Instant toInstant(java.time.LocalDateTime ldt) {
        return ldt == null ? null : ldt.atZone(ZoneId.systemDefault()).toInstant();
    }

    private static int clamp(int v, int lo, int hi) {
        if (v < lo) return lo;
        return Math.min(v, hi);
    }

    /** Mutable accumulator for one failure cluster. */
    private static final class Cluster {
        final LlmSpanEntity representative;
        int count;

        Cluster(LlmSpanEntity representative) {
            this.representative = representative;
        }
    }
}
