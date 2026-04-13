package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 链路追踪 span 持久化实体。
 * 一条记录对应一个操作（LLM 调用、工具执行、ask_user 等）。
 */
@Entity
@Table(name = "t_trace_span", indexes = {
        @Index(name = "idx_trace_span_session", columnList = "sessionId"),
        @Index(name = "idx_trace_span_parent", columnList = "parentSpanId"),
        @Index(name = "idx_trace_span_start", columnList = "startTime")
})
public class TraceSpanEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(length = 36, nullable = false)
    private String sessionId;

    @Column(length = 36)
    private String parentSpanId;

    /** AGENT_LOOP / LLM_CALL / TOOL_CALL / ASK_USER / COMPACT */
    @Column(length = 16, nullable = false)
    private String spanType;

    @Column(length = 256)
    private String name;

    @Column(columnDefinition = "CLOB")
    private String input;

    @Column(columnDefinition = "CLOB")
    private String output;

    private Instant startTime;

    private Instant endTime;

    private long durationMs;

    private int iterationIndex;

    private int inputTokens;

    private int outputTokens;

    @Column(length = 128)
    private String modelId;

    private boolean success;

    @Column(columnDefinition = "TEXT")
    private String error;

    public TraceSpanEntity() {}

    // --- getters & setters ---

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getParentSpanId() { return parentSpanId; }
    public void setParentSpanId(String parentSpanId) { this.parentSpanId = parentSpanId; }

    public String getSpanType() { return spanType; }
    public void setSpanType(String spanType) { this.spanType = spanType; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getInput() { return input; }
    public void setInput(String input) { this.input = input; }

    public String getOutput() { return output; }
    public void setOutput(String output) { this.output = output; }

    public Instant getStartTime() { return startTime; }
    public void setStartTime(Instant startTime) { this.startTime = startTime; }

    public Instant getEndTime() { return endTime; }
    public void setEndTime(Instant endTime) { this.endTime = endTime; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public int getIterationIndex() { return iterationIndex; }
    public void setIterationIndex(int iterationIndex) { this.iterationIndex = iterationIndex; }

    public int getInputTokens() { return inputTokens; }
    public void setInputTokens(int inputTokens) { this.inputTokens = inputTokens; }

    public int getOutputTokens() { return outputTokens; }
    public void setOutputTokens(int outputTokens) { this.outputTokens = outputTokens; }

    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
}
