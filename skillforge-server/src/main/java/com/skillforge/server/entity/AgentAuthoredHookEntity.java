package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * Lifecycle hook binding proposed by an agent. The row is dispatchable only when
 * {@code reviewState=APPROVED && enabled=true}.
 */
@Entity
@Table(name = "t_agent_authored_hook")
@EntityListeners(AuditingEntityListener.class)
public class AgentAuthoredHookEntity {

    public static final String STATE_PENDING = "PENDING";
    public static final String STATE_APPROVED = "APPROVED";
    public static final String STATE_REJECTED = "REJECTED";
    public static final String STATE_RETIRED = "RETIRED";

    public static final String METHOD_KIND_COMPILED = "COMPILED";
    public static final String METHOD_KIND_BUILTIN = "BUILTIN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "target_agent_id", nullable = false)
    private Long targetAgentId;

    @Column(name = "author_agent_id", nullable = false)
    private Long authorAgentId;

    @Column(name = "author_session_id", length = 36)
    private String authorSessionId;

    @Column(nullable = false, length = 64)
    private String event;

    @Column(name = "method_kind", nullable = false, length = 32)
    private String methodKind;

    @Column(name = "method_id")
    private Long methodId;

    @Column(name = "method_ref", nullable = false, length = 128)
    private String methodRef;

    @Column(name = "method_version_hash", length = 128)
    private String methodVersionHash;

    @Column(name = "args_json", columnDefinition = "TEXT")
    private String argsJson;

    @Column(name = "timeout_seconds", nullable = false)
    private int timeoutSeconds = 30;

    @Column(name = "failure_policy", nullable = false, length = 32)
    private String failurePolicy = "CONTINUE";

    @Column(nullable = false)
    private boolean async = false;

    @Column(name = "display_name", length = 256)
    private String displayName;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "review_state", nullable = false, length = 32)
    private String reviewState = STATE_PENDING;

    @Column(name = "review_note", columnDefinition = "TEXT")
    private String reviewNote;

    @Column(name = "reviewed_by_user_id")
    private Long reviewedByUserId;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "parent_hook_id")
    private Long parentHookId;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "usage_count", nullable = false)
    private long usageCount = 0;

    @Column(name = "success_count", nullable = false)
    private long successCount = 0;

    @Column(name = "failure_count", nullable = false)
    private long failureCount = 0;

    @Column(name = "last_executed_at")
    private Instant lastExecutedAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTargetAgentId() { return targetAgentId; }
    public void setTargetAgentId(Long targetAgentId) { this.targetAgentId = targetAgentId; }

    public Long getAuthorAgentId() { return authorAgentId; }
    public void setAuthorAgentId(Long authorAgentId) { this.authorAgentId = authorAgentId; }

    public String getAuthorSessionId() { return authorSessionId; }
    public void setAuthorSessionId(String authorSessionId) { this.authorSessionId = authorSessionId; }

    public String getEvent() { return event; }
    public void setEvent(String event) { this.event = event; }

    public String getMethodKind() { return methodKind; }
    public void setMethodKind(String methodKind) { this.methodKind = methodKind; }

    public Long getMethodId() { return methodId; }
    public void setMethodId(Long methodId) { this.methodId = methodId; }

    public String getMethodRef() { return methodRef; }
    public void setMethodRef(String methodRef) { this.methodRef = methodRef; }

    public String getMethodVersionHash() { return methodVersionHash; }
    public void setMethodVersionHash(String methodVersionHash) { this.methodVersionHash = methodVersionHash; }

    public String getArgsJson() { return argsJson; }
    public void setArgsJson(String argsJson) { this.argsJson = argsJson; }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    public String getFailurePolicy() { return failurePolicy; }
    public void setFailurePolicy(String failurePolicy) { this.failurePolicy = failurePolicy; }

    public boolean isAsync() { return async; }
    public void setAsync(boolean async) { this.async = async; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getReviewState() { return reviewState; }
    public void setReviewState(String reviewState) { this.reviewState = reviewState; }

    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String reviewNote) { this.reviewNote = reviewNote; }

    public Long getReviewedByUserId() { return reviewedByUserId; }
    public void setReviewedByUserId(Long reviewedByUserId) { this.reviewedByUserId = reviewedByUserId; }

    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }

    public Long getParentHookId() { return parentHookId; }
    public void setParentHookId(Long parentHookId) { this.parentHookId = parentHookId; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public long getUsageCount() { return usageCount; }
    public void setUsageCount(long usageCount) { this.usageCount = usageCount; }

    public long getSuccessCount() { return successCount; }
    public void setSuccessCount(long successCount) { this.successCount = successCount; }

    public long getFailureCount() { return failureCount; }
    public void setFailureCount(long failureCount) { this.failureCount = failureCount; }

    public Instant getLastExecutedAt() { return lastExecutedAt; }
    public void setLastExecutedAt(Instant lastExecutedAt) { this.lastExecutedAt = lastExecutedAt; }

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
