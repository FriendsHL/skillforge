package com.skillforge.server.entity;

import com.skillforge.server.eval.attribution.FailureAttribution;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

@Entity
@Table(name = "t_eval_run")
@EntityListeners(AuditingEntityListener.class)
public class EvalRunEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(length = 36, nullable = false)
    private String agentDefinitionId;

    @Column(length = 64)
    private String scenarioSetVersion;

    @Column(length = 32)
    private String status;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(columnDefinition = "TEXT")
    private String scenarioResultsJson;

    @Column(columnDefinition = "TEXT")
    private String improvementSuggestionsJson;

    private double overallPassRate;

    private double avgOracleScore;

    private int totalScenarios;

    private int passedScenarios;

    private int failedScenarios;

    private int timeoutScenarios;

    private int vetoScenarios;

    private int attrSkillMissing;

    private int attrSkillExecFailure;

    private int attrPromptQuality;

    private int attrContextOverflow;

    private int attrPerformance;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private FailureAttribution primaryAttribution;

    private int consecutiveDeclineCount;

    private Long triggeredByUserId;

    @CreatedDate
    private Instant startedAt;

    private Instant completedAt;

    @Column(length = 36)
    private String collabRunId;

    public EvalRunEntity() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getAgentDefinitionId() { return agentDefinitionId; }
    public void setAgentDefinitionId(String agentDefinitionId) { this.agentDefinitionId = agentDefinitionId; }

    public String getScenarioSetVersion() { return scenarioSetVersion; }
    public void setScenarioSetVersion(String scenarioSetVersion) { this.scenarioSetVersion = scenarioSetVersion; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getScenarioResultsJson() { return scenarioResultsJson; }
    public void setScenarioResultsJson(String scenarioResultsJson) { this.scenarioResultsJson = scenarioResultsJson; }

    public String getImprovementSuggestionsJson() { return improvementSuggestionsJson; }
    public void setImprovementSuggestionsJson(String improvementSuggestionsJson) { this.improvementSuggestionsJson = improvementSuggestionsJson; }

    public double getOverallPassRate() { return overallPassRate; }
    public void setOverallPassRate(double overallPassRate) { this.overallPassRate = overallPassRate; }

    public double getAvgOracleScore() { return avgOracleScore; }
    public void setAvgOracleScore(double avgOracleScore) { this.avgOracleScore = avgOracleScore; }

    public int getTotalScenarios() { return totalScenarios; }
    public void setTotalScenarios(int totalScenarios) { this.totalScenarios = totalScenarios; }

    public int getPassedScenarios() { return passedScenarios; }
    public void setPassedScenarios(int passedScenarios) { this.passedScenarios = passedScenarios; }

    public int getFailedScenarios() { return failedScenarios; }
    public void setFailedScenarios(int failedScenarios) { this.failedScenarios = failedScenarios; }

    public int getTimeoutScenarios() { return timeoutScenarios; }
    public void setTimeoutScenarios(int timeoutScenarios) { this.timeoutScenarios = timeoutScenarios; }

    public int getVetoScenarios() { return vetoScenarios; }
    public void setVetoScenarios(int vetoScenarios) { this.vetoScenarios = vetoScenarios; }

    public int getAttrSkillMissing() { return attrSkillMissing; }
    public void setAttrSkillMissing(int attrSkillMissing) { this.attrSkillMissing = attrSkillMissing; }

    public int getAttrSkillExecFailure() { return attrSkillExecFailure; }
    public void setAttrSkillExecFailure(int attrSkillExecFailure) { this.attrSkillExecFailure = attrSkillExecFailure; }

    public int getAttrPromptQuality() { return attrPromptQuality; }
    public void setAttrPromptQuality(int attrPromptQuality) { this.attrPromptQuality = attrPromptQuality; }

    public int getAttrContextOverflow() { return attrContextOverflow; }
    public void setAttrContextOverflow(int attrContextOverflow) { this.attrContextOverflow = attrContextOverflow; }

    public int getAttrPerformance() { return attrPerformance; }
    public void setAttrPerformance(int attrPerformance) { this.attrPerformance = attrPerformance; }

    public FailureAttribution getPrimaryAttribution() { return primaryAttribution; }
    public void setPrimaryAttribution(FailureAttribution primaryAttribution) { this.primaryAttribution = primaryAttribution; }

    public int getConsecutiveDeclineCount() { return consecutiveDeclineCount; }
    public void setConsecutiveDeclineCount(int consecutiveDeclineCount) { this.consecutiveDeclineCount = consecutiveDeclineCount; }

    public Long getTriggeredByUserId() { return triggeredByUserId; }
    public void setTriggeredByUserId(Long triggeredByUserId) { this.triggeredByUserId = triggeredByUserId; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    public String getCollabRunId() { return collabRunId; }
    public void setCollabRunId(String collabRunId) { this.collabRunId = collabRunId; }
}
