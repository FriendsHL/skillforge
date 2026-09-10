package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/** Append-only identity receipt for one committed durable Session cancellation. */
@Entity
@Table(name = "t_session_cancel_receipt",
        indexes = @Index(
                name = "idx_session_cancel_receipt_session_created",
                columnList = "session_id, created_at, request_id"),
        uniqueConstraints = @UniqueConstraint(
                name = "uq_session_cancel_receipt_target",
                columnNames = {"session_id", "history_epoch", "target_loop_id",
                        "target_loop_fence", "target_owner_instance_id"}))
public class SessionCancellationReceiptEntity {

    @Id
    @Column(name = "request_id", nullable = false, updatable = false,
            columnDefinition = "UUID")
    private UUID requestId;

    @Column(name = "session_id", nullable = false, updatable = false, length = 36)
    private String sessionId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "history_epoch", nullable = false, updatable = false)
    private long historyEpoch;

    @Column(name = "target_loop_id", nullable = false, updatable = false, length = 36)
    private String targetLoopId;

    @Column(name = "target_loop_fence", nullable = false, updatable = false)
    private long targetLoopFence;

    @Column(name = "target_owner_instance_id", nullable = false, updatable = false, length = 128)
    private String targetOwnerInstanceId;

    @Column(name = "attempt_id", updatable = false)
    private Long attemptId;

    @Column(name = "execution_generation", updatable = false)
    private Long executionGeneration;

    @Column(name = "outcome", nullable = false, updatable = false, length = 48)
    private String outcome;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public SessionCancellationReceiptEntity() {
    }

    public UUID getRequestId() { return requestId; }
    public void setRequestId(UUID requestId) { this.requestId = requestId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public long getHistoryEpoch() { return historyEpoch; }
    public void setHistoryEpoch(long historyEpoch) { this.historyEpoch = historyEpoch; }
    public String getTargetLoopId() { return targetLoopId; }
    public void setTargetLoopId(String targetLoopId) { this.targetLoopId = targetLoopId; }
    public long getTargetLoopFence() { return targetLoopFence; }
    public void setTargetLoopFence(long targetLoopFence) { this.targetLoopFence = targetLoopFence; }
    public String getTargetOwnerInstanceId() { return targetOwnerInstanceId; }
    public void setTargetOwnerInstanceId(String value) { this.targetOwnerInstanceId = value; }
    public Long getAttemptId() { return attemptId; }
    public void setAttemptId(Long attemptId) { this.attemptId = attemptId; }
    public Long getExecutionGeneration() { return executionGeneration; }
    public void setExecutionGeneration(Long executionGeneration) {
        this.executionGeneration = executionGeneration;
    }
    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
