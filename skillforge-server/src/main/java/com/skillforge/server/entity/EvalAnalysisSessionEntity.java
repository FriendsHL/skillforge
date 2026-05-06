package com.skillforge.server.entity;

import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

@Entity
@Table(name = "t_eval_analysis_session")
@EntityListeners(AuditingEntityListener.class)
public class EvalAnalysisSessionEntity {

    public static final String TYPE_SCENARIO_HISTORY = "scenario_history";
    public static final String TYPE_RUN_CASE = "run_case";
    public static final String TYPE_RUN_OVERALL = "run_overall";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", length = 36, nullable = false)
    private String sessionId;

    @Column(name = "task_id", length = 36)
    private String taskId;

    @Column(name = "task_item_id")
    private Long taskItemId;

    @Column(name = "scenario_id", length = 64)
    private String scenarioId;

    @Column(name = "analysis_type", length = 32, nullable = false)
    private String analysisType;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public Long getTaskItemId() { return taskItemId; }
    public void setTaskItemId(Long taskItemId) { this.taskItemId = taskItemId; }
    public String getScenarioId() { return scenarioId; }
    public void setScenarioId(String scenarioId) { this.scenarioId = scenarioId; }
    public String getAnalysisType() { return analysisType; }
    public void setAnalysisType(String analysisType) { this.analysisType = analysisType; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
