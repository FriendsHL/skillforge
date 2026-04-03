package com.skillforge.server.dto;

import com.skillforge.core.engine.ToolCallRecord;

import java.util.List;

public class ChatResponse {

    private String response;
    private String sessionId;
    private long inputTokens;
    private long outputTokens;
    private List<ToolCallRecord> toolCalls;

    public ChatResponse() {
    }

    public String getResponse() {
        return response;
    }

    public void setResponse(String response) {
        this.response = response;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public long getInputTokens() {
        return inputTokens;
    }

    public void setInputTokens(long inputTokens) {
        this.inputTokens = inputTokens;
    }

    public long getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(long outputTokens) {
        this.outputTokens = outputTokens;
    }

    public List<ToolCallRecord> getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(List<ToolCallRecord> toolCalls) {
        this.toolCalls = toolCalls;
    }
}
