package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * AUTOEVOLVE-AGENT-LEVEL-BUNDLE Phase 1: whole-agent A/B run row (路 B /
 * 指针元组 / 一个整体分). A <em>bundle</em> is a tuple of per-surface version
 * pointers; the candidate/baseline content stays in the existing per-surface
 * version tables. This row records ONE whole-agent A/B run so the evolve loop
 * can read it back (tech-design.md §1).
 *
 * <p>Backed by V137 {@code t_agent_evolve_ab_run}. Mirrors
 * {@link BehaviorRuleAbRunEntity}: time audit is only {@code started_at}
 * (NOT NULL, {@link PrePersist} default) + {@code completed_at} — NO
 * {@code created_at} (§7 W6). All time columns are {@link Instant} (java.md #2).
 */
@Entity
@Table(name = "t_agent_evolve_ab_run")
public class AgentEvolveAbRunEntity {

    public static final String STATUS_PENDING    = "PENDING";
    public static final String STATUS_RUNNING    = "RUNNING";
    public static final String STATUS_COMPLETED  = "COMPLETED";
    public static final String STATUS_FAILED     = "FAILED";
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "agent_id", length = 36, nullable = false)
    private String agentId;

    /** Candidate bundle pointer tuple JSON: {@code {"promptVersionId":..,"behaviorRuleVersionId":..}}. */
    @Column(name = "candidate_bundle_json", columnDefinition = "TEXT", nullable = false)
    private String candidateBundleJson;

    /** Baseline (current-best) bundle pointer tuple JSON. */
    @Column(name = "baseline_bundle_json", columnDefinition = "TEXT", nullable = false)
    private String baselineBundleJson;

    @Column(name = "dataset_version_id", length = 36)
    private String datasetVersionId;

    @Column(name = "skip_baseline", nullable = false)
    private boolean skipBaseline = false;

    @Column(name = "cached_baseline_rate")
    private Double cachedBaselineRate;

    @Column(name = "baseline_pass_rate")
    private Double baselinePassRate;

    @Column(name = "candidate_pass_rate")
    private Double candidatePassRate;

    @Column(name = "delta_pass_rate")
    private Double deltaPassRate;

    // Phase 2 non-regression gate; Phase 1 leaves these NULL.
    @Column(name = "target_delta_pp")
    private Double targetDeltaPp;

    @Column(name = "regression_delta_pp")
    private Double regressionDeltaPp;

    @Column(name = "ab_scenario_results_json", columnDefinition = "TEXT")
    private String abScenarioResultsJson;

    @Column(length = 32, nullable = false)
    private String status = STATUS_PENDING;

    /**
     * §7 W1 — when {@link #skipBaseline} is true, the service asserts the
     * {@link #baselineBundleJson} structurally equals this prior winner run's
     * {@link #candidateBundleJson} before trusting {@link #cachedBaselineRate}.
     */
    @Column(name = "prior_winner_ab_run_id", length = 36)
    private String priorWinnerAbRunId;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "triggered_by_user_id")
    private Long triggeredByUserId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    public AgentEvolveAbRunEntity() {}

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

    public String getCandidateBundleJson() { return candidateBundleJson; }
    public void setCandidateBundleJson(String candidateBundleJson) { this.candidateBundleJson = candidateBundleJson; }

    public String getBaselineBundleJson() { return baselineBundleJson; }
    public void setBaselineBundleJson(String baselineBundleJson) { this.baselineBundleJson = baselineBundleJson; }

    public String getDatasetVersionId() { return datasetVersionId; }
    public void setDatasetVersionId(String datasetVersionId) { this.datasetVersionId = datasetVersionId; }

    public boolean isSkipBaseline() { return skipBaseline; }
    public void setSkipBaseline(boolean skipBaseline) { this.skipBaseline = skipBaseline; }

    public Double getCachedBaselineRate() { return cachedBaselineRate; }
    public void setCachedBaselineRate(Double cachedBaselineRate) { this.cachedBaselineRate = cachedBaselineRate; }

    public Double getBaselinePassRate() { return baselinePassRate; }
    public void setBaselinePassRate(Double baselinePassRate) { this.baselinePassRate = baselinePassRate; }

    public Double getCandidatePassRate() { return candidatePassRate; }
    public void setCandidatePassRate(Double candidatePassRate) { this.candidatePassRate = candidatePassRate; }

    public Double getDeltaPassRate() { return deltaPassRate; }
    public void setDeltaPassRate(Double deltaPassRate) { this.deltaPassRate = deltaPassRate; }

    public Double getTargetDeltaPp() { return targetDeltaPp; }
    public void setTargetDeltaPp(Double targetDeltaPp) { this.targetDeltaPp = targetDeltaPp; }

    public Double getRegressionDeltaPp() { return regressionDeltaPp; }
    public void setRegressionDeltaPp(Double regressionDeltaPp) { this.regressionDeltaPp = regressionDeltaPp; }

    public String getAbScenarioResultsJson() { return abScenarioResultsJson; }
    public void setAbScenarioResultsJson(String abScenarioResultsJson) { this.abScenarioResultsJson = abScenarioResultsJson; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getPriorWinnerAbRunId() { return priorWinnerAbRunId; }
    public void setPriorWinnerAbRunId(String priorWinnerAbRunId) { this.priorWinnerAbRunId = priorWinnerAbRunId; }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }

    public Long getTriggeredByUserId() { return triggeredByUserId; }
    public void setTriggeredByUserId(Long triggeredByUserId) { this.triggeredByUserId = triggeredByUserId; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
