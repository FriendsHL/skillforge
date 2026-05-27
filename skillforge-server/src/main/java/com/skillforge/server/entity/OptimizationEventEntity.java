package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * V3 ATTRIBUTION-AGENT: one row per stage transition of an optimization attempt
 * for a V1 session pattern (pattern → proposal → candidate → A/B → canary →
 * final stage).
 *
 * <p>Backed by V80 {@code t_optimization_event}. Spec: see
 * {@code docs/requirements/active/ATTRIBUTION-AGENT/tech-design.md §2} and
 * {@code prd.md §功能需求 §1}.
 *
 * <p>{@link #surfaceType} values per ratify #6 (prd.md): V3 auto-dispatch
 * only fires for {@code skill} / {@code prompt}; {@code behavior_rule} reserved
 * for V4 MULTI-SURFACE-FLYWHEEL; {@code other} / {@code unclear} are
 * recorded for audit but never approved.
 *
 * <p>{@link #stage} state machine:
 * <pre>
 *  proposal_pending ─ approve ──► proposal_approved ──► candidate_created
 *                  ─ reject ───► proposal_rejected
 *                                       │
 *                                       ▼
 *                                  candidate_created ──► ab_running
 *                                                          │
 *                                                  ┌───────┴───────┐
 *                                                  ▼               ▼
 *                                              ab_passed       ab_failed
 *                                                  │
 *                                                  ▼
 *                                            canary_started ──► promoted
 *                                                          ─►  rolled_back
 *                                                          ─►  verified
 * </pre>
 *
 * <p>{@link #candidateSkillId} populated when surface=skill;
 * {@link #candidatePromptVersionId} populated when surface=prompt. The
 * columns are intentionally split (not polymorphic) so each path has a
 * type-safe link.
 *
 * <p>{@link #cooldownExpiresAt} is the 24h cooldown anchor per ratify #2
 * (prd.md); AttributionDispatcherService (Phase 1.2) excludes any pattern
 * whose latest event has cooldownExpiresAt > NOW().
 *
 * <p>Audit columns ({@link #createdAt} / {@link #updatedAt}) are written
 * explicitly at insert + stage-transition save (no {@code @EntityListeners} —
 * matches V77 CanaryRolloutEntity convention to avoid the V69 auditing-listener
 * footgun where post-seed save() overwrites Flyway-defaulted columns).
 */
@Entity
@Table(name = "t_optimization_event")
public class OptimizationEventEntity {

    // surface_type values (ratify #6 — V3 auto-approves {skill, prompt} only)
    public static final String SURFACE_SKILL = "skill";
    public static final String SURFACE_PROMPT = "prompt";
    public static final String SURFACE_BEHAVIOR_RULE = "behavior_rule";  // V4
    public static final String SURFACE_OTHER = "other";
    public static final String SURFACE_UNCLEAR = "unclear";

    // stage state machine
    /**
     * Phase 1.3 — sentinel row written by AttributionDispatcherService BEFORE
     * chatAsync. Closes the race window where a parallel dispatcher tick could
     * see "no event for this pattern" and double-fire. ProposeOptimizationTool
     * later UPDATEs the sentinel into proposal_pending (or proposal_rejected)
     * once the curator agent finishes.
     */
    public static final String STAGE_DISPATCH_INITIATED = "dispatch_initiated";
    public static final String STAGE_PROPOSAL_PENDING = "proposal_pending";
    public static final String STAGE_PROPOSAL_APPROVED = "proposal_approved";
    public static final String STAGE_PROPOSAL_REJECTED = "proposal_rejected";
    /** Phase 1.3 — set by AttributionApprovalService.approve() once it kicks off candidate generation. */
    public static final String STAGE_CANDIDATE_GENERATING = "candidate_generating";
    /** Phase 1.3 — set by AttributionApprovalService.approve() after candidate gen returns successfully. */
    public static final String STAGE_CANDIDATE_READY = "candidate_ready";
    /** Phase 1.3 — set by AttributionApprovalService.approve() if candidate generation throws. */
    public static final String STAGE_CANDIDATE_FAILED = "candidate_failed";
    /** Legacy alias kept for callers that pre-existed Phase 1.3 stage rename (Phase 1.2 ProposeOptimizationTool javadoc). */
    public static final String STAGE_CANDIDATE_CREATED = "candidate_created";
    public static final String STAGE_AB_RUNNING = "ab_running";
    public static final String STAGE_AB_PASSED = "ab_passed";
    public static final String STAGE_AB_FAILED = "ab_failed";
    public static final String STAGE_CANARY_STARTED = "canary_started";
    public static final String STAGE_PROMOTED = "promoted";
    public static final String STAGE_ROLLED_BACK = "rolled_back";
    public static final String STAGE_VERIFIED = "verified";

    // risk values (free-form column but conventional vocabulary)
    public static final String RISK_LOW = "low";
    public static final String RISK_MEDIUM = "medium";
    public static final String RISK_HIGH = "high";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * V1.2 OPT-REPORT-V1 (V101, 2026-05-23): nullable to support events
     * derived from the new "report → convert-to-event" bridge path. Legacy
     * attribution-curator events always carry a non-null patternId (V80
     * NOT NULL constraint preserved at the application level via
     * {@code ProposeOptimizationTool}); report-derived events skip the
     * pattern concept entirely and instead point back via
     * {@link #sourceReportId} / {@link #sourceIssueId}. V101 ALTER drops
     * the DB NOT NULL constraint; the column type was already
     * {@code Long} (boxed) so no Java-side change was needed.
     */
    @Column(name = "pattern_id")
    private Long patternId;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Column(name = "surface_type", nullable = false, length = 32)
    private String surfaceType;

    @Column(name = "change_type", length = 64)
    private String changeType;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "expected_impact", columnDefinition = "TEXT")
    private String expectedImpact;

    @Column(name = "confidence", precision = 3, scale = 2)
    private BigDecimal confidence;

    @Column(name = "risk", length = 16)
    private String risk;

    @Column(name = "stage", nullable = false, length = 32)
    private String stage;

    @Column(name = "candidate_skill_id")
    private Long candidateSkillId;

    @Column(name = "candidate_prompt_version_id")
    private Long candidatePromptVersionId;

    /**
     * V4 MULTI-SURFACE-FLYWHEEL Phase 1.3: type-safe link to the
     * {@link com.skillforge.server.entity.BehaviorRuleVersionEntity} produced
     * by {@code AttributionApprovalService.dispatchBehaviorRuleSurface} when
     * {@link #surfaceType} = {@code behavior_rule}. Mirrors
     * {@link #candidateSkillId} / {@link #candidatePromptVersionId} but
     * VARCHAR(36) because {@code BehaviorRuleVersionEntity.id} is a UUID
     * string (V82 column type). The V3.2 stage-mirror listener uses this
     * column to look up the matching event on a {@code BehaviorRulePromotedEvent}.
     */
    @Column(name = "candidate_behavior_rule_version_id", length = 36)
    private String candidateBehaviorRuleVersionId;

    /**
     * FLYWHEEL-LOOP-CLOSURE Phase 1.2 (V88, 2026-05-16) — UUID sidecar for
     * {@link #candidatePromptVersionId}. {@code PromptVersionEntity.id} is a
     * String UUID that won't fit in the legacy BIGINT column, so the
     * attribution dispatch path writes the link here instead. Phase 1.3
     * listener and Phase 1.4 /run-ab endpoint read from this column. The
     * legacy BIGINT column stays for backward compatibility but remains NULL
     * on the attribution-prompt path.
     */
    @Column(name = "candidate_prompt_version_uuid", length = 36)
    private String candidatePromptVersionUuid;

    /**
     * FLYWHEEL-LOOP-CLOSURE Phase 1.2 (V88, 2026-05-16) — UUID sidecar for the
     * SkillDraft the attribution dispatcher just created.
     * {@code SkillDraftEntity.id} is a String UUID; the legacy
     * {@link #candidateSkillId} BIGINT gets populated only later when the
     * draft is approved into a SkillEntity row (in
     * {@code SkillDraftService.approveDraft}). Until then, this sidecar is
     * the canonical pointer back to the draft.
     */
    @Column(name = "candidate_skill_draft_uuid", length = 36)
    private String candidateSkillDraftUuid;

    @Column(name = "ab_run_id")
    private Long abRunId;

    @Column(name = "canary_id")
    private Long canaryId;

    @Column(name = "attribution_session_id", length = 36)
    private String attributionSessionId;

    @Column(name = "cooldown_expires_at")
    private Instant cooldownExpiresAt;

    /**
     * V1.2 OPT-REPORT-V1 (V101, 2026-05-23): non-null on events derived
     * from the "Convert to Event" bridge action — points back to the
     * {@code t_opt_report} row that contained the originating issue.
     * NULL for every legacy attribution-curator-derived event.
     *
     * <p>FK {@code REFERENCES t_opt_report(id) ON DELETE SET NULL} — if
     * the operator deletes the report, derived events keep their
     * stage/pattern/etc. but lose the back-pointer (the proposal can
     * still be reviewed on its own merits).
     *
     * <p>Idempotency: paired with {@link #sourceIssueId} this is the
     * dedupe key used by {@code OptReportToEventBridge.convertIssueToEvent}
     * to make re-clicks no-op.
     */
    @Column(name = "source_report_id", length = 36)
    private String sourceReportId;

    /**
     * V1.2 OPT-REPORT-V1 (V101, 2026-05-23): the stable {@code id} from
     * {@code summary_json.topIssues[i].id} (e.g. {@code "issue-1"}). Paired
     * with {@link #sourceReportId} for the idempotency check; nullable
     * because the legacy attribution-curator path never sets it.
     */
    @Column(name = "source_issue_id", length = 64)
    private String sourceIssueId;

    @Column(name = "memory_context_hash", length = 64)
    private String memoryContextHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "memory_context_memory_ids", columnDefinition = "jsonb")
    private String memoryContextMemoryIds;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public OptimizationEventEntity() {}

    /**
     * Phase 1.1 reviewer Fix #1 (java W2 + db W3): JPA lifecycle callback ensures
     * audit columns are populated even when callers (e.g. Phase 1.3
     * OptimizationEventService stage-transition paths) forget to call
     * {@link #setCreatedAt(Instant)} / {@link #setUpdatedAt(Instant)} explicitly.
     * V80 schema marks both NOT NULL, so a missed set() would otherwise raise
     * a {@code ConstraintViolationException} with a confusing root cause.
     *
     * <p>Intentionally uses {@code @PrePersist} / {@code @PreUpdate} (JPA built-in
     * lifecycle callbacks on the entity itself) rather than
     * {@code @EntityListeners(AuditingEntityListener.class)} — the latter is the
     * V69 footgun that rewrites Flyway-defaulted timestamps post-seed.
     *
     * <p>Caller-set timestamps still win (the {@code if (createdAt == null)} guard
     * preserves explicit override paths needed by IT fixtures that backdate rows
     * to simulate "old" events).
     */
    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getPatternId() { return patternId; }
    public void setPatternId(Long patternId) { this.patternId = patternId; }

    public Long getAgentId() { return agentId; }
    public void setAgentId(Long agentId) { this.agentId = agentId; }

    public String getSurfaceType() { return surfaceType; }
    public void setSurfaceType(String surfaceType) { this.surfaceType = surfaceType; }

    public String getChangeType() { return changeType; }
    public void setChangeType(String changeType) { this.changeType = changeType; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getExpectedImpact() { return expectedImpact; }
    public void setExpectedImpact(String expectedImpact) { this.expectedImpact = expectedImpact; }

    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal confidence) { this.confidence = confidence; }

    public String getRisk() { return risk; }
    public void setRisk(String risk) { this.risk = risk; }

    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }

    public Long getCandidateSkillId() { return candidateSkillId; }
    public void setCandidateSkillId(Long candidateSkillId) { this.candidateSkillId = candidateSkillId; }

    public Long getCandidatePromptVersionId() { return candidatePromptVersionId; }
    public void setCandidatePromptVersionId(Long candidatePromptVersionId) {
        this.candidatePromptVersionId = candidatePromptVersionId;
    }

    public String getCandidateBehaviorRuleVersionId() { return candidateBehaviorRuleVersionId; }
    public void setCandidateBehaviorRuleVersionId(String candidateBehaviorRuleVersionId) {
        this.candidateBehaviorRuleVersionId = candidateBehaviorRuleVersionId;
    }

    public String getCandidatePromptVersionUuid() { return candidatePromptVersionUuid; }
    public void setCandidatePromptVersionUuid(String candidatePromptVersionUuid) {
        this.candidatePromptVersionUuid = candidatePromptVersionUuid;
    }

    public String getCandidateSkillDraftUuid() { return candidateSkillDraftUuid; }
    public void setCandidateSkillDraftUuid(String candidateSkillDraftUuid) {
        this.candidateSkillDraftUuid = candidateSkillDraftUuid;
    }

    public Long getAbRunId() { return abRunId; }
    public void setAbRunId(Long abRunId) { this.abRunId = abRunId; }

    public Long getCanaryId() { return canaryId; }
    public void setCanaryId(Long canaryId) { this.canaryId = canaryId; }

    public String getAttributionSessionId() { return attributionSessionId; }
    public void setAttributionSessionId(String attributionSessionId) {
        this.attributionSessionId = attributionSessionId;
    }

    public Instant getCooldownExpiresAt() { return cooldownExpiresAt; }
    public void setCooldownExpiresAt(Instant cooldownExpiresAt) {
        this.cooldownExpiresAt = cooldownExpiresAt;
    }

    public String getSourceReportId() { return sourceReportId; }
    public void setSourceReportId(String sourceReportId) { this.sourceReportId = sourceReportId; }

    public String getSourceIssueId() { return sourceIssueId; }
    public void setSourceIssueId(String sourceIssueId) { this.sourceIssueId = sourceIssueId; }

    public String getMemoryContextHash() { return memoryContextHash; }
    public void setMemoryContextHash(String memoryContextHash) {
        this.memoryContextHash = memoryContextHash;
    }

    public String getMemoryContextMemoryIds() { return memoryContextMemoryIds; }
    public void setMemoryContextMemoryIds(String memoryContextMemoryIds) {
        this.memoryContextMemoryIds = memoryContextMemoryIds;
    }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
