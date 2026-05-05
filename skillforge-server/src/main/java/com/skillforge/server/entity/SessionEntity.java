package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.time.LocalDateTime;

@Entity
@Table(name = "t_session")
@EntityListeners(AuditingEntityListener.class)
public class SessionEntity {

    /**
     * EVAL-V2 M3a §2.2: 来源标签，区分 production / eval 流量。
     *
     * <p>Single source of truth — 5 处过滤（SessionService.list*、TracesController.listTraces、
     * DashboardService 三个 usage query、CompactionService 两条压缩入口、两个 startup recovery）
     * 和 3 处 spawn（createSubSession / spawnMember / createBranchFromCheckpoint）都引用这两个常量，
     * 避免散落的 magic string。
     */
    public static final String ORIGIN_PRODUCTION = "production";
    public static final String ORIGIN_EVAL = "eval";

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

    /** 所属的多 Agent 协作运行 ID(nullable, 只有参与协作的 session 才有) */
    @Column(length = 36)
    private String collabRunId;

    /** Override max loop iterations for this session (null = use agent default) */
    private Integer maxLoops;

    /** If true, child agent gets stripped-down system prompt (no SOUL.md, TOOLS.md, memory) */
    @Column(columnDefinition = "BOOLEAN DEFAULT FALSE")
    private boolean lightContext = false;

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

    /**
     * OBS-4: 当前 user message 处理期间的 active root trace id —— 跨 agent / 跨 session
     * trace 串联根。每次收到新 user message 由 ChatService 清空（边界重置）；主 agent 第一个
     * trace 创建时由 ChatService 回填为 trace_id（自己当 root）；后续主 agent trace 创建时
     * 读这一字段继承同一 root；spawn child session 时复制父 session 的当前值给 child。
     * 默认 NULL：老 session 不参与 root_trace_id 链路。
     */
    @Column(name = "active_root_trace_id", length = 36)
    private String activeRootTraceId;

    /**
     * 最后一次抽取时间（成功或空结果都更新），可重复刷新。
     *
     * <p>历史语义是"终身锁"（非空即跳过后续抽取）。Memory v2 PR-3 起将以
     * {@link #lastExtractedMessageSeq} 与 {@code t_session_message.seq_no} 的
     * 大小关系判定是否需要再次抽取，本字段仅作为"上次抽取时间"做冷却 / 观测用途，
     * 但 PR-1 不动现有读写方为前向兼容，仅更新注释。
     */
    private Instant digestExtractedAt;

    /**
     * 增量抽取游标：已经被 digest 提取过的最大 {@code t_session_message.seq_no}。
     *
     * <p>类型必须与 {@code SessionMessageEntity#seqNo (long)} 对齐，避免溢出和签名不一致。
     * 0 表示从未做过增量抽取（V29 默认值，向后兼容旧 session）。PR-3 才会消费它。
     */
    @Column(name = "last_extracted_message_seq", nullable = false)
    private long lastExtractedMessageSeq = 0L;

    /**
     * EVAL-V2 Q1: id of an EvalScenarioEntity (or classpath / home scenario id)
     * that this session was opened to analyze. Populated by AnalyzeCaseModal
     * flow when an operator clicks "Analyze" on a case in the eval drawer;
     * NULL for all regular chat sessions. Used by the scenario detail drawer
     * to surface "previous analysis sessions for this case" so we don't fan
     * out duplicate analysis chats.
     *
     * <p>Length 64 (not 36) — UUIDs are 36 chars but
     * {@code BaseScenarioService.SAFE_ID} permits up to 64-char slugs (e.g.
     * {@code sc-bs-improved-2026-05}); narrowing to 36 would let a long-id
     * scenario pass validation, write to disk, then fail the Analyze →
     * createSession insert with a confusing truncation error (reviewer r2
     * W1, fixed before deploy).
     */
    @Column(name = "source_scenario_id", length = 64)
    private String sourceScenarioId;

    /**
     * EVAL-V2 M3a §2.2: 流量来源标签。{@link #ORIGIN_PRODUCTION} / {@link #ORIGIN_EVAL}。
     *
     * <p>V50 ALTER 加 NOT NULL DEFAULT 'production'，老 session 回填 production；新建 session
     * 默认也是 production。EvalOrchestrator 创建 eval session 时显式 setOrigin("eval")。
     * 子 session（SubAgent / collab member / compaction branch）由 spawn 路径继承父值，
     * 保证整树同 origin。
     */
    @Column(name = "origin", nullable = false, length = 16)
    private String origin = ORIGIN_PRODUCTION;

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    /**
     * 来源渠道标识（web / feishu / telegram / ...），由 controller 在返回前从
     * {@link com.skillforge.server.entity.ChannelConversationEntity} 注入；
     * 未绑定任何 channel 的 session 默认视为 "web"。非持久化字段。
     */
    @Transient
    private String channelPlatform;

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

    public String getCollabRunId() {
        return collabRunId;
    }

    public void setCollabRunId(String collabRunId) {
        this.collabRunId = collabRunId;
    }

    public Integer getMaxLoops() {
        return maxLoops;
    }

    public void setMaxLoops(Integer maxLoops) {
        this.maxLoops = maxLoops;
    }

    public boolean isLightContext() {
        return lightContext;
    }

    public void setLightContext(boolean lightContext) {
        this.lightContext = lightContext;
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

    public String getActiveRootTraceId() {
        return activeRootTraceId;
    }

    public void setActiveRootTraceId(String activeRootTraceId) {
        this.activeRootTraceId = activeRootTraceId;
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

    public long getLastExtractedMessageSeq() {
        return lastExtractedMessageSeq;
    }

    public void setLastExtractedMessageSeq(long lastExtractedMessageSeq) {
        this.lastExtractedMessageSeq = lastExtractedMessageSeq;
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

    public String getChannelPlatform() {
        return channelPlatform;
    }

    public void setChannelPlatform(String channelPlatform) {
        this.channelPlatform = channelPlatform;
    }

    public String getSourceScenarioId() {
        return sourceScenarioId;
    }

    public void setSourceScenarioId(String sourceScenarioId) {
        this.sourceScenarioId = sourceScenarioId;
    }

    public String getOrigin() {
        return origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }
}
