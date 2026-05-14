package com.skillforge.server.canary;

import com.skillforge.server.entity.CanaryRolloutEntity;
import com.skillforge.server.entity.SessionAnnotationEntity;
import com.skillforge.server.repository.CanaryMetricSnapshotRepository;
import com.skillforge.server.repository.CanaryRolloutRepository;
import com.skillforge.server.repository.SessionAnnotationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SKILL-CANARY-ROLLOUT V2 Phase 1.4: hourly aggregation of canary metrics.
 *
 * <p>Called by the {@code metrics-collector} agent's {@code RecomputeMetrics}
 * tool every hour (via the V79-seeded {@code ScheduledTask
 * metrics-collector-hourly}). For each active canary in {@code stage='canary'},
 * this service:
 *
 * <ol>
 *   <li>Reads {@code annotation_type='outcome'} rows from the last {@code window}
 *       (typically 1 hour) — produced by the V1 session-annotator agent.</li>
 *   <li>For each annotated session, looks up its {@code canary_group}
 *       annotation (written at allocation time by {@link CanaryAllocator}) to
 *       classify the session as control or candidate.</li>
 *   <li>Per session, keeps the latest outcome only (LLM agent may write
 *       multiple annotations over time; UNIQUE on
 *       {@code (session_id, annotation_type, annotation_value, source)}
 *       allows different values to coexist).</li>
 *   <li>Aggregates per canary: {@code sample = total}; per brief's classification:
 *       {@code success_count = success + partial_success};
 *       {@code failure_count = failure + cancelled}.</li>
 *   <li>Writes one {@code t_canary_metric_snapshot} row per (canary,
 *       hour_bucket) via native {@code ON CONFLICT DO NOTHING} upsert —
 *       safe under re-run within the same hour AND preserves per-canary
 *       independence in PG (see V1 W2 footgun: {@code save() + catch DIVE}
 *       marks txn aborted, poisoning subsequent canaries).</li>
 *   <li>Triggers {@link CanaryRolloutService#autoRollbackCheck} for each
 *       active canary; per-canary {@link DataAccessException} is caught so
 *       one failure cannot break the rest of the tick.</li>
 * </ol>
 *
 * <p><b>Outcome → success/failure mapping</b> (tech-design §6 + Phase 1.4 brief):
 * <ul>
 *   <li>{@code success} + {@code partial_success} → success_count</li>
 *   <li>{@code failure} + {@code cancelled} → failure_count</li>
 *   <li>{@code sample_size} = sum of all 4 outcome types (every annotated session counts)</li>
 * </ul>
 *
 * <p><b>4-dim quality metrics</b> (quality / efficiency / latency / cost):
 * Phase 1.4 leaves the {@code avg_*} columns null — production eval-task
 * scoring is not yet linked to canary sessions. Phase 1.5 / V3 wires
 * {@code EvalScoreFormula M4_V2} into these.
 *
 * <p><b>Empty windows</b>: even when no outcomes were annotated for a canary
 * in the window, a snapshot row is still written with all zeros so the
 * dashboard can render an empty hour bucket (rather than the row silently
 * missing).
 */
@Service
@Transactional
public class CanaryMetricsService {

    private static final Logger log = LoggerFactory.getLogger(CanaryMetricsService.class);

    /** {@code annotation_type} key for the per-session outcome label. */
    static final String OUTCOME_TYPE = "outcome";
    static final String OUTCOME_SUCCESS = "success";
    static final String OUTCOME_PARTIAL_SUCCESS = "partial_success";
    static final String OUTCOME_FAILURE = "failure";
    static final String OUTCOME_CANCELLED = "cancelled";

    /** Default surface for V2 — must match {@link CanaryRolloutEntity#SURFACE_SKILL}. */
    static final String SURFACE_SKILL = CanaryRolloutEntity.SURFACE_SKILL;
    /** Canary-group annotation value prefix: {@code "skill:<name>"}. */
    static final String CANARY_GROUP_VALUE_PREFIX = SURFACE_SKILL + ":";

    private final CanaryRolloutRepository canaryRepository;
    private final CanaryMetricSnapshotRepository snapshotRepository;
    private final SessionAnnotationRepository annotationRepository;
    private final CanaryRolloutService canaryRolloutService;

    public CanaryMetricsService(CanaryRolloutRepository canaryRepository,
                                CanaryMetricSnapshotRepository snapshotRepository,
                                SessionAnnotationRepository annotationRepository,
                                CanaryRolloutService canaryRolloutService) {
        this.canaryRepository = canaryRepository;
        this.snapshotRepository = snapshotRepository;
        this.annotationRepository = annotationRepository;
        this.canaryRolloutService = canaryRolloutService;
    }

    /**
     * Result of one {@link #recompute(Duration)} invocation. Same shape as the
     * {@code RecomputeMetricsTool} JSON output ({@code window_hours} is set by
     * the tool — the service only knows the {@link Duration}).
     */
    public record RecomputeResult(int activeCanaries,
                                  int snapshotsWritten,
                                  int autoRollbacksTriggered) {}

    /**
     * Run one aggregation pass over the given look-back {@code window}.
     * Idempotent on re-run thanks to the per-(canary, hour) UNIQUE constraint
     * on {@code t_canary_metric_snapshot} + the native ON CONFLICT upsert.
     *
     * <p>The hour bucket is {@code now} truncated to the hour (UTC) — re-running
     * within the same wall-clock hour is a no-op rather than overwriting.
     */
    public RecomputeResult recompute(Duration window) {
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException(
                    "window must be a positive Duration (got " + window + ")");
        }
        Instant now = Instant.now();
        Instant since = now.minus(window);
        Instant bucketAt = now.truncatedTo(ChronoUnit.HOURS);

        List<CanaryRolloutEntity> activeCanaries;
        try {
            activeCanaries = canaryRepository.findByRolloutStage(CanaryRolloutEntity.STAGE_CANARY);
        } catch (DataAccessException e) {
            log.warn("CanaryMetricsService.recompute: failed to load active canaries: {}", e.getMessage());
            return new RecomputeResult(0, 0, 0);
        }
        if (activeCanaries.isEmpty()) {
            log.info("CanaryMetricsService.recompute: no active canaries — nothing to do (window={})", window);
            return new RecomputeResult(0, 0, 0);
        }

        // Pre-index active canaries by skill name → (canaryId, side).
        Map<String, CanaryGroupRef> skillNameIndex = buildSkillNameIndex(activeCanaries);

        // Per-session latest outcome (defensive — agent typically writes one per session).
        Map<String, SessionAnnotationEntity> latestOutcomePerSession = loadLatestOutcomes(since);

        // Bucket outcomes into per-canary aggregates.
        Map<Long, Aggregates> perCanary = new HashMap<>();
        for (SessionAnnotationEntity outcome : latestOutcomePerSession.values()) {
            CanaryGroupRef ref = resolveGroupRef(outcome.getSessionId(), skillNameIndex);
            if (ref == null) {
                continue;
            }
            perCanary.computeIfAbsent(ref.canaryId, k -> new Aggregates())
                    .tally(ref.side, outcome.getAnnotationValue());
        }

        // Persist one snapshot per active canary (always — even when empty —
        // so the dashboard can render empty buckets per brief).
        int snapshotsWritten = 0;
        for (CanaryRolloutEntity canary : activeCanaries) {
            Aggregates agg = perCanary.getOrDefault(canary.getId(), new Aggregates());
            try {
                int inserted = snapshotRepository.upsertSnapshotSkipDuplicate(
                        canary.getId(), bucketAt,
                        agg.controlSamples, agg.controlSuccess, agg.controlFailure,
                        null, null, null, null,
                        agg.candidateSamples, agg.candidateSuccess, agg.candidateFailure,
                        null, null, null, null,
                        agg.failRateRatio());
                if (inserted > 0) {
                    snapshotsWritten++;
                } else {
                    log.debug("CanaryMetricsService.recompute: snapshot already exists for canary={} bucket={} — ON CONFLICT skip",
                            canary.getId(), bucketAt);
                }
            } catch (DataAccessException e) {
                log.warn("CanaryMetricsService.recompute: upsert snapshot failed for canary={}: {}",
                        canary.getId(), e.getMessage());
            }
        }

        // Trigger auto-rollback for every active canary. Per-canary try-catch
        // isolates one failing rollback from blocking the rest of the tick.
        int autoRollbacks = 0;
        for (CanaryRolloutEntity canary : activeCanaries) {
            try {
                if (canaryRolloutService.autoRollbackCheck(canary.getId())) {
                    autoRollbacks++;
                }
            } catch (RuntimeException e) {
                log.warn("CanaryMetricsService.recompute: autoRollbackCheck failed for canary={}: {}",
                        canary.getId(), e.getMessage());
            }
        }

        log.info("CanaryMetricsService.recompute: window={} bucket={} active={} snapshotsWritten={} autoRollbacks={}",
                window, bucketAt, activeCanaries.size(), snapshotsWritten, autoRollbacks);
        return new RecomputeResult(activeCanaries.size(), snapshotsWritten, autoRollbacks);
    }

    // ───────────────────────── helpers ─────────────────────────

    /**
     * Pull every outcome annotation written within the window + collapse to
     * one entry per session (latest by {@code created_at}). The repository
     * query already sorts newest-first, so we just take the first sighting.
     */
    private Map<String, SessionAnnotationEntity> loadLatestOutcomes(Instant since) {
        List<SessionAnnotationEntity> outcomes;
        try {
            outcomes = annotationRepository.findByTypeCreatedSince(OUTCOME_TYPE, since);
        } catch (DataAccessException e) {
            log.warn("CanaryMetricsService.recompute: failed to load outcomes since {}: {}",
                    since, e.getMessage());
            return Map.of();
        }
        Map<String, SessionAnnotationEntity> bySession = new HashMap<>();
        for (SessionAnnotationEntity a : outcomes) {
            bySession.putIfAbsent(a.getSessionId(), a); // newest-first ordering → first hit wins
        }
        return bySession;
    }

    /**
     * Look up which canary + side this session belongs to. Returns null when
     * the session has no canary_group annotation or its pinned skill isn't on
     * any active canary.
     */
    private CanaryGroupRef resolveGroupRef(String sessionId,
                                           Map<String, CanaryGroupRef> skillNameIndex) {
        Optional<String> groupOpt;
        try {
            groupOpt = annotationRepository.findCanaryGroup(sessionId, SURFACE_SKILL);
        } catch (DataAccessException e) {
            log.warn("CanaryMetricsService.resolveGroupRef: findCanaryGroup failed for session={}: {}",
                    sessionId, e.getMessage());
            return null;
        }
        if (groupOpt.isEmpty()) {
            return null;
        }
        String skillName = parseSkillName(groupOpt.get());
        if (skillName == null) {
            return null;
        }
        return skillNameIndex.get(skillName);
    }

    /**
     * Build {@code skillName → (canaryId, side)} index. Each canary contributes
     * 2 entries (baseline → control, candidate → candidate). If two canaries
     * collide on the same skill name (shouldn't happen given
     * {@code uq_canary_active}) the last one wins + we log a warning.
     */
    private Map<String, CanaryGroupRef> buildSkillNameIndex(List<CanaryRolloutEntity> canaries) {
        Map<String, CanaryGroupRef> index = new HashMap<>();
        for (CanaryRolloutEntity c : canaries) {
            CanaryGroupRef priorBaseline = index.put(c.getBaselineSkillName(),
                    new CanaryGroupRef(c.getId(), Side.CONTROL));
            CanaryGroupRef priorCandidate = index.put(c.getCandidateSkillName(),
                    new CanaryGroupRef(c.getId(), Side.CANDIDATE));
            if (priorBaseline != null || priorCandidate != null) {
                log.warn("CanaryMetricsService: skill-name collision among active canaries (baseline='{}' candidate='{}') — canary id={} wins",
                        c.getBaselineSkillName(), c.getCandidateSkillName(), c.getId());
            }
        }
        return index;
    }

    /** Parse {@code "skill:my-name"} → {@code "my-name"}; null on malformed values. */
    static String parseSkillName(String groupValue) {
        if (groupValue == null || !groupValue.startsWith(CANARY_GROUP_VALUE_PREFIX)) {
            return null;
        }
        String name = groupValue.substring(CANARY_GROUP_VALUE_PREFIX.length());
        return name.isEmpty() ? null : name;
    }

    private enum Side { CONTROL, CANDIDATE }

    /** Lightweight (canaryId, side) tuple used as the skill-name index value. */
    private record CanaryGroupRef(Long canaryId, Side side) {}

    /** Mutable per-canary accumulator while walking the outcome list. */
    private static final class Aggregates {
        int controlSamples = 0;
        int controlSuccess = 0;
        int controlFailure = 0;
        int candidateSamples = 0;
        int candidateSuccess = 0;
        int candidateFailure = 0;

        /**
         * Tally outcome into the appropriate bucket. Brief classification:
         * {@code success + partial_success → success_count};
         * {@code failure + cancelled → failure_count}. Any unknown outcome
         * value still increments {@code sample_size} (defensive — agent
         * might emit new values without code update).
         */
        void tally(Side side, String outcomeValue) {
            switch (side) {
                case CONTROL -> {
                    controlSamples++;
                    if (isSuccessClass(outcomeValue)) controlSuccess++;
                    else if (isFailureClass(outcomeValue)) controlFailure++;
                }
                case CANDIDATE -> {
                    candidateSamples++;
                    if (isSuccessClass(outcomeValue)) candidateSuccess++;
                    else if (isFailureClass(outcomeValue)) candidateFailure++;
                }
            }
        }

        private static boolean isSuccessClass(String v) {
            return OUTCOME_SUCCESS.equals(v) || OUTCOME_PARTIAL_SUCCESS.equals(v);
        }

        private static boolean isFailureClass(String v) {
            return OUTCOME_FAILURE.equals(v) || OUTCOME_CANCELLED.equals(v);
        }

        /**
         * Compute the candidate/control failure-rate ratio for the snapshot
         * row. Returns null when either side has zero samples or control has
         * zero failures — the snapshot column allows null and
         * {@code autoRollbackCheck} short-circuits in those degenerate cases.
         */
        BigDecimal failRateRatio() {
            if (controlSamples == 0 || candidateSamples == 0) {
                return null;
            }
            double controlRate = (double) controlFailure / controlSamples;
            double candidateRate = (double) candidateFailure / candidateSamples;
            if (controlRate <= 0.0) {
                return null;
            }
            return BigDecimal.valueOf(candidateRate / controlRate)
                    .setScale(3, RoundingMode.HALF_UP);
        }
    }
}
