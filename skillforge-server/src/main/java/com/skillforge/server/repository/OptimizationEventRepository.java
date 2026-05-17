package com.skillforge.server.repository;

import com.skillforge.server.entity.OptimizationEventEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * V3 ATTRIBUTION-AGENT Phase 1.1: JPA access for {@link OptimizationEventEntity}.
 *
 * <p>Phase 1.1 ships only the narrow queries Phase 1.2 (dispatcher) /
 * Phase 1.3 (approval) / Phase 1.4 (dashboard) need. Spec:
 * {@code docs/requirements/active/ATTRIBUTION-AGENT/tech-design.md §3.1 / §3.2}.
 *
 * <p>Tested by {@code OptimizationEventPersistenceIT} (Testcontainers PG —
 * skipped without Docker).
 */
public interface OptimizationEventRepository extends JpaRepository<OptimizationEventEntity, Long> {

    /**
     * Phase 1.4 dashboard Pending Approvals queue: paginated list of events
     * in a given stage, newest first.
     *
     * <p>Backed by {@code idx_optimization_event_stage_time (stage, created_at DESC)}.
     */
    List<OptimizationEventEntity> findByStageOrderByCreatedAtDesc(String stage, Pageable pageable);

    /**
     * Phase 1.2 AttributionDispatcherService 24h cooldown gate (prd.md ratify #2).
     *
     * <p>Returns any event row whose {@code cooldown_expires_at} is still in the
     * future for the given pattern. An empty list ⇒ pattern is eligible for a
     * fresh attribution-curator dispatch; non-empty ⇒ skip pattern.
     *
     * <p>Index note: this query carries two predicates (pattern_id +
     * cooldown_expires_at). PostgreSQL uses
     * {@code idx_optimization_event_pattern(pattern_id, stage)} for the
     * pattern_id equality pass; the partial
     * {@code idx_optimization_event_cooldown} may assist as a bitmap supplement.
     * A dedicated {@code (pattern_id, cooldown_expires_at) WHERE NOT NULL}
     * composite would be optimal — deferred until Phase 1.4 data volume
     * warrants it.
     */
    List<OptimizationEventEntity> findByPatternIdAndCooldownExpiresAtAfter(
            Long patternId, Instant now);

    /**
     * Phase 1.2 / 1.3 cross-check: events for a specific pattern in a specific
     * stage, ordered oldest-first. Used by:
     * <ul>
     *   <li>{@code ProposeOptimizationTool} to find the dispatcher-written
     *       sentinel (Phase 1.3 reviewer fix made the ordering explicit so
     *       {@code .get(0)} is deterministically the oldest sentinel — was
     *       previously a derived-query name with no ORDER BY).</li>
     *   <li>{@code AttributionDispatcherService} Filter 4 alternate path
     *       (currently routed through
     *       {@link #existsByPatternIdAndStageIn} for performance).</li>
     *   <li>Future {@code AttributionApprovalService} cross-checks (e.g.
     *       confirming a proposal hasn't already advanced past
     *       {@code proposal_pending}).</li>
     * </ul>
     *
     * <p>Backed by {@code idx_optimization_event_pattern (pattern_id, stage)};
     * the {@code ORDER BY createdAt ASC} is a small in-memory sort over the
     * filtered rows (typically 1, occasionally 2 if a duplicate sentinel
     * landed during a race).
     */
    @Query("SELECT e FROM OptimizationEventEntity e " +
           "WHERE e.patternId = :patternId AND e.stage = :stage " +
           "ORDER BY e.createdAt ASC")
    List<OptimizationEventEntity> findByPatternIdAndStage(
            @Param("patternId") Long patternId,
            @Param("stage") String stage);

    /**
     * Phase 1.2 reviewer fix (N+1 collapse): single-query check whether any
     * event row for the given pattern is in any of the supplied stages. Used
     * by {@code AttributionDispatcherService} Filter 4 — was previously a
     * {@code for (stage : ACTIVE_STAGES)} loop calling
     * {@link #findByPatternIdAndStage} per stage (worst case ≥6 queries per
     * pattern × 100-row scan = 600 SELECTs).
     *
     * <p>Backed by {@code idx_optimization_event_pattern (pattern_id, stage)};
     * the {@code stage IN} clause uses an index range scan.
     */
    @Query("SELECT COUNT(e) > 0 FROM OptimizationEventEntity e " +
           "WHERE e.patternId = :patternId AND e.stage IN :stages")
    boolean existsByPatternIdAndStageIn(
            @Param("patternId") Long patternId,
            @Param("stages") Collection<String> stages);

    /**
     * Phase 1.4 dashboard "Per-Agent Pending Approvals" filter — the side
     * panel scoped to a single agent's pending proposals.
     *
     * <p>Backed by {@code idx_optimization_event_agent (agent_id, stage)}.
     */
    List<OptimizationEventEntity> findByAgentIdAndStage(Long agentId, String stage);

    /**
     * Phase 1.2 timeline reconstruction: all events for a pattern, oldest
     * first, so the dashboard Timeline view can render the full causal chain.
     */
    List<OptimizationEventEntity> findByPatternIdOrderByCreatedAtAsc(Long patternId);

    /**
     * Phase 1.2 helper: candidate-eligibility scan. Returns patterns that
     * have no event row yet at all. Counterpart to
     * {@link #findByPatternIdAndCooldownExpiresAtAfter} — the dispatcher
     * uses the COUNT form to skip patterns with any active event, then
     * verifies cooldown for the rest.
     */
    @Query("SELECT COUNT(e) FROM OptimizationEventEntity e WHERE e.patternId = :patternId")
    long countByPatternId(@Param("patternId") Long patternId);

    /**
     * Phase 1.4 orphan sentinel cleanup: rows in a given stage that were
     * created before the cutoff. Used by
     * {@code AttributionDispatcherService.cleanupOrphanSentinels} to find
     * {@code dispatch_initiated} sentinels older than the curator-run TTL.
     */
    List<OptimizationEventEntity> findByStageAndCreatedAtBefore(String stage, Instant cutoff);

    /**
     * V3.2 stage-mirror listener: find the optimization event whose A/B run id
     * was just completed. There's at most one such event per A/B run because
     * V3 attribution writes ab_run_id only once (at candidate_ready →
     * ab_running transition).
     */
    List<OptimizationEventEntity> findByAbRunId(Long abRunId);

    /**
     * V3.2 stage-mirror listener: find the optimization event whose candidate
     * prompt version was just promoted. At most one per candidate (each
     * attribution-derived PromptVersionEntity is written exactly once by the
     * approval flow).
     */
    List<OptimizationEventEntity> findByCandidatePromptVersionId(Long candidatePromptVersionId);

    /**
     * V3.2 stage-mirror listener: find the optimization event whose candidate
     * SkillEntity row was just merged/promoted. Useful when V1 SkillDrafts
     * page merges a draft (creates SkillEntity) and the operator wants the V3
     * timeline to follow.
     */
    List<OptimizationEventEntity> findByCandidateSkillId(Long candidateSkillId);

    /**
     * V4 MULTI-SURFACE-FLYWHEEL Phase 1.3 stage-mirror listener: find the
     * optimization event whose candidate {@link com.skillforge.server.entity.BehaviorRuleVersionEntity}
     * row was just promoted. At most one per candidate (each attribution-derived
     * behavior_rule version is written exactly once by
     * {@code AttributionApprovalService.dispatchBehaviorRuleSurface}).
     *
     * <p>Distinct from the prompt/skill counterparts because
     * {@code candidate_behavior_rule_version_id} is VARCHAR(36) (UUID, matches
     * {@code BehaviorRuleVersionEntity.id}) rather than BIGINT — so the
     * V3.2 listener doesn't need the legacy {@code Long.parseLong} skip path.
     */
    List<OptimizationEventEntity> findByCandidateBehaviorRuleVersionId(String candidateBehaviorRuleVersionId);

    /**
     * FLYWHEEL-LOOP-CLOSURE Phase 1.4 (V88 sidecar lookup): used by
     * {@code PromptImproverService.runAbTestAgainst} ephemeral fallback path
     * to walk candidate prompt UUID → originating attribution event → pattern
     * id → pattern session members. The V88 sidecar column
     * {@code candidate_prompt_version_uuid} is written by
     * {@code AttributionApprovalService.dispatchPromptSurface} (Phase 1.2)
     * with the {@code PromptVersionEntity.id} String UUID. At most one event
     * per candidate by construction.
     */
    List<OptimizationEventEntity> findByCandidatePromptVersionUuid(String candidatePromptVersionUuid);

    /**
     * FLYWHEEL-LOOP-CLOSURE Phase 1.4 (V88 sidecar lookup): twin of
     * {@link #findByCandidatePromptVersionUuid(String)} for the skill surface.
     * Read by {@code SkillDraftService.startAbTestFromDraft} ephemeral
     * fallback to recover the {@code patternId} for pattern-member scenario
     * extraction.
     */
    List<OptimizationEventEntity> findByCandidateSkillDraftUuid(String candidateSkillDraftUuid);

    /**
     * Phase 1.4 reviewer fix (W2/W3): unified pageable list with optional
     * {@code stage} / {@code agentId} / {@code surfaceType} filters. Each
     * parameter may be null to skip that dimension; passing all-null returns
     * every event. Returns a {@link Page} so the controller can surface a
     * truthful {@code total} for FE pagination (was previously stream-filtered
     * post-query, breaking pagination math).
     *
     * <p>ORDER BY is fixed to {@code createdAt DESC}; the {@code Pageable}'s
     * sort spec is intentionally ignored (the @Query embeds the sort so plan
     * is stable, and the dashboard timeline view is always newest-first).
     *
     * <p>Indexable: when {@code stage} or {@code agentId} pins down a
     * selective predicate, PostgreSQL hits
     * {@code idx_optimization_event_stage_time} / {@code idx_optimization_event_agent}
     * respectively. Worst case (all-null) does a full sequential scan, which
     * is fine at V3 dogfood data volume.
     */
    @Query(value = "SELECT e FROM OptimizationEventEntity e WHERE " +
                   "(:stage IS NULL OR e.stage = :stage) AND " +
                   "(:agentId IS NULL OR e.agentId = :agentId) AND " +
                   "(:surfaceType IS NULL OR e.surfaceType = :surfaceType) " +
                   "ORDER BY e.createdAt DESC",
           countQuery = "SELECT COUNT(e) FROM OptimizationEventEntity e WHERE " +
                        "(:stage IS NULL OR e.stage = :stage) AND " +
                        "(:agentId IS NULL OR e.agentId = :agentId) AND " +
                        "(:surfaceType IS NULL OR e.surfaceType = :surfaceType)")
    Page<OptimizationEventEntity> findFiltered(
            @Param("stage") String stage,
            @Param("agentId") Long agentId,
            @Param("surfaceType") String surfaceType,
            Pageable pageable);
}
