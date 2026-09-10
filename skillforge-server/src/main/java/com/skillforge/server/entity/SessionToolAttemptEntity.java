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
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** Durable two-phase Tool step, including its generation-fenced continuation claim. */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "t_session_tool_attempt",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_session_tool_attempt_session_step",
                        columnNames = {"session_id", "step_id"}),
                @UniqueConstraint(name = "uq_session_tool_attempt_assistant_message",
                        columnNames = "assistant_message_id")
        },
        indexes = {
                @Index(name = "idx_session_tool_attempt_session_state",
                        columnList = "session_id, state"),
                @Index(name = "idx_session_tool_attempt_recovery_lease",
                        columnList = "state, execution_lease_until")
        })
public class SessionToolAttemptEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Immutable intent origin. These columns identify the provider response that
    // created the attempt and must never be rewritten by a takeover claim.
    @Column(name = "session_id", nullable = false, updatable = false, length = 36)
    private String sessionId;

    @Column(name = "step_id", nullable = false, updatable = false, columnDefinition = "UUID")
    private UUID stepId;

    @Column(name = "history_epoch", nullable = false, updatable = false)
    private long historyEpoch;

    @Column(name = "origin_loop_id", nullable = false, updatable = false, length = 36)
    private String originLoopId;

    @Column(name = "origin_fence", nullable = false, updatable = false)
    private long originFence;

    @Column(name = "assistant_message_id", nullable = false, updatable = false)
    private Long assistantMessageId;

    @Column(name = "assistant_payload_hash", nullable = false, updatable = false,
            columnDefinition = "CHAR(64)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private String assistantPayloadHash;

    @Column(name = "pre_intent_max_message_id", nullable = false, updatable = false)
    private long preIntentMaxMessageId = -1L;

    @Column(name = "pre_intent_max_seq", nullable = false, updatable = false)
    private long preIntentMaxSeq = -1L;

    @Column(name = "manifest_json", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String manifestJson;

    @Column(name = "manifest_hash", nullable = false, updatable = false,
            columnDefinition = "CHAR(64)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private String manifestHash;

    @Column(name = "replay_safety", nullable = false, updatable = false, length = 32)
    private String replaySafety;

    // Mutable execution claim. A takeover advances generation/fence without ever
    // changing the immutable origin tuple above.
    @Column(name = "state", nullable = false, length = 48)
    private String state;

    @Column(name = "execution_loop_id", length = 36)
    private String executionLoopId;

    @Column(name = "execution_fence")
    private Long executionFence;

    @Column(name = "execution_owner_instance_id", length = 128)
    private String executionOwnerInstanceId;

    @Column(name = "execution_generation", nullable = false)
    private long executionGeneration = 0L;

    @Column(name = "claim_request_id", columnDefinition = "UUID")
    private UUID claimRequestId;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "execution_lease_until")
    private Instant executionLeaseUntil;

    // Closed result identity and archive preparation state.
    @Column(name = "result_batch_id", columnDefinition = "UUID")
    private UUID resultBatchId;

    @Column(name = "result_execution_generation")
    private Long resultExecutionGeneration;

    @Column(name = "result_execution_fence")
    private Long resultExecutionFence;

    @Column(name = "archive_preparation_state", nullable = false, length = 24)
    private String archivePreparationState = "NOT_STARTED";

    @Column(name = "archive_prepared_count", nullable = false)
    private int archivePreparedCount = 0;

    @Column(name = "archive_total_count", nullable = false)
    private int archiveTotalCount = 0;

    // W-R6-1: durable post-action continuation. Resolution creates PENDING;
    // SessionRunCoordinator alone may fill the claim tuple and advance it.
    @Column(name = "post_action_state", nullable = false, length = 24)
    private String postActionState = "NONE";

    @Column(name = "post_action_resolution_request_id", columnDefinition = "UUID")
    private UUID postActionResolutionRequestId;

    @Column(name = "post_action_result_batch_id", columnDefinition = "UUID")
    private UUID postActionResultBatchId;

    @Column(name = "post_action_kind", length = 40)
    private String postActionKind;

    @Column(name = "post_action_claim_request_id", columnDefinition = "UUID")
    private UUID postActionClaimRequestId;

    @Column(name = "post_action_loop_id", length = 36)
    private String postActionLoopId;

    @Column(name = "post_action_fence")
    private Long postActionFence;

    /** Internal once-only checkpoint; not part of the external resolution protocol. */
    @Column(name = "post_action_inbox_handoff_accepted", nullable = false)
    private boolean postActionInboxHandoffAccepted = false;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public SessionToolAttemptEntity() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public UUID getStepId() { return stepId; }
    public void setStepId(UUID stepId) { this.stepId = stepId; }
    public long getHistoryEpoch() { return historyEpoch; }
    public void setHistoryEpoch(long historyEpoch) { this.historyEpoch = historyEpoch; }
    public String getOriginLoopId() { return originLoopId; }
    public void setOriginLoopId(String originLoopId) { this.originLoopId = originLoopId; }
    public long getOriginFence() { return originFence; }
    public void setOriginFence(long originFence) { this.originFence = originFence; }
    public Long getAssistantMessageId() { return assistantMessageId; }
    public void setAssistantMessageId(Long assistantMessageId) { this.assistantMessageId = assistantMessageId; }
    public String getAssistantPayloadHash() { return assistantPayloadHash; }
    public void setAssistantPayloadHash(String assistantPayloadHash) { this.assistantPayloadHash = assistantPayloadHash; }
    public long getPreIntentMaxMessageId() { return preIntentMaxMessageId; }
    public void setPreIntentMaxMessageId(long preIntentMaxMessageId) { this.preIntentMaxMessageId = preIntentMaxMessageId; }
    public long getPreIntentMaxSeq() { return preIntentMaxSeq; }
    public void setPreIntentMaxSeq(long preIntentMaxSeq) { this.preIntentMaxSeq = preIntentMaxSeq; }
    public String getManifestJson() { return manifestJson; }
    public void setManifestJson(String manifestJson) { this.manifestJson = manifestJson; }
    public String getManifestHash() { return manifestHash; }
    public void setManifestHash(String manifestHash) { this.manifestHash = manifestHash; }
    public String getReplaySafety() { return replaySafety; }
    public void setReplaySafety(String replaySafety) { this.replaySafety = replaySafety; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getExecutionLoopId() { return executionLoopId; }
    public void setExecutionLoopId(String executionLoopId) { this.executionLoopId = executionLoopId; }
    public Long getExecutionFence() { return executionFence; }
    public void setExecutionFence(Long executionFence) { this.executionFence = executionFence; }
    public String getExecutionOwnerInstanceId() { return executionOwnerInstanceId; }
    public void setExecutionOwnerInstanceId(String executionOwnerInstanceId) { this.executionOwnerInstanceId = executionOwnerInstanceId; }
    public long getExecutionGeneration() { return executionGeneration; }
    public void setExecutionGeneration(long executionGeneration) { this.executionGeneration = executionGeneration; }
    public UUID getClaimRequestId() { return claimRequestId; }
    public void setClaimRequestId(UUID claimRequestId) { this.claimRequestId = claimRequestId; }
    public Instant getClaimedAt() { return claimedAt; }
    public void setClaimedAt(Instant claimedAt) { this.claimedAt = claimedAt; }
    public Instant getExecutionLeaseUntil() { return executionLeaseUntil; }
    public void setExecutionLeaseUntil(Instant executionLeaseUntil) { this.executionLeaseUntil = executionLeaseUntil; }
    public UUID getResultBatchId() { return resultBatchId; }
    public void setResultBatchId(UUID resultBatchId) { this.resultBatchId = resultBatchId; }
    public Long getResultExecutionGeneration() { return resultExecutionGeneration; }
    public void setResultExecutionGeneration(Long resultExecutionGeneration) { this.resultExecutionGeneration = resultExecutionGeneration; }
    public Long getResultExecutionFence() { return resultExecutionFence; }
    public void setResultExecutionFence(Long resultExecutionFence) { this.resultExecutionFence = resultExecutionFence; }
    public String getArchivePreparationState() { return archivePreparationState; }
    public void setArchivePreparationState(String archivePreparationState) { this.archivePreparationState = archivePreparationState; }
    public int getArchivePreparedCount() { return archivePreparedCount; }
    public void setArchivePreparedCount(int archivePreparedCount) { this.archivePreparedCount = archivePreparedCount; }
    public int getArchiveTotalCount() { return archiveTotalCount; }
    public void setArchiveTotalCount(int archiveTotalCount) { this.archiveTotalCount = archiveTotalCount; }
    public String getPostActionState() { return postActionState; }
    public void setPostActionState(String postActionState) { this.postActionState = postActionState; }
    public UUID getPostActionResolutionRequestId() { return postActionResolutionRequestId; }
    public void setPostActionResolutionRequestId(UUID value) { this.postActionResolutionRequestId = value; }
    public UUID getPostActionResultBatchId() { return postActionResultBatchId; }
    public void setPostActionResultBatchId(UUID value) { this.postActionResultBatchId = value; }
    public String getPostActionKind() { return postActionKind; }
    public void setPostActionKind(String postActionKind) { this.postActionKind = postActionKind; }
    public UUID getPostActionClaimRequestId() { return postActionClaimRequestId; }
    public void setPostActionClaimRequestId(UUID value) { this.postActionClaimRequestId = value; }
    public String getPostActionLoopId() { return postActionLoopId; }
    public void setPostActionLoopId(String postActionLoopId) { this.postActionLoopId = postActionLoopId; }
    public Long getPostActionFence() { return postActionFence; }
    public void setPostActionFence(Long postActionFence) { this.postActionFence = postActionFence; }
    public boolean isPostActionInboxHandoffAccepted() { return postActionInboxHandoffAccepted; }
    public void setPostActionInboxHandoffAccepted(boolean value) { this.postActionInboxHandoffAccepted = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
