package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 持久化的父 session pending 结果 mailbox —— 插入顺序即投递顺序。
 * 对应之前内存里的 pendingResults: Map<parentSessionId, Deque<String>>。
 */
@Entity
@Table(name = "t_subagent_pending_result", indexes = {
        @Index(name = "idx_subagent_pending_parent", columnList = "parentSessionId")
})
public class SubAgentPendingResultEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 36, nullable = false)
    private String parentSessionId;

    @Column(columnDefinition = "CLOB", nullable = false)
    private String payload;

    /** Monotonic sequence number per target session, for ordering */
    private Long seqNo;

    /** UUID for dedup */
    @Column(length = 36)
    private String messageId;

    /** Generalized target session (for peer messages in Phase 2). Same as parentSessionId for now. */
    @Column(length = 36)
    private String targetSessionId;

    /** Retry count for delivery failures */
    @Column(columnDefinition = "INT DEFAULT 0")
    private int retryCount = 0;

    /** null = pending (normal), DELIVERY_FAILED = max retries exceeded */
    @Column(length = 16)
    private String status;

    private Instant createdAt;

    public SubAgentPendingResultEntity() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getParentSessionId() {
        return parentSessionId;
    }

    public void setParentSessionId(String parentSessionId) {
        this.parentSessionId = parentSessionId;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public Long getSeqNo() {
        return seqNo;
    }

    public void setSeqNo(Long seqNo) {
        this.seqNo = seqNo;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getTargetSessionId() {
        return targetSessionId;
    }

    public void setTargetSessionId(String targetSessionId) {
        this.targetSessionId = targetSessionId;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
