package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "t_media_generation_job")
public class MediaGenerationJobEntity {
    @Id @Column(length = 36) private String id;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "session_id", length = 36, nullable = false) private String sessionId;
    @Column(name = "agent_id") private Long agentId;
    @Column(name = "source_tool_use_id", length = 128, nullable = false) private String sourceToolUseId;
    @Column(name = "idempotency_key", length = 128, nullable = false, unique = true) private String idempotencyKey;
    @Column(name = "media_type", length = 16, nullable = false) private String mediaType;
    @Column(length = 32, nullable = false) private String operation;
    @Column(length = 32, nullable = false) private String provider;
    @Column(length = 128, nullable = false) private String model;
    @Column(name = "provider_job_id", length = 255) private String providerJobId;
    @Column(length = 32, nullable = false) private String status;
    @Column(name = "request_json", columnDefinition = "TEXT", nullable = false) private String requestJson;
    @Column(name = "provider_metadata_json", columnDefinition = "TEXT") private String providerMetadataJson;
    @Column(name = "result_attachment_id", length = 36) private String resultAttachmentId;
    @Column(name = "attempt_count", nullable = false) private int attemptCount;
    @Column(name = "next_poll_at") private Instant nextPollAt;
    @Column(name = "lease_owner", length = 128) private String leaseOwner;
    @Column(name = "lease_expires_at") private Instant leaseExpiresAt;
    @Column(name = "error_code", length = 80) private String errorCode;
    @Column(name = "error_message", columnDefinition = "TEXT") private String errorMessage;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "submitted_at") private Instant submittedAt;
    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "completed_at") private Instant completedAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version private long version;

    public MediaGenerationJobEntity() { }
    public String getId() { return id; } public void setId(String v) { id = v; }
    public Long getUserId() { return userId; } public void setUserId(Long v) { userId = v; }
    public String getSessionId() { return sessionId; } public void setSessionId(String v) { sessionId = v; }
    public Long getAgentId() { return agentId; } public void setAgentId(Long v) { agentId = v; }
    public String getSourceToolUseId() { return sourceToolUseId; } public void setSourceToolUseId(String v) { sourceToolUseId = v; }
    public String getIdempotencyKey() { return idempotencyKey; } public void setIdempotencyKey(String v) { idempotencyKey = v; }
    public String getMediaType() { return mediaType; } public void setMediaType(String v) { mediaType = v; }
    public String getOperation() { return operation; } public void setOperation(String v) { operation = v; }
    public String getProvider() { return provider; } public void setProvider(String v) { provider = v; }
    public String getModel() { return model; } public void setModel(String v) { model = v; }
    public String getProviderJobId() { return providerJobId; } public void setProviderJobId(String v) { providerJobId = v; }
    public String getStatus() { return status; } public void setStatus(String v) { status = v; }
    public String getRequestJson() { return requestJson; } public void setRequestJson(String v) { requestJson = v; }
    public String getProviderMetadataJson() { return providerMetadataJson; } public void setProviderMetadataJson(String v) { providerMetadataJson = v; }
    public String getResultAttachmentId() { return resultAttachmentId; } public void setResultAttachmentId(String v) { resultAttachmentId = v; }
    public int getAttemptCount() { return attemptCount; } public void setAttemptCount(int v) { attemptCount = v; }
    public Instant getNextPollAt() { return nextPollAt; } public void setNextPollAt(Instant v) { nextPollAt = v; }
    public String getLeaseOwner() { return leaseOwner; } public void setLeaseOwner(String v) { leaseOwner = v; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; } public void setLeaseExpiresAt(Instant v) { leaseExpiresAt = v; }
    public String getErrorCode() { return errorCode; } public void setErrorCode(String v) { errorCode = v; }
    public String getErrorMessage() { return errorMessage; } public void setErrorMessage(String v) { errorMessage = v; }
    public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
    public Instant getSubmittedAt() { return submittedAt; } public void setSubmittedAt(Instant v) { submittedAt = v; }
    public Instant getStartedAt() { return startedAt; } public void setStartedAt(Instant v) { startedAt = v; }
    public Instant getCompletedAt() { return completedAt; } public void setCompletedAt(Instant v) { completedAt = v; }
    public Instant getUpdatedAt() { return updatedAt; } public void setUpdatedAt(Instant v) { updatedAt = v; }
    public long getVersion() { return version; }
}

