package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * SKILL-CANARY-ROLLOUT V2: one row per active or historical canary rollout
 * for an (agent_id × surface_type) pair.
 *
 * <p>Backed by V77 {@code t_canary_rollout}. Active canaries (one per pair) are
 * enforced by the partial UNIQUE INDEX {@code uq_canary_active} on PostgreSQL
 * (H2 does not support partial unique indices — JPA IT runs in Testcontainers
 * PG only).
 *
 * <p>Field naming: per Phase 1.0 校对 the schema uses
 * {@link #baselineSkillName} / {@link #candidateSkillName} (VARCHAR(64))
 * matching {@code SessionSkillResolver}'s name-based abstraction, NOT
 * version_id BIGINT as the original draft assumed. See
 * {@code tech-design.md} §0.3 + §5.
 *
 * <p>{@link #rolloutStage} values:
 * <ul>
 *   <li>{@code disabled} — created but not started</li>
 *   <li>{@code canary}   — actively splitting traffic</li>
 *   <li>{@code production} — promoted to 100% (terminal)</li>
 *   <li>{@code rolled_back} — manual or auto rollback (terminal)</li>
 * </ul>
 *
 * <p>{@link #decision} is set on terminal transitions ({@code promoted} or
 * {@code rolled_back}); null while ongoing.
 */
@Entity
@Table(name = "t_canary_rollout")
public class CanaryRolloutEntity {

    public static final String SURFACE_SKILL = "skill";

    public static final String STAGE_DISABLED = "disabled";
    public static final String STAGE_CANARY = "canary";
    public static final String STAGE_PRODUCTION = "production";
    public static final String STAGE_ROLLED_BACK = "rolled_back";

    public static final String DECISION_PROMOTED = "promoted";
    public static final String DECISION_ROLLED_BACK = "rolled_back";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "surface_type", nullable = false, length = 32)
    private String surfaceType;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Column(name = "baseline_skill_name", nullable = false, length = 64)
    private String baselineSkillName;

    @Column(name = "candidate_skill_name", nullable = false, length = 64)
    private String candidateSkillName;

    @Column(name = "rollout_stage", nullable = false, length = 32)
    private String rolloutStage;

    @Column(name = "rollout_percentage", nullable = false)
    private Integer rolloutPercentage = 0;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "last_decision_at")
    private Instant lastDecisionAt;

    @Column(name = "decision", length = 32)
    private String decision;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public CanaryRolloutEntity() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSurfaceType() { return surfaceType; }
    public void setSurfaceType(String surfaceType) { this.surfaceType = surfaceType; }

    public Long getAgentId() { return agentId; }
    public void setAgentId(Long agentId) { this.agentId = agentId; }

    public String getBaselineSkillName() { return baselineSkillName; }
    public void setBaselineSkillName(String baselineSkillName) { this.baselineSkillName = baselineSkillName; }

    public String getCandidateSkillName() { return candidateSkillName; }
    public void setCandidateSkillName(String candidateSkillName) { this.candidateSkillName = candidateSkillName; }

    public String getRolloutStage() { return rolloutStage; }
    public void setRolloutStage(String rolloutStage) { this.rolloutStage = rolloutStage; }

    public Integer getRolloutPercentage() { return rolloutPercentage; }
    public void setRolloutPercentage(Integer rolloutPercentage) { this.rolloutPercentage = rolloutPercentage; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getLastDecisionAt() { return lastDecisionAt; }
    public void setLastDecisionAt(Instant lastDecisionAt) { this.lastDecisionAt = lastDecisionAt; }

    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
