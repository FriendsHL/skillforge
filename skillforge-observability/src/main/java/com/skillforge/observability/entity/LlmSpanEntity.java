package com.skillforge.observability.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/** 持久化映射 — {@code t_llm_span}（plan §3.2）。 */
@Entity
@Table(name = "t_llm_span")
public class LlmSpanEntity {

    @Id
    @Column(name = "span_id", length = 36, nullable = false)
    private String spanId;

    @Column(name = "trace_id", length = 36, nullable = false)
    private String traceId;

    @Column(name = "parent_span_id", length = 36)
    private String parentSpanId;

    @Column(name = "session_id", length = 36, nullable = false)
    private String sessionId;

    @Column(name = "agent_id")
    private Long agentId;

    @Column(name = "provider", length = 32)
    private String provider;

    @Column(name = "model", length = 128)
    private String model;

    @Column(name = "iteration_index", nullable = false)
    private int iterationIndex;

    @Column(name = "stream", nullable = false)
    private boolean stream = true;

    @Column(name = "input_summary", columnDefinition = "TEXT")
    private String inputSummary;

    @Column(name = "output_summary", columnDefinition = "TEXT")
    private String outputSummary;

    @Column(name = "input_blob_ref", columnDefinition = "TEXT")
    private String inputBlobRef;

    @Column(name = "output_blob_ref", columnDefinition = "TEXT")
    private String outputBlobRef;

    @Column(name = "raw_sse_blob_ref", columnDefinition = "TEXT")
    private String rawSseBlobRef;

    @Column(name = "blob_status", length = 16)
    private String blobStatus;

    @Column(name = "input_tokens", nullable = false)
    private int inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private int outputTokens;

    @Column(name = "cache_read_tokens")
    private Integer cacheReadTokens;

    @Column(name = "usage_json", columnDefinition = "JSONB")
    @JdbcTypeCode(SqlTypes.JSON)
    private String usageJson;

    @Column(name = "cost_usd", precision = 12, scale = 6)
    private BigDecimal costUsd;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "finish_reason", length = 32)
    private String finishReason;

    @Column(name = "request_id", length = 128)
    private String requestId;

    @Column(name = "reasoning_content", columnDefinition = "TEXT")
    private String reasoningContent;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    @Column(name = "error_type", length = 64)
    private String errorType;

    @Column(name = "tool_use_id", length = 64)
    private String toolUseId;

    @Column(name = "attributes_json", columnDefinition = "JSONB")
    @JdbcTypeCode(SqlTypes.JSON)
    private String attributesJson;

    @Column(name = "source", length = 8, nullable = false)
    private String source = "live";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public LlmSpanEntity() {}

    public String getSpanId() { return spanId; }
    public void setSpanId(String v) { this.spanId = v; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String v) { this.traceId = v; }
    public String getParentSpanId() { return parentSpanId; }
    public void setParentSpanId(String v) { this.parentSpanId = v; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String v) { this.sessionId = v; }
    public Long getAgentId() { return agentId; }
    public void setAgentId(Long v) { this.agentId = v; }
    public String getProvider() { return provider; }
    public void setProvider(String v) { this.provider = v; }
    public String getModel() { return model; }
    public void setModel(String v) { this.model = v; }
    public int getIterationIndex() { return iterationIndex; }
    public void setIterationIndex(int v) { this.iterationIndex = v; }
    public boolean isStream() { return stream; }
    public void setStream(boolean v) { this.stream = v; }
    public String getInputSummary() { return inputSummary; }
    public void setInputSummary(String v) { this.inputSummary = v; }
    public String getOutputSummary() { return outputSummary; }
    public void setOutputSummary(String v) { this.outputSummary = v; }
    public String getInputBlobRef() { return inputBlobRef; }
    public void setInputBlobRef(String v) { this.inputBlobRef = v; }
    public String getOutputBlobRef() { return outputBlobRef; }
    public void setOutputBlobRef(String v) { this.outputBlobRef = v; }
    public String getRawSseBlobRef() { return rawSseBlobRef; }
    public void setRawSseBlobRef(String v) { this.rawSseBlobRef = v; }
    public String getBlobStatus() { return blobStatus; }
    public void setBlobStatus(String v) { this.blobStatus = v; }
    public int getInputTokens() { return inputTokens; }
    public void setInputTokens(int v) { this.inputTokens = v; }
    public int getOutputTokens() { return outputTokens; }
    public void setOutputTokens(int v) { this.outputTokens = v; }
    public Integer getCacheReadTokens() { return cacheReadTokens; }
    public void setCacheReadTokens(Integer v) { this.cacheReadTokens = v; }
    public String getUsageJson() { return usageJson; }
    public void setUsageJson(String v) { this.usageJson = v; }
    public BigDecimal getCostUsd() { return costUsd; }
    public void setCostUsd(BigDecimal v) { this.costUsd = v; }
    public long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(long v) { this.latencyMs = v; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant v) { this.startedAt = v; }
    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant v) { this.endedAt = v; }
    public String getFinishReason() { return finishReason; }
    public void setFinishReason(String v) { this.finishReason = v; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String v) { this.requestId = v; }
    public String getReasoningContent() { return reasoningContent; }
    public void setReasoningContent(String v) { this.reasoningContent = v; }
    public String getError() { return error; }
    public void setError(String v) { this.error = v; }
    public String getErrorType() { return errorType; }
    public void setErrorType(String v) { this.errorType = v; }
    public String getToolUseId() { return toolUseId; }
    public void setToolUseId(String v) { this.toolUseId = v; }
    public String getAttributesJson() { return attributesJson; }
    public void setAttributesJson(String v) { this.attributesJson = v; }
    public String getSource() { return source; }
    public void setSource(String v) { this.source = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
