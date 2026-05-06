package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * EVAL-V2 M3a (b2): per-case row in {@code t_eval_task_item}, FK on {@code task_id}
 * → {@link EvalTaskEntity#id}.
 *
 * <p>Replaces the historical practice of dumping all per-case data into
 * {@code t_eval_task.scenario_results_json} (TEXT). Row-level storage gives:
 * <ul>
 *   <li>Indexable case-level filtering / sorting (Items tab UI).</li>
 *   <li>Direct FK from {@code session_id} → {@code t_session.id} so OBS trace
 *       UI can drill from a failed item to its chat session + root_trace_id.</li>
 *   <li>Per-case attribution / judge_rationale queries become plain JPQL.</li>
 * </ul>
 *
 * <p>Legacy completed tasks (status=COMPLETED) are migrated by V54
 * data-migration into row-level entries (with NULL session_id / root_trace_id /
 * scenario_source / tool_call_count — those are new in M3a and not in the
 * legacy jsonb shape).
 */
@Entity
@Table(name = "t_eval_task_item")
public class EvalTaskItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", length = 36, nullable = false)
    private String taskId;

    @Column(name = "scenario_id", length = 64, nullable = false)
    private String scenarioId;

    /** {@code classpath} | {@code home} | {@code db} (per {@code EvalScenario.SOURCE_*}). */
    @Column(name = "scenario_source", length = 16)
    private String scenarioSource;

    /** Real {@code t_session.id} (NOT the legacy synthetic "eval_<UUID>"). */
    @Column(name = "session_id", length = 36)
    private String sessionId;

    /** Snapshot of {@code t_session.active_root_trace_id} at scenario completion. */
    @Column(name = "root_trace_id", length = 36)
    private String rootTraceId;

    @Column(name = "composite_score", precision = 5, scale = 2)
    private BigDecimal compositeScore;

    @Column(name = "quality_score", precision = 5, scale = 2)
    private BigDecimal qualityScore;

    @Column(name = "efficiency_score", precision = 5, scale = 2)
    private BigDecimal efficiencyScore;

    @Column(name = "latency_score", precision = 5, scale = 2)
    private BigDecimal latencyScore;

    @Column(name = "cost_score", precision = 5, scale = 2)
    private BigDecimal costScore;

    @Column(name = "cost_usd", precision = 12, scale = 6)
    private BigDecimal costUsd;

    @Column(name = "score_formula_version", length = 32)
    private String scoreFormulaVersion;

    @Column(name = "score_breakdown_json", columnDefinition = "TEXT")
    private String scoreBreakdownJson;

    /** PASS | FAIL | TIMEOUT | VETO | ERROR (mirrors {@code ScenarioRunResult.status}). */
    @Column(name = "status", length = 16, nullable = false)
    private String status;

    @Column(name = "loop_count")
    private Integer loopCount;

    @Column(name = "tool_call_count")
    private Integer toolCallCount;

    @Column(name = "latency_ms")
    private Long latencyMs;

    /** {@code FailureAttribution.name()} string; nullable for non-failed cases. */
    @Column(name = "attribution", length = 64)
    private String attribution;

    @Column(name = "judge_rationale", columnDefinition = "TEXT")
    private String judgeRationale;

    @Column(name = "agent_final_output", columnDefinition = "TEXT")
    private String agentFinalOutput;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public EvalTaskItemEntity() {
    }

    /** Set createdAt automatically if caller didn't (matches DB DEFAULT now()). */
    public void touchCreatedAtIfMissing() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public String getScenarioId() { return scenarioId; }
    public void setScenarioId(String scenarioId) { this.scenarioId = scenarioId; }

    public String getScenarioSource() { return scenarioSource; }
    public void setScenarioSource(String scenarioSource) { this.scenarioSource = scenarioSource; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getRootTraceId() { return rootTraceId; }
    public void setRootTraceId(String rootTraceId) { this.rootTraceId = rootTraceId; }

    public BigDecimal getCompositeScore() { return compositeScore; }
    public void setCompositeScore(BigDecimal compositeScore) { this.compositeScore = compositeScore; }

    public BigDecimal getQualityScore() { return qualityScore; }
    public void setQualityScore(BigDecimal qualityScore) { this.qualityScore = qualityScore; }

    public BigDecimal getEfficiencyScore() { return efficiencyScore; }
    public void setEfficiencyScore(BigDecimal efficiencyScore) { this.efficiencyScore = efficiencyScore; }

    public BigDecimal getLatencyScore() { return latencyScore; }
    public void setLatencyScore(BigDecimal latencyScore) { this.latencyScore = latencyScore; }

    public BigDecimal getCostScore() { return costScore; }
    public void setCostScore(BigDecimal costScore) { this.costScore = costScore; }

    public BigDecimal getCostUsd() { return costUsd; }
    public void setCostUsd(BigDecimal costUsd) { this.costUsd = costUsd; }

    public String getScoreFormulaVersion() { return scoreFormulaVersion; }
    public void setScoreFormulaVersion(String scoreFormulaVersion) { this.scoreFormulaVersion = scoreFormulaVersion; }

    public String getScoreBreakdownJson() { return scoreBreakdownJson; }
    public void setScoreBreakdownJson(String scoreBreakdownJson) { this.scoreBreakdownJson = scoreBreakdownJson; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getLoopCount() { return loopCount; }
    public void setLoopCount(Integer loopCount) { this.loopCount = loopCount; }

    public Integer getToolCallCount() { return toolCallCount; }
    public void setToolCallCount(Integer toolCallCount) { this.toolCallCount = toolCallCount; }

    public Long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Long latencyMs) { this.latencyMs = latencyMs; }

    public String getAttribution() { return attribution; }
    public void setAttribution(String attribution) { this.attribution = attribution; }

    public String getJudgeRationale() { return judgeRationale; }
    public void setJudgeRationale(String judgeRationale) { this.judgeRationale = judgeRationale; }

    public String getAgentFinalOutput() { return agentFinalOutput; }
    public void setAgentFinalOutput(String agentFinalOutput) { this.agentFinalOutput = agentFinalOutput; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
