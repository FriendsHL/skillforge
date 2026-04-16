package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "t_eval_session")
public class EvalSessionEntity {

    @Id
    @Column(length = 36)
    private String sessionId;

    @Column(length = 36)
    private String evalRunId;

    @Column(length = 36)
    private String scenarioId;

    @Column(length = 16)
    private String status;

    private Instant startedAt;

    private Instant completedAt;

    public EvalSessionEntity() {
    }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getEvalRunId() { return evalRunId; }
    public void setEvalRunId(String evalRunId) { this.evalRunId = evalRunId; }

    public String getScenarioId() { return scenarioId; }
    public void setScenarioId(String scenarioId) { this.scenarioId = scenarioId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
