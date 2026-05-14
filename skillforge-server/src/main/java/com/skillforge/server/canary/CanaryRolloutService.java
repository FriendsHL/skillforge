package com.skillforge.server.canary;

import com.skillforge.server.entity.CanaryMetricSnapshotEntity;
import com.skillforge.server.entity.CanaryRolloutEntity;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.improve.SkillAbEvalService;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.CanaryMetricSnapshotRepository;
import com.skillforge.server.repository.CanaryRolloutRepository;
import com.skillforge.server.repository.SkillRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * SKILL-CANARY-ROLLOUT V2 Phase 1.3: lifecycle operations on
 * {@link CanaryRolloutEntity}.
 *
 * <p>Five lifecycle methods (tech-design §3.2):
 *
 * <ul>
 *   <li>{@link #startCanary} — create a new {@code stage='canary'} row +
 *       transition the candidate {@link SkillEntity} into {@code rollout_stage='canary'};
 *       enforces uniqueness up-front + validates agent & both skill rows exist.</li>
 *   <li>{@link #stepUp} — raise {@code rollout_percentage} (strictly monotonic)
 *       on both the rollout row AND the candidate {@link SkillEntity} so the
 *       two tables stay in sync.</li>
 *   <li>{@link #publish} — terminal promote: pct=100, stage='production',
 *       decision='promoted'. Also flips the candidate skill to enabled and the
 *       baseline to disabled via {@link SkillAbEvalService#promoteCandidate}
 *       (re-using the V64 partial-unique-safe ordering).</li>
 *   <li>{@link #rollback} — terminal: pct=0, stage='rolled_back'. Candidate
 *       skill row gets {@code rollout_stage='rolled_back'}; the baseline is
 *       left untouched (it was never disabled in the first place).</li>
 *   <li>{@link #autoRollbackCheck} — sum lifetime snapshots + decide. Returns
 *       {@code false} until Phase 1.4's {@code ProdMetricsCollector} starts
 *       writing rows — the empty-snapshot case is handled gracefully.</li>
 * </ul>
 *
 * <p><b>Locking</b>: all mutating methods are class-level {@code @Transactional};
 * the read-only listers / find-by-id methods override with
 * {@code readOnly = true} for query optimization.
 *
 * <p><b>Phase scope</b>: this class does NOT compute metric snapshots — that
 * belongs to Phase 1.4 {@code CanaryMetricsService.recompute}. It only reads
 * pre-existing snapshots and decides.
 */
@Service
@Transactional
public class CanaryRolloutService {

    private static final Logger log = LoggerFactory.getLogger(CanaryRolloutService.class);

    /** Tech-design §6 auto-rollback thresholds. */
    static final int AUTO_ROLLBACK_MIN_CANDIDATE_SAMPLES = 50;
    static final double AUTO_ROLLBACK_FAIL_RATIO_THRESHOLD = 1.5;

    private final CanaryRolloutRepository canaryRepository;
    private final CanaryMetricSnapshotRepository snapshotRepository;
    private final SkillRepository skillRepository;
    private final AgentRepository agentRepository;
    private final SkillAbEvalService skillAbEvalService;

    public CanaryRolloutService(CanaryRolloutRepository canaryRepository,
                                CanaryMetricSnapshotRepository snapshotRepository,
                                SkillRepository skillRepository,
                                AgentRepository agentRepository,
                                SkillAbEvalService skillAbEvalService) {
        this.canaryRepository = canaryRepository;
        this.snapshotRepository = snapshotRepository;
        this.skillRepository = skillRepository;
        this.agentRepository = agentRepository;
        this.skillAbEvalService = skillAbEvalService;
    }

    /**
     * Create a new active canary rollout + transition the candidate skill row.
     *
     * <p>Validates 5 invariants up-front:
     * <ol>
     *   <li>{@code percentage} in [0, 100]</li>
     *   <li>{@code agentId} resolves to an existing {@code t_agent} row</li>
     *   <li>{@code surfaceType} is currently only {@code 'skill'} (V2 scope)</li>
     *   <li>Both {@code baselineSkillName} and {@code candidateSkillName} resolve to
     *       existing {@code t_skill} rows and differ from each other</li>
     *   <li>No active canary already exists for (agent, surface) — the {@code uq_canary_active}
     *       partial UNIQUE INDEX (Postgres-only) is the DB-layer safety net; the
     *       application-side pre-check gives a clean {@code 409} + descriptive
     *       message on H2 unit tests.</li>
     * </ol>
     */
    public CanaryRolloutEntity startCanary(Long agentId,
                                           String surfaceType,
                                           String baselineSkillName,
                                           String candidateSkillName,
                                           int percentage) {
        if (agentId == null) {
            throw new IllegalArgumentException("agentId is required");
        }
        if (percentage < 0 || percentage > 100) {
            throw new IllegalArgumentException("percentage must be in [0, 100], got " + percentage);
        }
        String surface = normalizeSurfaceType(surfaceType);
        if (baselineSkillName == null || baselineSkillName.isBlank()) {
            throw new IllegalArgumentException("baselineSkillName is required");
        }
        if (candidateSkillName == null || candidateSkillName.isBlank()) {
            throw new IllegalArgumentException("candidateSkillName is required");
        }
        if (baselineSkillName.equals(candidateSkillName)) {
            throw new IllegalArgumentException(
                    "baselineSkillName must differ from candidateSkillName");
        }
        if (!agentRepository.existsById(agentId)) {
            throw new IllegalArgumentException("Agent not found: id=" + agentId);
        }
        // findByName tolerates a single enabled row; multi-row collision (rare —
        // V64 partial unique prevents enabled dups, disabled dups are possible
        // but uncommon for fresh canary candidates) bubbles as IncorrectResultSize.
        SkillEntity baselineSkill = skillRepository.findByName(baselineSkillName)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Baseline skill not found: " + baselineSkillName));
        SkillEntity candidateSkill = skillRepository.findByName(candidateSkillName)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Candidate skill not found: " + candidateSkillName));

        Optional<CanaryRolloutEntity> existing = canaryRepository
                .findActiveCanaryByAgentAndSurface(agentId, surface);
        if (existing.isPresent()) {
            throw new CanaryStateException(
                    "Agent " + agentId + " already has an active canary (id=" + existing.get().getId()
                            + ", baseline=" + existing.get().getBaselineSkillName() + ")");
        }

        CanaryRolloutEntity entity = new CanaryRolloutEntity();
        entity.setSurfaceType(surface);
        entity.setAgentId(agentId);
        entity.setBaselineSkillName(baselineSkillName);
        entity.setCandidateSkillName(candidateSkillName);
        entity.setRolloutStage(CanaryRolloutEntity.STAGE_CANARY);
        entity.setRolloutPercentage(percentage);
        Instant now = Instant.now();
        entity.setStartedAt(now);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        CanaryRolloutEntity saved = canaryRepository.save(entity);

        // Sync candidate SkillEntity so the row-level rollout columns mirror the
        // canary row. Baseline is intentionally left at its current state
        // (typically 'production' / 100) — it remains the default path until
        // publish() flips both.
        candidateSkill.setRolloutStage(CanaryRolloutEntity.STAGE_CANARY);
        candidateSkill.setRolloutPercentage(percentage);
        skillRepository.save(candidateSkill);
        // Reference baselineSkill to silence "unused" warnings while keeping the
        // existence check in scope — also lets a future iteration record the
        // pre-canary baseline state if needed.
        log.info("CanaryRolloutService: startCanary id={} agent={} surface={} baseline='{}'(id={}) candidate='{}'(id={}) pct={}",
                saved.getId(), agentId, surface, baselineSkillName, baselineSkill.getId(),
                candidateSkillName, candidateSkill.getId(), percentage);
        return saved;
    }

    /**
     * Increase {@code rollout_percentage} on an active canary. Must be a strict
     * increase — step-down is not supported here (use {@link #rollback} for an
     * explicit reset to 0%). Syncs the candidate SkillEntity's
     * {@code rollout_percentage} so the per-row column tracks the canary row.
     */
    public CanaryRolloutEntity stepUp(Long canaryId, int newPercentage) {
        if (newPercentage < 0 || newPercentage > 100) {
            throw new IllegalArgumentException("percentage must be in [0, 100], got " + newPercentage);
        }
        CanaryRolloutEntity c = canaryRepository.findById(canaryId)
                .orElseThrow(() -> new NoSuchElementException("Canary not found: id=" + canaryId));
        if (!CanaryRolloutEntity.STAGE_CANARY.equals(c.getRolloutStage())) {
            throw new CanaryStateException(
                    "Cannot step up canary id=" + canaryId + " — stage is " + c.getRolloutStage());
        }
        int current = c.getRolloutPercentage() == null ? 0 : c.getRolloutPercentage();
        if (newPercentage <= current) {
            throw new IllegalArgumentException(
                    "stepUp must strictly increase pct (current=" + current + ", requested=" + newPercentage + ")");
        }
        c.setRolloutPercentage(newPercentage);
        c.setUpdatedAt(Instant.now());
        CanaryRolloutEntity saved = canaryRepository.save(c);

        // Sync candidate SkillEntity (best-effort; if missing — operator may have
        // deleted the row — log and continue: the canary row is the source of truth).
        skillRepository.findByName(c.getCandidateSkillName()).ifPresentOrElse(skill -> {
            skill.setRolloutPercentage(newPercentage);
            skillRepository.save(skill);
        }, () -> log.warn("CanaryRolloutService.stepUp: candidate skill '{}' missing from SkillRepository — canary row updated, skill row skip",
                c.getCandidateSkillName()));

        log.info("CanaryRolloutService: stepUp id={} pct {} -> {}", canaryId, current, newPercentage);
        return saved;
    }

    /**
     * Terminal promote: pct=100, stage='production', decision='promoted'.
     *
     * <p>Three mutations in one transaction:
     * <ol>
     *   <li>{@code t_canary_rollout} row → stage='production', pct=100,
     *       decision='promoted', last_decision_at=now.</li>
     *   <li>Candidate {@link SkillEntity} → rollout_stage='production', pct=100.
     *       Baseline → rollout_stage='disabled'.</li>
     *   <li>{@link SkillAbEvalService#promoteCandidate} — re-uses the existing
     *       V64-partial-unique-safe ordering (disable parent + flush, enable
     *       candidate, re-register in SkillRegistry). Backward-compat with the
     *       pre-V2 promote pipeline so existing dashboard tooling keeps working.</li>
     * </ol>
     *
     * <p><b>Phase 1.3 r1 code-review W2 (followup nit)</b>: if the SkillEntity sync
     * succeeds but {@code promoteCandidate} throws, the canary row is already
     * flipped to {@code production} — the operator sees a "published" canary
     * with the SkillRegistry not yet re-registered. Phase 1.5 FE work should
     * revisit this: either add a {@code publish_failed} terminal state or
     * propagate the exception so the dashboard renders an actionable error.
     * For Phase 1.3 we accept the partial state because the existing
     * promoteCandidate path is well-tested in {@code SkillAbEvalService} and
     * realistic failure modes (DB lost connection) already break the whole
     * transaction so this branch is rare in practice.
     */
    public CanaryRolloutEntity publish(Long canaryId) {
        CanaryRolloutEntity c = canaryRepository.findById(canaryId)
                .orElseThrow(() -> new NoSuchElementException("Canary not found: id=" + canaryId));
        if (!CanaryRolloutEntity.STAGE_CANARY.equals(c.getRolloutStage())) {
            throw new CanaryStateException(
                    "Cannot publish canary id=" + canaryId + " — stage is " + c.getRolloutStage());
        }
        c.setRolloutPercentage(100);
        c.setRolloutStage(CanaryRolloutEntity.STAGE_PRODUCTION);
        c.setDecision(CanaryRolloutEntity.DECISION_PROMOTED);
        Instant now = Instant.now();
        c.setLastDecisionAt(now);
        c.setUpdatedAt(now);
        CanaryRolloutEntity saved = canaryRepository.save(c);

        // SkillEntity sync: candidate→production/100, baseline→disabled.
        Optional<SkillEntity> candidateOpt = skillRepository.findByName(c.getCandidateSkillName());
        Optional<SkillEntity> baselineOpt = skillRepository.findByName(c.getBaselineSkillName());

        baselineOpt.ifPresentOrElse(baseline -> {
            baseline.setRolloutStage(CanaryRolloutEntity.STAGE_DISABLED);
            skillRepository.save(baseline);
        }, () -> log.warn("CanaryRolloutService.publish: baseline skill '{}' missing — canary row promoted, baseline skill row skip",
                c.getBaselineSkillName()));

        candidateOpt.ifPresentOrElse(candidate -> {
            candidate.setRolloutStage(CanaryRolloutEntity.STAGE_PRODUCTION);
            candidate.setRolloutPercentage(100);
            skillRepository.save(candidate);
            // Re-use existing promote path so SkillRegistry stays in sync and the
            // V64 partial-unique enabled-flag swap happens correctly.
            try {
                skillAbEvalService.promoteCandidate(candidate);
            } catch (RuntimeException e) {
                log.warn("CanaryRolloutService.publish: promoteCandidate threw for skill '{}' id={}: {}",
                        candidate.getName(), candidate.getId(), e.getMessage());
                throw e;
            }
        }, () -> log.warn("CanaryRolloutService.publish: candidate skill '{}' missing — promote skipped",
                c.getCandidateSkillName()));

        log.info("CanaryRolloutService: publish id={} → production (candidate='{}', baseline='{}' disabled)",
                canaryId, c.getCandidateSkillName(), c.getBaselineSkillName());
        return saved;
    }

    /**
     * Terminal rollback: pct=0, stage='rolled_back', decision='rolled_back'.
     *
     * <p>Mutations:
     * <ol>
     *   <li>{@code t_canary_rollout} row → stage='rolled_back', pct=0, decision='rolled_back'.</li>
     *   <li>Candidate {@link SkillEntity} → rollout_stage='rolled_back', pct=0.
     *       Baseline is left untouched — it was never disabled, so reverting
     *       just means future allocations fall back to baseline (which is what
     *       {@code CanaryAllocator} already does when no active canary exists).</li>
     * </ol>
     *
     * <p>{@code reason} is currently only logged — a future migration will add
     * a {@code rollback_reason} column for dashboard visibility.
     */
    public CanaryRolloutEntity rollback(Long canaryId, String reason) {
        CanaryRolloutEntity c = canaryRepository.findById(canaryId)
                .orElseThrow(() -> new NoSuchElementException("Canary not found: id=" + canaryId));
        if (!CanaryRolloutEntity.STAGE_CANARY.equals(c.getRolloutStage())) {
            throw new CanaryStateException(
                    "Cannot rollback canary id=" + canaryId + " — stage is " + c.getRolloutStage());
        }
        c.setRolloutPercentage(0);
        c.setRolloutStage(CanaryRolloutEntity.STAGE_ROLLED_BACK);
        c.setDecision(CanaryRolloutEntity.DECISION_ROLLED_BACK);
        Instant now = Instant.now();
        c.setLastDecisionAt(now);
        c.setUpdatedAt(now);
        CanaryRolloutEntity saved = canaryRepository.save(c);

        skillRepository.findByName(c.getCandidateSkillName()).ifPresentOrElse(skill -> {
            skill.setRolloutStage(CanaryRolloutEntity.STAGE_ROLLED_BACK);
            skill.setRolloutPercentage(0);
            skillRepository.save(skill);
        }, () -> log.warn("CanaryRolloutService.rollback: candidate skill '{}' missing — canary row rolled back, skill row skip",
                c.getCandidateSkillName()));

        log.info("CanaryRolloutService: rollback id={} reason='{}'", canaryId, reason);
        return saved;
    }

    /**
     * Sum lifetime snapshots for the canary and trigger {@link #rollback}
     * when the candidate is dragging worse than the threshold ratio.
     *
     * <p>{@code t_canary_metric_snapshot} rows are written by Phase 1.4
     * {@code ProdMetricsCollector} — until that ships this method is a no-op
     * (empty snapshot list → candidateSamples=0 → returns false). Safe to call
     * speculatively from Phase 1.3 dashboards.
     *
     * @return {@code true} if rollback was triggered; {@code false} for "stage
     *         not canary" / "not enough samples" / "control rate is zero
     *         (cannot compute ratio)" / "ratio under threshold".
     */
    public boolean autoRollbackCheck(Long canaryId) {
        CanaryRolloutEntity c = canaryRepository.findById(canaryId).orElse(null);
        if (c == null || !CanaryRolloutEntity.STAGE_CANARY.equals(c.getRolloutStage())) {
            return false;
        }

        List<CanaryMetricSnapshotEntity> snapshots = snapshotRepository.findAllByCanaryId(canaryId);
        long candidateSamples = 0L;
        long candidateFailures = 0L;
        long controlSamples = 0L;
        long controlFailures = 0L;
        for (CanaryMetricSnapshotEntity s : snapshots) {
            candidateSamples += zeroIfNull(s.getCandidateSampleSize());
            candidateFailures += zeroIfNull(s.getCandidateFailureCount());
            controlSamples += zeroIfNull(s.getControlSampleSize());
            controlFailures += zeroIfNull(s.getControlFailureCount());
        }

        if (candidateSamples < AUTO_ROLLBACK_MIN_CANDIDATE_SAMPLES) {
            return false;
        }
        if (controlSamples == 0L) {
            // No control traffic — can't compute ratio. Conservative no-op.
            return false;
        }
        double candidateRate = (double) candidateFailures / candidateSamples;
        double controlRate = (double) controlFailures / controlSamples;
        if (controlRate <= 0.0) {
            // Control perfect → infinity ratio would flap on first candidate
            // failure. Be lenient: require non-trivial control failure rate
            // before letting the ratio kick in.
            return false;
        }
        double ratio = candidateRate / controlRate;
        if (ratio > AUTO_ROLLBACK_FAIL_RATIO_THRESHOLD) {
            String reason = String.format(
                    "auto: candidateFailRate=%.4f controlFailRate=%.4f ratio=%.3f candidateSamples=%d",
                    candidateRate, controlRate, ratio, candidateSamples);
            rollback(canaryId, reason);
            return true;
        }
        return false;
    }

    /**
     * List rollouts for an agent + surface, optionally filtered by stage.
     * Backs the {@code GET /api/canary/rollouts?agentId=&surfaceType=&stage=}
     * dashboard endpoint.
     */
    @Transactional(readOnly = true)
    public List<CanaryRolloutEntity> listByAgent(Long agentId, String surfaceType, String stage) {
        if (agentId == null) {
            throw new IllegalArgumentException("agentId is required");
        }
        String surface = normalizeSurfaceType(surfaceType);
        List<CanaryRolloutEntity> all = canaryRepository.findByAgentIdAndSurfaceType(agentId, surface);
        if (stage == null || stage.isBlank()) {
            return all;
        }
        return all.stream().filter(r -> stage.equals(r.getRolloutStage())).toList();
    }

    /** Detail-endpoint backing — throws {@link NoSuchElementException} on miss. */
    @Transactional(readOnly = true)
    public CanaryRolloutEntity findById(Long canaryId) {
        return canaryRepository.findById(canaryId)
                .orElseThrow(() -> new NoSuchElementException("Canary not found: id=" + canaryId));
    }

    /**
     * Metrics-endpoint backing — last N hourly snapshots for the given canary
     * (newest first). Used by Phase 1.5 {@code CanaryPanel} to draw the rolling
     * comparison chart.
     */
    @Transactional(readOnly = true)
    public List<CanaryMetricSnapshotEntity> findMetricSnapshots(Long canaryId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        // 404 if the parent rollout doesn't exist — keeps the metrics surface
        // honest (no silently empty array for a non-existent canary).
        if (!canaryRepository.existsById(canaryId)) {
            throw new NoSuchElementException("Canary not found: id=" + canaryId);
        }
        return snapshotRepository.findByCanaryIdOrderByBucketAtDesc(
                canaryId, org.springframework.data.domain.PageRequest.of(0, limit));
    }

    private String normalizeSurfaceType(String surfaceType) {
        String surface = surfaceType == null || surfaceType.isBlank()
                ? CanaryRolloutEntity.SURFACE_SKILL
                : surfaceType;
        if (!CanaryRolloutEntity.SURFACE_SKILL.equals(surface)) {
            // V2 ratify: only 'skill' surface is implemented. Other values
            // (prompt / behavior_rule) are reserved for V3+ and explicitly
            // rejected so callers get a clear signal instead of a silent miss.
            throw new IllegalArgumentException(
                    "surfaceType must be 'skill' (got '" + surfaceType + "'); other surfaces are V3+");
        }
        return surface;
    }

    private static long zeroIfNull(Integer v) {
        return v == null ? 0L : v.longValue();
    }
}
