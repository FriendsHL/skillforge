package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
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

    /** 父 session id(SubAgent 异步派发生成的子 session) */
    @Column(length = 36)
    private String parentSessionId;

    /** 嵌套深度:根 session=0, 子=1, 孙=2 */
    @Column(columnDefinition = "INT DEFAULT 0")
    private int depth = 0;

    /** SubAgent 派发时的 runId(用于结果回推时定位) */
    @Column(length = 36)
    private String subAgentRunId;

    /** 迄今为止执行过的 light 压缩次数 */
    @Column(columnDefinition = "INT DEFAULT 0")
    private int lightCompactCount = 0;

    /** 迄今为止执行过的 full 压缩次数 */
    @Column(columnDefinition = "INT DEFAULT 0")
    private int fullCompactCount = 0;

    /** 最近一次压缩完成的时间 */
    private Instant lastCompactedAt;

    /** 最近一次压缩时的 messageCount, 用于 idempotency 守卫 */
    @Column(columnDefinition = "INT DEFAULT 0")
    private int lastCompactedAtMessageCount = 0;

    /** 所有压缩累计释放的 token 数 */
    @Column(columnDefinition = "BIGINT DEFAULT 0")
    private long totalTokensReclaimed = 0;

    /**
     * 最近一次"真实 user 消息"到达的时间。用于 B3 engine-gap 的 idle 检测 ——
     * 不能用 updatedAt, 因为后者会被内部 runtime_status 写入、smart title 等无关操作污染。
     * createSession 时写当前时间; chatAsync 追加 user 消息前更新。
     */
    private Instant lastUserMessageAt;

    /** Loop 执行完毕的时间(正常/取消/异常都设置) */
    private Instant completedAt;

    /** 会话摘要已被 digest 提取的时间(用于增量记忆提取) */
    private Instant digestExtractedAt;

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

    public String getParentSessionId() {
        return parentSessionId;
    }

    public void setParentSessionId(String parentSessionId) {
        this.parentSessionId = parentSessionId;
    }

    public int getDepth() {
        return depth;
    }

    public void setDepth(int depth) {
        this.depth = depth;
    }

    public String getSubAgentRunId() {
        return subAgentRunId;
    }

    public void setSubAgentRunId(String subAgentRunId) {
        this.subAgentRunId = subAgentRunId;
    }

    public int getLightCompactCount() {
        return lightCompactCount;
    }

    public void setLightCompactCount(int lightCompactCount) {
        this.lightCompactCount = lightCompactCount;
    }

    public Instant getLastUserMessageAt() {
        return lastUserMessageAt;
    }

    public void setLastUserMessageAt(Instant lastUserMessageAt) {
        this.lastUserMessageAt = lastUserMessageAt;
    }

    public int getFullCompactCount() {
        return fullCompactCount;
    }

    public void setFullCompactCount(int fullCompactCount) {
        this.fullCompactCount = fullCompactCount;
    }

    public Instant getLastCompactedAt() {
        return lastCompactedAt;
    }

    public void setLastCompactedAt(Instant lastCompactedAt) {
        this.lastCompactedAt = lastCompactedAt;
    }

    public int getLastCompactedAtMessageCount() {
        return lastCompactedAtMessageCount;
    }

    public void setLastCompactedAtMessageCount(int lastCompactedAtMessageCount) {
        this.lastCompactedAtMessageCount = lastCompactedAtMessageCount;
    }

    public long getTotalTokensReclaimed() {
        return totalTokensReclaimed;
    }

    public void setTotalTokensReclaimed(long totalTokensReclaimed) {
        this.totalTokensReclaimed = totalTokensReclaimed;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public Instant getDigestExtractedAt() {
        return digestExtractedAt;
    }

    public void setDigestExtractedAt(Instant digestExtractedAt) {
        this.digestExtractedAt = digestExtractedAt;
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
