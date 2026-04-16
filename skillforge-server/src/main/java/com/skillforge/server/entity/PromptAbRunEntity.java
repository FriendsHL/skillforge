package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "t_prompt_ab_run")
public class PromptAbRunEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(length = 36, nullable = false)
    private String agentId;

    @Column(length = 36, nullable = false)
    private String promptVersionId;

    @Column(length = 36, nullable = false)
    private String baselineEvalRunId;

    @Column(length = 32, nullable = false)
    private String status = "PENDING";

    private Double baselinePassRate;

    private Double candidatePassRate;

    private Double deltaPassRate;

    @Column(nullable = false)
    private boolean promoted = false;

    @Column(length = 128)
    private String skipReason;

    @Column(columnDefinition = "TEXT")
    private String failureReason;

    @Column(columnDefinition = "TEXT")
    private String abScenarioResultsJson;

    private Long triggeredByUserId;

    private Instant startedAt;

    private Instant completedAt;

    public PromptAbRunEntity() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getPromptVersionId() { return promptVersionId; }
    public void setPromptVersionId(String promptVersionId) { this.promptVersionId = promptVersionId; }

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

    public String getSkipReason() { return skipReason; }
    public void setSkipReason(String skipReason) { this.skipReason = skipReason; }

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
