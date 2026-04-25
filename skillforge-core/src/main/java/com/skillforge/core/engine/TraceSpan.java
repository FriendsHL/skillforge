package com.skillforge.core.engine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 链路追踪 span，记录 Agent Loop 中每个操作的详细信息。
 * <p>
 * spanType 枚举：
 * <ul>
 *   <li>AGENT_LOOP — 一次完整的用户请求 → loop 执行</li>
 *   <li>LLM_CALL — 单次 LLM 调用（一个 iteration 的模型推理）</li>
 *   <li>TOOL_CALL — 工具/Tool 执行</li>
 *   <li>ASK_USER — ask_user 阻塞等待用户回答</li>
 *   <li>COMPACT — 上下文压缩操作</li>
 * </ul>
 */
public class TraceSpan {

    private String id;
    private String sessionId;
    private String parentSpanId;
    private String spanType;
    private String name;
    private String input;
    private String output;
    private long startTimeMs;
    private long endTimeMs;
    private long durationMs;
    private int iterationIndex;
    private int inputTokens;
    private int outputTokens;
    private String modelId;
    private boolean success;
    private String error;
    private String toolUseId;
    /**
     * Optional free-form attributes (BUG-D observability). Lazy-initialized; iteration order is
     * insertion order (LinkedHashMap) so the JSON serialization stays deterministic.
     */
    private Map<String, Object> attributes;

    public TraceSpan() {
        this.id = UUID.randomUUID().toString();
        this.success = true;
    }

    public TraceSpan(String spanType, String name) {
        this();
        this.spanType = spanType;
        this.name = name;
        this.startTimeMs = System.currentTimeMillis();
    }

    /** 结束 span 并计算 durationMs。 */
    public TraceSpan end() {
        this.endTimeMs = System.currentTimeMillis();
        this.durationMs = this.endTimeMs - this.startTimeMs;
        return this;
    }

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

    public long getStartTimeMs() { return startTimeMs; }
    public void setStartTimeMs(long startTimeMs) { this.startTimeMs = startTimeMs; }

    public long getEndTimeMs() { return endTimeMs; }
    public void setEndTimeMs(long endTimeMs) { this.endTimeMs = endTimeMs; }

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

    public String getToolUseId() { return toolUseId; }
    public void setToolUseId(String toolUseId) { this.toolUseId = toolUseId; }

    public Map<String, Object> getAttributes() { return attributes; }
    public void setAttributes(Map<String, Object> attributes) { this.attributes = attributes; }

    /** Insert or overwrite a single attribute. Lazy-initializes the underlying map. */
    public void putAttribute(String key, Object value) {
        if (key == null) {
            return;
        }
        if (attributes == null) {
            attributes = new LinkedHashMap<>();
        }
        attributes.put(key, value);
    }
}
