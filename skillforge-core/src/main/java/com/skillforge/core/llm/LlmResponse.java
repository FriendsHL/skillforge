package com.skillforge.core.llm;

import com.skillforge.core.model.ToolUseBlock;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 响应模型，统一不同提供商的返回格式。
 */
public class LlmResponse {

    private String content;
    private String reasoningContent;
    private List<ToolUseBlock> toolUseBlocks = new ArrayList<>();
    private String stopReason;
    private Usage usage;

    public LlmResponse() {
    }

    /**
     * 是否因工具调用而停止。
     */
    public boolean isToolUse() {
        return "tool_use".equals(stopReason);
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getReasoningContent() {
        return reasoningContent;
    }

    public void setReasoningContent(String reasoningContent) {
        this.reasoningContent = reasoningContent;
    }

    public List<ToolUseBlock> getToolUseBlocks() {
        return toolUseBlocks;
    }

    public void setToolUseBlocks(List<ToolUseBlock> toolUseBlocks) {
        this.toolUseBlocks = toolUseBlocks;
    }

    public String getStopReason() {
        return stopReason;
    }

    public void setStopReason(String stopReason) {
        this.stopReason = stopReason;
    }

    public Usage getUsage() {
        return usage;
    }

    public void setUsage(Usage usage) {
        this.usage = usage;
    }

    /**
     * Token 用量统计。
     */
    public static class Usage {

        private int inputTokens;
        private int outputTokens;

        public Usage() {
        }

        public Usage(int inputTokens, int outputTokens) {
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
        }

        public int getInputTokens() {
            return inputTokens;
        }

        public void setInputTokens(int inputTokens) {
            this.inputTokens = inputTokens;
        }

        public int getOutputTokens() {
            return outputTokens;
        }

        public void setOutputTokens(int outputTokens) {
            this.outputTokens = outputTokens;
        }
    }
}
