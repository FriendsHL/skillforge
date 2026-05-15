package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * MULTI-SURFACE-FLYWHEEL V4 Phase 1.1: versioned behavior_rule snapshot for an
 * agent. Mirrors {@link PromptVersionEntity}'s shape so the V4
 * {@code AbstractAbEvalRunner<V>} Template Method (Phase 1.2) can treat the
 * three surfaces uniformly.
 *
 * <p>Backed by V82 {@code t_behavior_rule_version}. Partial UNIQUE INDEX
 * {@code uq_brv_one_active} enforces "≤1 active row per agent" on PostgreSQL
 * — JPA IT runs in Testcontainers PG only (H2 does not support partial
 * unique indices; see {@code CanaryPersistenceIT} for the same pattern).
 *
 * <p>Status values:
 * <ul>
 *   <li>{@code candidate} — created via attribution / auto_improve, not yet
 *       in production</li>
 *   <li>{@code active}    — current production version (≤1 per agent)</li>
 *   <li>{@code retired}   — was active, superseded by a newer promote</li>
 *   <li>{@code rejected}  — A/B failed or operator declined</li>
 * </ul>
 *
 * <p>Source values:
 * <ul>
 *   <li>{@code manual}       — operator hand-edited rules</li>
 *   <li>{@code attribution}  — generated from a curator's attribution proposal
 *       (V3-style flow — see {@code BehaviorRuleImproverService.startImprovementFromAttribution})</li>
 *   <li>{@code auto_improve} — eval-driven (future)</li>
 * </ul>
 *
 * <p>Audit trail: per {@code java.md} known footgun #2, time fields use
 * {@link Instant} (not {@code LocalDateTime}). Default values set in
 * {@link #onPrePersist()} so JPA insert paths work whether the caller stamps
 * createdAt explicitly or not. We deliberately do NOT use
 * {@code @EntityListeners(AuditingEntityListener.class)} here per V77 / V80
 * convention: those listeners can't observe migration-defaulted columns
 * (e.g. {@code created_at TIMESTAMPTZ DEFAULT NOW()}), and mixing both
 * sources of truth invites the V77 footgun where DB default + listener fight
 * over the same column.
 */
@Entity
@Table(name = "t_behavior_rule_version")
public class BehaviorRuleVersionEntity {

    public static final String STATUS_CANDIDATE = "candidate";
    public static final String STATUS_ACTIVE    = "active";
    public static final String STATUS_RETIRED   = "retired";
    public static final String STATUS_REJECTED  = "rejected";

    public static final String SOURCE_MANUAL       = "manual";
    public static final String SOURCE_ATTRIBUTION  = "attribution";
    public static final String SOURCE_AUTO_IMPROVE = "auto_improve";

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "agent_id", length = 36, nullable = false)
    private String agentId;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(length = 16, nullable = false)
    private String status = STATUS_CANDIDATE;

    @Column(name = "rules_json", columnDefinition = "TEXT", nullable = false)
    private String rulesJson;

    @Column(length = 32, nullable = false)
    private String source = SOURCE_MANUAL;

    @Column(name = "improvement_rationale", columnDefinition = "TEXT")
    private String improvementRationale;

    @Column(name = "source_event_id")
    private Long sourceEventId;

    @Column(name = "baseline_version_id", length = 36)
    private String baselineVersionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "promoted_at")
    private Instant promotedAt;

    public BehaviorRuleVersionEntity() {}

    /**
     * Stamp {@code createdAt} if the caller hasn't set it. Pure defensive —
     * DB has {@code DEFAULT NOW()}, but Hibernate may bypass DEFAULT clauses
     * for nullable-stamped columns depending on dialect.
     */
    @PrePersist
    void onPrePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    @PreUpdate
    void onPreUpdate() {
        // No-op today (no updatedAt column on this entity — promotedAt is
        // updated explicitly by BehaviorRulePromotionService.promote when the
        // transition happens). Stub kept so future audit columns can hook in
        // without re-introducing @EntityListeners.
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public int getVersionNumber() { return versionNumber; }
    public void setVersionNumber(int versionNumber) { this.versionNumber = versionNumber; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getRulesJson() { return rulesJson; }
    public void setRulesJson(String rulesJson) { this.rulesJson = rulesJson; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getImprovementRationale() { return improvementRationale; }
    public void setImprovementRationale(String improvementRationale) { this.improvementRationale = improvementRationale; }

    public Long getSourceEventId() { return sourceEventId; }
    public void setSourceEventId(Long sourceEventId) { this.sourceEventId = sourceEventId; }

    public String getBaselineVersionId() { return baselineVersionId; }
    public void setBaselineVersionId(String baselineVersionId) { this.baselineVersionId = baselineVersionId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getPromotedAt() { return promotedAt; }
    public void setPromotedAt(Instant promotedAt) { this.promotedAt = promotedAt; }
}
