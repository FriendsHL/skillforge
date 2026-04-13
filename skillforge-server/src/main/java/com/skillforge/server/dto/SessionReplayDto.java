package com.skillforge.server.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Session Replay 视图：将扁平消息列表重构为 turn → iteration → tool call 三层结构。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SessionReplayDto {

    private String sessionId;
    private String status;
    private String runtimeStatus;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<Turn> turns;

    public static class Turn {
        private int turnIndex;
        private String userMessage;
        private String finalResponse;
        private int iterationCount;
        private int inputTokens;
        private int outputTokens;
        private String modelId;
        private Long durationMs;
        private List<Iteration> iterations;

        public int getTurnIndex() { return turnIndex; }
        public void setTurnIndex(int turnIndex) { this.turnIndex = turnIndex; }
        public String getUserMessage() { return userMessage; }
        public void setUserMessage(String userMessage) { this.userMessage = userMessage; }
        public String getFinalResponse() { return finalResponse; }
        public void setFinalResponse(String finalResponse) { this.finalResponse = finalResponse; }
        public int getIterationCount() { return iterationCount; }
        public void setIterationCount(int iterationCount) { this.iterationCount = iterationCount; }
        public int getInputTokens() { return inputTokens; }
        public void setInputTokens(int inputTokens) { this.inputTokens = inputTokens; }
        public int getOutputTokens() { return outputTokens; }
        public void setOutputTokens(int outputTokens) { this.outputTokens = outputTokens; }
        public String getModelId() { return modelId; }
        public void setModelId(String modelId) { this.modelId = modelId; }
        public Long getDurationMs() { return durationMs; }
        public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
        public List<Iteration> getIterations() { return iterations; }
        public void setIterations(List<Iteration> iterations) { this.iterations = iterations; }
    }

    public static class Iteration {
        private int iterationIndex;
        private String assistantText;
        private List<ReplayToolCall> toolCalls;

        public int getIterationIndex() { return iterationIndex; }
        public void setIterationIndex(int iterationIndex) { this.iterationIndex = iterationIndex; }
        public String getAssistantText() { return assistantText; }
        public void setAssistantText(String assistantText) { this.assistantText = assistantText; }
        public List<ReplayToolCall> getToolCalls() { return toolCalls; }
        public void setToolCalls(List<ReplayToolCall> toolCalls) { this.toolCalls = toolCalls; }
    }

    public static class ReplayToolCall {
        private String id;
        private String name;
        private Map<String, Object> input;
        private String output;
        private boolean success;
        private Long durationMs;
        private Long timestamp;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Map<String, Object> getInput() { return input; }
        public void setInput(Map<String, Object> input) { this.input = input; }
        public String getOutput() { return output; }
        public void setOutput(String output) { this.output = output; }
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public Long getDurationMs() { return durationMs; }
        public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
        public Long getTimestamp() { return timestamp; }
        public void setTimestamp(Long timestamp) { this.timestamp = timestamp; }
    }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRuntimeStatus() { return runtimeStatus; }
    public void setRuntimeStatus(String runtimeStatus) { this.runtimeStatus = runtimeStatus; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public List<Turn> getTurns() { return turns; }
    public void setTurns(List<Turn> turns) { this.turns = turns; }
}
