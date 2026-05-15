package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * MULTI-SURFACE-FLYWHEEL V4 Phase 1.1: A/B run row for the behavior_rule
 * surface. Mirrors {@link PromptAbRunEntity} so the V4
 * {@code AbstractAbEvalRunner<V>} (Phase 1.2) can drive all three surfaces
 * via the same Template Method.
 *
 * <p>Backed by V82 {@code t_behavior_rule_ab_run}. Phase 1.1 only persists +
 * round-trips rows (verified by {@code BehaviorRulePersistenceIT}); the real
 * A/B pipeline wiring lands in Phase 1.2 once the abstract runner exists.
 */
@Entity
@Table(name = "t_behavior_rule_ab_run")
public class BehaviorRuleAbRunEntity {

    public static final String STATUS_PENDING   = "PENDING";
    public static final String STATUS_RUNNING   = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED    = "FAILED";

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "agent_id", length = 36, nullable = false)
    private String agentId;

    @Column(name = "baseline_version_id", length = 36, nullable = false)
    private String baselineVersionId;

    @Column(name = "candidate_version_id", length = 36, nullable = false)
    private String candidateVersionId;

    @Column(name = "baseline_eval_run_id", length = 36)
    private String baselineEvalRunId;

    @Column(length = 32, nullable = false)
    private String status = STATUS_PENDING;

    @Column(name = "baseline_pass_rate")
    private Double baselinePassRate;

    @Column(name = "candidate_pass_rate")
    private Double candidatePassRate;

    @Column(name = "delta_pass_rate")
    private Double deltaPassRate;

    @Column(nullable = false)
    private boolean promoted = false;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "ab_scenario_results_json", columnDefinition = "TEXT")
    private String abScenarioResultsJson;

    @Column(name = "triggered_by_user_id")
    private Long triggeredByUserId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    public BehaviorRuleAbRunEntity() {}

    @PrePersist
    void onPrePersist() {
        if (startedAt == null) {
            startedAt = Instant.now();
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getBaselineVersionId() { return baselineVersionId; }
    public void setBaselineVersionId(String baselineVersionId) { this.baselineVersionId = baselineVersionId; }

    public String getCandidateVersionId() { return candidateVersionId; }
    public void setCandidateVersionId(String candidateVersionId) { this.candidateVersionId = candidateVersionId; }

    public String getBaselineEvalRunId() { return baselineEvalRunId; }
    public void setBaselineEvalRunId(String baselineEvalRunId) { this.baselineEvalRunId = baselineEvalRunId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Double getBaselinePassRate() { return baselinePassRate; }
    public void setBaselinePassRate(Double baselinePassRate) { this.baselinePassRate = baselinePassRate; }

    public Double getCandidatePassRate() { return candidatePassRate; }
    public void setCandidatePassRate(Double candidatePassRate) { this.candidatePassRate = candidatePassRate; }

    public Double getDeltaPassRate() { return deltaPassRate; }
    public void setDeltaPassRate(Double deltaPassRate) { this.deltaPassRate = deltaPassRate; }

    public boolean isPromoted() { return promoted; }
    public void setPromoted(boolean promoted) { this.promoted = promoted; }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }

    public String getAbScenarioResultsJson() { return abScenarioResultsJson; }
    public void setAbScenarioResultsJson(String abScenarioResultsJson) { this.abScenarioResultsJson = abScenarioResultsJson; }

    public Long getTriggeredByUserId() { return triggeredByUserId; }
    public void setTriggeredByUserId(Long triggeredByUserId) { this.triggeredByUserId = triggeredByUserId; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
