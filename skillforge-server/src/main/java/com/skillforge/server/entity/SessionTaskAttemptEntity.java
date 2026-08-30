package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "t_session_task_attempt")
public class SessionTaskAttemptEntity {
    @Id @Column(length = 36) private String id;
    @Column(name = "task_id", nullable = false, length = 36) private String taskId;
    @Column(name = "graph_session_id", nullable = false, length = 36) private String graphSessionId;
    @Column(name = "collab_run_id", nullable = false, length = 36) private String collabRunId;
    @Column(name = "attempt_no", nullable = false) private int attemptNo;
    @Column(name = "worker_session_id", nullable = false, length = 36) private String workerSessionId;
    @Column(name = "worker_agent_id", nullable = false) private Long workerAgentId;
    @Column(name = "lease_token", nullable = false, length = 36) private String leaseToken;
    @Column(nullable = false, length = 16) private String status;
    @Column(name = "leased_at", nullable = false) private Instant leasedAt;
    @Column(name = "heartbeat_at", nullable = false) private Instant heartbeatAt;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "finished_at") private Instant finishedAt;
    @Column(name = "reason_code", length = 64) private String reasonCode;

    public SessionTaskAttemptEntity() {}
    public String getId() { return id; } public void setId(String value) { id = value; }
    public String getTaskId() { return taskId; } public void setTaskId(String value) { taskId = value; }
    public String getGraphSessionId() { return graphSessionId; } public void setGraphSessionId(String value) { graphSessionId = value; }
    public String getCollabRunId() { return collabRunId; } public void setCollabRunId(String value) { collabRunId = value; }
    public int getAttemptNo() { return attemptNo; } public void setAttemptNo(int value) { attemptNo = value; }
    public String getWorkerSessionId() { return workerSessionId; } public void setWorkerSessionId(String value) { workerSessionId = value; }
    public Long getWorkerAgentId() { return workerAgentId; } public void setWorkerAgentId(Long value) { workerAgentId = value; }
    public String getLeaseToken() { return leaseToken; } public void setLeaseToken(String value) { leaseToken = value; }
    public String getStatus() { return status; } public void setStatus(String value) { status = value; }
    public Instant getLeasedAt() { return leasedAt; } public void setLeasedAt(Instant value) { leasedAt = value; }
    public Instant getHeartbeatAt() { return heartbeatAt; } public void setHeartbeatAt(Instant value) { heartbeatAt = value; }
    public Instant getExpiresAt() { return expiresAt; } public void setExpiresAt(Instant value) { expiresAt = value; }
    public Instant getFinishedAt() { return finishedAt; } public void setFinishedAt(Instant value) { finishedAt = value; }
    public String getReasonCode() { return reasonCode; } public void setReasonCode(String value) { reasonCode = value; }
}
