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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
