package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "t_session_task_event")
public class SessionTaskEventEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "task_id", nullable = false, length = 36) private String taskId;
    @Column(name = "graph_session_id", nullable = false, length = 36) private String graphSessionId;
    @Column(name = "collab_run_id", nullable = false, length = 36) private String collabRunId;
    @Column(name = "attempt_id", length = 36) private String attemptId;
    @Column(name = "event_type", nullable = false, length = 32) private String eventType;
    @Column(name = "actor_session_id", length = 36) private String actorSessionId;
    @Column(name = "actor_agent_id") private Long actorAgentId;
    @Column(name = "from_status", length = 16) private String fromStatus;
    @Column(name = "to_status", length = 16) private String toStatus;
    @Column(name = "reason_code", length = 64) private String reasonCode;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    public SessionTaskEventEntity() {}
    public Long getId() { return id; } public void setId(Long value) { id = value; }
    public String getTaskId() { return taskId; } public void setTaskId(String value) { taskId = value; }
    public String getGraphSessionId() { return graphSessionId; } public void setGraphSessionId(String value) { graphSessionId = value; }
    public String getCollabRunId() { return collabRunId; } public void setCollabRunId(String value) { collabRunId = value; }
    public String getAttemptId() { return attemptId; } public void setAttemptId(String value) { attemptId = value; }
    public String getEventType() { return eventType; } public void setEventType(String value) { eventType = value; }
    public String getActorSessionId() { return actorSessionId; } public void setActorSessionId(String value) { actorSessionId = value; }
    public Long getActorAgentId() { return actorAgentId; } public void setActorAgentId(Long value) { actorAgentId = value; }
    public String getFromStatus() { return fromStatus; } public void setFromStatus(String value) { fromStatus = value; }
    public String getToStatus() { return toStatus; } public void setToStatus(String value) { toStatus = value; }
    public String getReasonCode() { return reasonCode; } public void setReasonCode(String value) { reasonCode = value; }
    public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant value) { createdAt = value; }
}
