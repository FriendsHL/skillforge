package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only acknowledgement that an uncertain external outcome was resolved as unknown.
 *
 * <p>{@code attemptId} intentionally remains a scalar with no JPA relation or attempt FK,
 * so pruning attempt runtime cannot erase the audit. Every mapped column is non-updatable;
 * PostgreSQL privileges/trigger provide the authoritative append-only enforcement.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "t_session_tool_attempt_resolution_audit",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_session_tool_attempt_resolution_request",
                columnNames = "resolution_request_id"),
        indexes = {
                @Index(name = "idx_session_tool_attempt_audit_session_created",
                        columnList = "session_id, created_at"),
                @Index(name = "idx_session_tool_attempt_audit_attempt_created",
                        columnList = "attempt_id, created_at")
        })
public class SessionToolAttemptResolutionAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "resolution_request_id", nullable = false, updatable = false, columnDefinition = "UUID")
    private UUID resolutionRequestId;

    @Column(name = "session_id", nullable = false, updatable = false, length = 36)
    private String sessionId;

    /** Immutable scalar only: deliberately no attempt relation/FK. */
    @Column(name = "attempt_id", nullable = false, updatable = false)
    private Long attemptId;

    @Column(name = "step_id", nullable = false, updatable = false, columnDefinition = "UUID")
    private UUID stepId;

    @Column(name = "history_epoch", nullable = false, updatable = false)
    private long historyEpoch;

    @Column(name = "execution_generation", nullable = false, updatable = false)
    private long executionGeneration;

    @Column(name = "execution_fence", nullable = false, updatable = false)
    private long executionFence;

    /** Server-derived authenticated actor; never copied from request JSON. */
    @Column(name = "actor_id", nullable = false, updatable = false)
    private Long actorId;

    @Column(name = "actor_authority", nullable = false, updatable = false, length = 24)
    private String actorAuthority;

    /** Length-framed SHA-256 only; raw reason is never persisted here. */
    @Column(name = "reason_hash", nullable = false, updatable = false,
            columnDefinition = "CHAR(64)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private String reasonHash;

    @Column(name = "action", nullable = false, updatable = false, length = 40)
    private String action;

    /** IDs, exact message hashes and dispositions only; never inbox message bodies. */
    @Column(name = "inbox_dispositions_json", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String inboxDispositionsJson;

    @Column(name = "result_batch_id", nullable = false, updatable = false, columnDefinition = "UUID")
    private UUID resultBatchId;

    @Column(name = "outcome_state", nullable = false, updatable = false, length = 32)
    private String outcomeState;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public SessionToolAttemptResolutionAuditEntity() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public UUID getResolutionRequestId() { return resolutionRequestId; }
    public void setResolutionRequestId(UUID value) { this.resolutionRequestId = value; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public Long getAttemptId() { return attemptId; }
    public void setAttemptId(Long attemptId) { this.attemptId = attemptId; }
    public UUID getStepId() { return stepId; }
    public void setStepId(UUID stepId) { this.stepId = stepId; }
    public long getHistoryEpoch() { return historyEpoch; }
    public void setHistoryEpoch(long historyEpoch) { this.historyEpoch = historyEpoch; }
    public long getExecutionGeneration() { return executionGeneration; }
    public void setExecutionGeneration(long value) { this.executionGeneration = value; }
    public long getExecutionFence() { return executionFence; }
    public void setExecutionFence(long executionFence) { this.executionFence = executionFence; }
    public Long getActorId() { return actorId; }
    public void setActorId(Long actorId) { this.actorId = actorId; }
    public String getActorAuthority() { return actorAuthority; }
    public void setActorAuthority(String actorAuthority) { this.actorAuthority = actorAuthority; }
    public String getReasonHash() { return reasonHash; }
    public void setReasonHash(String reasonHash) { this.reasonHash = reasonHash; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getInboxDispositionsJson() { return inboxDispositionsJson; }
    public void setInboxDispositionsJson(String value) { this.inboxDispositionsJson = value; }
    public UUID getResultBatchId() { return resultBatchId; }
    public void setResultBatchId(UUID resultBatchId) { this.resultBatchId = resultBatchId; }
    public String getOutcomeState() { return outcomeState; }
    public void setOutcomeState(String outcomeState) { this.outcomeState = outcomeState; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
