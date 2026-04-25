package com.skillforge.core.llm;

import com.skillforge.core.model.Message;
import com.skillforge.core.model.ReasoningEffort;
import com.skillforge.core.model.ThinkingMode;
import com.skillforge.core.model.ToolSchema;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 请求模型，封装调用参数。
 */
public class LlmRequest {

    private String systemPrompt;
    private List<Message> messages = new ArrayList<>();
    private List<ToolSchema> tools = new ArrayList<>();
    private String model;
    private int maxTokens = 4096;
    private double temperature = 0.7;
    /** Agent-level thinking-mode override. Null or AUTO = provider default. */
    private ThinkingMode thinkingMode;
    /** Reasoning-effort hint for reasoning-capable families. Null = provider default. */
    private ReasoningEffort reasoningEffort;

    public LlmRequest() {
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public List<Message> getMessages() {
        return messages;
    }

    public void setMessages(List<Message> messages) {
        this.messages = messages;
    }

    public List<ToolSchema> getTools() {
        return tools;
    }

    public void setTools(List<ToolSchema> tools) {
        this.tools = tools;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public ThinkingMode getThinkingMode() {
        return thinkingMode;
    }

    public void setThinkingMode(ThinkingMode thinkingMode) {
        this.thinkingMode = thinkingMode;
    }

    public ReasoningEffort getReasoningEffort() {
        return reasoningEffort;
    }

    public void setReasoningEffort(ReasoningEffort reasoningEffort) {
        this.reasoningEffort = reasoningEffort;
    }
}
