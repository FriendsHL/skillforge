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

    public LlmTraceEntity() {}

    public String getTraceId() { return traceId; }
    public void setTraceId(String v) { this.traceId = v; }
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
}
