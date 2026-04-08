package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 持久化的 SubAgent run 记录 —— 对应 SubAgentRegistry 之前的内存 POJO。
 */
@Entity
@Table(name = "t_subagent_run", indexes = {
        @Index(name = "idx_subagent_run_parent", columnList = "parentSessionId")
})
public class SubAgentRunEntity {

    @Id
    @Column(length = 36)
    private String runId;

    @Column(length = 36)
    private String parentSessionId;

    @Column(length = 36)
    private String childSessionId;

    private Long childAgentId;

    @Column(length = 128)
    private String childAgentName;

    @Column(columnDefinition = "CLOB")
    private String task;

    /** RUNNING / COMPLETED / FAILED / CANCELLED */
    @Column(length = 16)
    private String status;

    @Column(columnDefinition = "CLOB")
    private String finalMessage;

    private Instant spawnedAt;

    private Instant completedAt;

    public SubAgentRunEntity() {
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getParentSessionId() {
        return parentSessionId;
    }

    public void setParentSessionId(String parentSessionId) {
        this.parentSessionId = parentSessionId;
    }

    public String getChildSessionId() {
        return childSessionId;
    }

    public void setChildSessionId(String childSessionId) {
        this.childSessionId = childSessionId;
    }

    public Long getChildAgentId() {
        return childAgentId;
    }

    public void setChildAgentId(Long childAgentId) {
        this.childAgentId = childAgentId;
    }

    public String getChildAgentName() {
        return childAgentName;
    }

    public void setChildAgentName(String childAgentName) {
        this.childAgentName = childAgentName;
    }

    public String getTask() {
        return task;
    }

    public void setTask(String task) {
        this.task = task;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getFinalMessage() {
        return finalMessage;
    }

    public void setFinalMessage(String finalMessage) {
        this.finalMessage = finalMessage;
    }

    public Instant getSpawnedAt() {
        return spawnedAt;
    }

    public void setSpawnedAt(Instant spawnedAt) {
        this.spawnedAt = spawnedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }
}
