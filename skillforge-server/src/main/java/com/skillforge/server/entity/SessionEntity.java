package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "t_session")
@EntityListeners(AuditingEntityListener.class)
public class SessionEntity {

    @Id
    private String id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long agentId;

    private String title;

    private String status = "active";

    private int messageCount = 0;

    private long totalInputTokens = 0;

    private long totalOutputTokens = 0;

    @Column(columnDefinition = "CLOB")
    private String messagesJson;

    /** 运行时状态: idle / running / waiting_user / error */
    @Column(length = 32)
    private String runtimeStatus = "idle";

    /** 当前步骤描述 */
    @Column(length = 256)
    private String runtimeStep;

    /** 最近一次错误消息 */
    @Column(columnDefinition = "TEXT")
    private String runtimeError;

    /** 执行模式: ask / auto (覆盖 Agent 默认值) */
    @Column(length = 16)
    private String executionMode = "ask";

    /** 是否已通过 LLM 生成过精炼标题(避免重复触发) */
    @Column(columnDefinition = "BOOLEAN DEFAULT FALSE")
    private boolean smartTitled = false;

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    public SessionEntity() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getAgentId() {
        return agentId;
    }

    public void setAgentId(Long agentId) {
        this.agentId = agentId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getMessageCount() {
        return messageCount;
    }

    public void setMessageCount(int messageCount) {
        this.messageCount = messageCount;
    }

    public long getTotalInputTokens() {
        return totalInputTokens;
    }

    public void setTotalInputTokens(long totalInputTokens) {
        this.totalInputTokens = totalInputTokens;
    }

    public long getTotalOutputTokens() {
        return totalOutputTokens;
    }

    public void setTotalOutputTokens(long totalOutputTokens) {
        this.totalOutputTokens = totalOutputTokens;
    }

    public String getMessagesJson() {
        return messagesJson;
    }

    public void setMessagesJson(String messagesJson) {
        this.messagesJson = messagesJson;
    }

    public String getRuntimeStatus() {
        return runtimeStatus;
    }

    public void setRuntimeStatus(String runtimeStatus) {
        this.runtimeStatus = runtimeStatus;
    }

    public String getRuntimeStep() {
        return runtimeStep;
    }

    public void setRuntimeStep(String runtimeStep) {
        this.runtimeStep = runtimeStep;
    }

    public String getRuntimeError() {
        return runtimeError;
    }

    public void setRuntimeError(String runtimeError) {
        this.runtimeError = runtimeError;
    }

    public String getExecutionMode() {
        return executionMode;
    }

    public void setExecutionMode(String executionMode) {
        this.executionMode = executionMode;
    }

    public boolean isSmartTitled() {
        return smartTitled;
    }

    public void setSmartTitled(boolean smartTitled) {
        this.smartTitled = smartTitled;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
