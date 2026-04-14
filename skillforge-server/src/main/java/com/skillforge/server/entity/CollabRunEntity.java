package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 多 Agent 协作运行实体。一次 CollabRun 包含一个 leader session 和若干 member session。
 */
@Entity
@Table(name = "t_collab_run")
public class CollabRunEntity {

    @Id
    @Column(length = 36)
    private String collabRunId;

    @Column(length = 36)
    private String leaderSessionId;

    /** RUNNING / COMPLETED / CANCELLED */
    @Column(length = 16)
    private String status;

    private int maxDepth = 2;

    private int maxTotalAgents = 20;

    private Instant createdAt;

    private Instant completedAt;

    public CollabRunEntity() {
    }

    public String getCollabRunId() {
        return collabRunId;
    }

    public void setCollabRunId(String collabRunId) {
        this.collabRunId = collabRunId;
    }

    public String getLeaderSessionId() {
        return leaderSessionId;
    }

    public void setLeaderSessionId(String leaderSessionId) {
        this.leaderSessionId = leaderSessionId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    public void setMaxDepth(int maxDepth) {
        this.maxDepth = maxDepth;
    }

    public int getMaxTotalAgents() {
        return maxTotalAgents;
    }

    public void setMaxTotalAgents(int maxTotalAgents) {
        this.maxTotalAgents = maxTotalAgents;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }
}
