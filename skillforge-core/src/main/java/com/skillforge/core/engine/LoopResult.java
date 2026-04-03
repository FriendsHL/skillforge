package com.skillforge.core.engine;

import com.skillforge.core.model.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent Loop 循环结果，包含最终响应、完整消息历史和统计信息。
 */
public class LoopResult {

    private String finalResponse;
    private List<Message> messages;
    private long totalInputTokens;
    private long totalOutputTokens;
    private int loopCount;
    private List<ToolCallRecord> toolCalls;

    public LoopResult() {
        this.messages = new ArrayList<>();
        this.toolCalls = new ArrayList<>();
    }

    public LoopResult(String finalResponse, List<Message> messages,
                      long totalInputTokens, long totalOutputTokens,
                      int loopCount, List<ToolCallRecord> toolCalls) {
        this.finalResponse = finalResponse;
        this.messages = messages;
        this.totalInputTokens = totalInputTokens;
        this.totalOutputTokens = totalOutputTokens;
        this.loopCount = loopCount;
        this.toolCalls = toolCalls;
    }

    public String getFinalResponse() {
        return finalResponse;
    }

    public void setFinalResponse(String finalResponse) {
        this.finalResponse = finalResponse;
    }

    public List<Message> getMessages() {
        return messages;
    }

    public void setMessages(List<Message> messages) {
        this.messages = messages;
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

    public int getLoopCount() {
        return loopCount;
    }

    public void setLoopCount(int loopCount) {
        this.loopCount = loopCount;
    }

    public List<ToolCallRecord> getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(List<ToolCallRecord> toolCalls) {
        this.toolCalls = toolCalls;
    }
}
