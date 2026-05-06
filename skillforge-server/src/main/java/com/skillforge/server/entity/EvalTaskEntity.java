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

import java.math.BigDecimal;
import java.time.Instant;

/**
 * EVAL-V2 M3a (b2): renamed from {@code EvalRunEntity} (table {@code t_eval_run})
 * to align with the new "task" model. Per-case rows live in
 * {@link EvalTaskItemEntity}; this row carries task-level aggregates.
 *
 * <p>New fields added by V52:
 * <ul>
 *   <li>{@code scenarioCount} / {@code passCount} (renamed) / {@code failCount}
 *       — task-level counters; {@code passCount} renames the legacy
 *       {@code passedScenarios} (DB column {@code pass_count}).</li>
 *   <li>{@code compositeAvg} — primary score signal (M3a). Legacy
 *       {@code overallPassRate} stays for backward compat with PromptImprover /
 *       SkillAbEval baseline lookups.</li>
 *   <li>{@code datasetFilter} — JSON-encoded subset selector ({@code {"split":"held_out"}}
 *       etc.).</li>
 *   <li>{@code attributionSummary} / {@code improvementSuggestion} — LLM-generated
 *       post-run analysis surface (M5/M6 feed).</li>
 *   <li>{@code analysisSessionId} — optional FK to the analysis chat session
 *       opened from the task drawer (M5 closed-loop).</li>
 * </ul>
 *
 * <p>The legacy table name {@code t_eval_run} survives as a read-only VIEW
 * (V52) so external SQL callers keep working until M3a-b3 + 1 sprint.
 */
@Entity
@Table(name = "t_eval_task")
@EntityListeners(AuditingEntityListener.class)
public class EvalTaskEntity {

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

    /**
     * Renamed from {@code passedScenarios} → {@code passCount} (DB column
     * {@code pass_count}). Symmetric with new {@code failCount}; the legacy
     * column name lives on in the {@code t_eval_run} VIEW.
     */
    @Column(name = "pass_count", nullable = false)
    private int passCount;

    private int failedScenarios;

    private int timeoutScenarios;

    private int vetoScenarios;

    private int attrSkillMissing;

    private int attrSkillExecFailure;

    private int attrPromptQuality;

    private int attrContextOverflow;

    private int attrPerformance;

    private int attrMemoryInterference;

    private int attrMemoryMissing;

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

    // === EVAL-V2 M3a (b2) new fields ===

    /** LLM-generated post-run summary of attribution distribution (M5 feed). */
    @Column(name = "attribution_summary", columnDefinition = "TEXT")
    private String attributionSummary;

    /** LLM-generated improvement suggestion (M6 feed). */
    @Column(name = "improvement_suggestion", columnDefinition = "TEXT")
    private String improvementSuggestion;

    /** Optional FK to the analysis chat session opened from the task drawer. */
    @Column(name = "analysis_session_id", length = 36)
    private String analysisSessionId;

    /**
     * JSON-encoded subset selector — e.g. {@code {"split":"held_out"}} or
     * {@code {"tags":["multi-turn"]}}. NULL means full dataset.
     */
    @Column(name = "dataset_filter", columnDefinition = "TEXT")
    private String datasetFilter;

    /**
     * Total scenarios this task was supposed to run (post-filter). Distinct
     * from {@link #totalScenarios} which is legacy and may diverge during
     * mid-task progress UI rendering — {@link #scenarioCount} is the
     * authoritative pre-run count, {@link #totalScenarios} is updated
     * incrementally.
     */
    @Column(name = "scenario_count")
    private Integer scenarioCount;

    /** Symmetric with {@link #passCount}. Set by EvalOrchestrator at completion. */
    @Column(name = "fail_count", nullable = false)
    private int failCount;

    /**
     * Average of per-case composite scores. Primary score signal in M3a;
     * {@link #overallPassRate} stays for backward compat with PromptImprover /
     * SkillAbEval baseline lookups.
     */
    @Column(name = "composite_avg", precision = 5, scale = 2)
    private BigDecimal compositeAvg;

    public EvalTaskEntity() {
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

    public int getPassCount() { return passCount; }
    public void setPassCount(int passCount) { this.passCount = passCount; }

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

    public int getAttrMemoryInterference() { return attrMemoryInterference; }
    public void setAttrMemoryInterference(int attrMemoryInterference) { this.attrMemoryInterference = attrMemoryInterference; }

    public int getAttrMemoryMissing() { return attrMemoryMissing; }
    public void setAttrMemoryMissing(int attrMemoryMissing) { this.attrMemoryMissing = attrMemoryMissing; }

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

    public String getAttributionSummary() { return attributionSummary; }
    public void setAttributionSummary(String attributionSummary) { this.attributionSummary = attributionSummary; }

    public String getImprovementSuggestion() { return improvementSuggestion; }
    public void setImprovementSuggestion(String improvementSuggestion) { this.improvementSuggestion = improvementSuggestion; }

    public String getAnalysisSessionId() { return analysisSessionId; }
    public void setAnalysisSessionId(String analysisSessionId) { this.analysisSessionId = analysisSessionId; }

    public String getDatasetFilter() { return datasetFilter; }
    public void setDatasetFilter(String datasetFilter) { this.datasetFilter = datasetFilter; }

    public Integer getScenarioCount() { return scenarioCount; }
    public void setScenarioCount(Integer scenarioCount) { this.scenarioCount = scenarioCount; }

    public int getFailCount() { return failCount; }
    public void setFailCount(int failCount) { this.failCount = failCount; }

    public BigDecimal getCompositeAvg() { return compositeAvg; }
    public void setCompositeAvg(BigDecimal compositeAvg) { this.compositeAvg = compositeAvg; }
}
