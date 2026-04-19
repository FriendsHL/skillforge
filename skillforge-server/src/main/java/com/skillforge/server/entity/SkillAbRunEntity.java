package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

@Entity
@Table(name = "t_skill_ab_run")
@EntityListeners(AuditingEntityListener.class)
public class SkillAbRunEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false)
    private Long parentSkillId;

    @Column(nullable = false)
    private Long candidateSkillId;

    @Column(length = 36, nullable = false)
    private String agentId;

    @Column(length = 36)
    private String baselineEvalRunId;

    @Column(length = 32, nullable = false)
    private String status = "PENDING";

    private Double baselinePassRate;

    private Double candidatePassRate;

    private Double deltaPassRate;

    @Column(nullable = false)
    private boolean promoted = false;

    @Column(length = 256)
    private String skipReason;

    @Column(columnDefinition = "TEXT")
    private String failureReason;

    @Column(columnDefinition = "TEXT")
    private String abScenarioResultsJson;

    private Long triggeredByUserId;

    @CreatedDate
    private Instant createdAt;

    private Instant startedAt;

    private Instant completedAt;

    public SkillAbRunEntity() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Long getParentSkillId() { return parentSkillId; }
    public void setParentSkillId(Long parentSkillId) { this.parentSkillId = parentSkillId; }

    public Long getCandidateSkillId() { return candidateSkillId; }
    public void setCandidateSkillId(Long candidateSkillId) { this.candidateSkillId = candidateSkillId; }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

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

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
