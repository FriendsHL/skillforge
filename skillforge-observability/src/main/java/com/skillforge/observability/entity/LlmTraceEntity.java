package com.skillforge.observability.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 持久化映射 — {@code t_llm_trace}（plan §3.1）。
 * 写入由 {@code PgLlmTraceStore.upsertTrace} 用原生 SQL ON CONFLICT 处理；entity 仅作为读路径投影。
 */
@Entity
@Table(name = "t_llm_trace")
public class LlmTraceEntity {

    @Id
    @Column(name = "trace_id", length = 36, nullable = false)
    private String traceId;

    /**
     * OBS-4 M0: 跨 agent / 跨 session trace 串联根。
     * 同一 user message 内主 agent 所有 trace + 派出的所有 subagent (含递归 child of child)
     * 全部共享同一 root_trace_id。Immutable: 一旦写入不再改。
     * <p>M0 schema 是 nullable（迁移 V45 仅加列 + 回填，不 SET NOT NULL）；
     * M1 写入路径改完后由后续 V46 SET NOT NULL。读路径从 M2 起按它聚合整树。
     */
    @Column(name = "root_trace_id", length = 36)
    private String rootTraceId;

    @Column(name = "session_id", length = 36, nullable = false)
    private String sessionId;

    @Column(name = "agent_id")
    private Long agentId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "root_name", length = 256)
    private String rootName;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "total_input_tokens", nullable = false)
    private int totalInputTokens;

    @Column(name = "total_output_tokens", nullable = false)
    private int totalOutputTokens;

    @Column(name = "total_cost_usd", precision = 12, scale = 6)
    private BigDecimal totalCostUsd;

    @Column(name = "source", length = 8, nullable = false)
    private String source = "live";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** OBS-2 M0: trace 结束时 finalize 写回 (ended_at - started_at) ms。 */
    @Column(name = "total_duration_ms", nullable = false)
    private long totalDurationMs;

    /** OBS-2 M0: 该 trace 内 t_llm_span where kind=tool 计数。 */
    @Column(name = "tool_call_count", nullable = false)
    private int toolCallCount;

    /** OBS-2 M0: 该 trace 内 t_llm_span where kind=event 计数。 */
    @Column(name = "event_count", nullable = false)
    private int eventCount;

    /** OBS-2 M0: running | ok | error | cancelled (应用层枚举校验)。 */
    @Column(name = "status", length = 16, nullable = false)
    private String status = "running";

    /** OBS-2 M0: 失败时摘要错误信息 (TEXT, 不限长)。 */
    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    /** OBS-2 M0: trace 主 agent 名 (与 root_name 同语义；新加为字段自描述)。 */
    @Column(name = "agent_name", length = 256)
    private String agentName;

    /**
     * EVAL-V2 M3a §2.2: 流量来源标签 — {@code production} 或 {@code eval}。
     *
     * <p>V50 ALTER 加 NOT NULL DEFAULT 'production'，老行回填 production；写入路径
     * (PgLlmTraceStore native SQL) 不显式 set，依赖 DB DEFAULT 即可（下一个 milestone
     * 才会让 EvalOrchestrator 创建 origin='eval' 的真实 session，触发 trace 自动继承）。
     * 仅作为读路径投影使用，配合 partial index {@code idx_trace_origin}。
     */
    @Column(name = "origin", nullable = false, length = 16)
    private String origin = "production";

    public LlmTraceEntity() {}

    public String getTraceId() { return traceId; }
    public void setTraceId(String v) { this.traceId = v; }
    public String getRootTraceId() { return rootTraceId; }
    public void setRootTraceId(String v) { this.rootTraceId = v; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String v) { this.sessionId = v; }
    public Long getAgentId() { return agentId; }
    public void setAgentId(Long v) { this.agentId = v; }
    public Long getUserId() { return userId; }
    public void setUserId(Long v) { this.userId = v; }
    public String getRootName() { return rootName; }
    public void setRootName(String v) { this.rootName = v; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant v) { this.startedAt = v; }
    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant v) { this.endedAt = v; }
    public int getTotalInputTokens() { return totalInputTokens; }
    public void setTotalInputTokens(int v) { this.totalInputTokens = v; }
    public int getTotalOutputTokens() { return totalOutputTokens; }
    public void setTotalOutputTokens(int v) { this.totalOutputTokens = v; }
    public BigDecimal getTotalCostUsd() { return totalCostUsd; }
    public void setTotalCostUsd(BigDecimal v) { this.totalCostUsd = v; }
    public String getSource() { return source; }
    public void setSource(String v) { this.source = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public long getTotalDurationMs() { return totalDurationMs; }
    public void setTotalDurationMs(long v) { this.totalDurationMs = v; }
    public int getToolCallCount() { return toolCallCount; }
    public void setToolCallCount(int v) { this.toolCallCount = v; }
    public int getEventCount() { return eventCount; }
    public void setEventCount(int v) { this.eventCount = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getError() { return error; }
    public void setError(String v) { this.error = v; }
    public String getAgentName() { return agentName; }
    public void setAgentName(String v) { this.agentName = v; }
    public String getOrigin() { return origin; }
    public void setOrigin(String v) { this.origin = v; }
}
