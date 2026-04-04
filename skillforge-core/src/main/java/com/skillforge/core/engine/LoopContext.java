package com.skillforge.core.engine;

import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * 循环上下文，贯穿整个 Agent Loop 生命周期。
 */
public class LoopContext {

    private AgentDefinition agentDefinition;
    private List<Message> messages;
    private String sessionId;
    private Long userId;
    private String workingDirectory;
    private long totalInputTokens;
    private long totalOutputTokens;
    private int loopCount;
    private int maxLoops;
    private String subAgentTaskId;

    public LoopContext() {
        this.messages = new ArrayList<>();
        this.maxLoops = 50;
    }

    public AgentDefinition getAgentDefinition() {
        return agentDefinition;
    }

    public void setAgentDefinition(AgentDefinition agentDefinition) {
        this.agentDefinition = agentDefinition;
    }

    public List<Message> getMessages() {
        return messages;
    }

    public void setMessages(List<Message> messages) {
        this.messages = messages;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public void setWorkingDirectory(String workingDirectory) {
        this.workingDirectory = workingDirectory;
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

    public int getMaxLoops() {
        return maxLoops;
    }

    public void setMaxLoops(int maxLoops) {
        this.maxLoops = maxLoops;
    }

    public String getSubAgentTaskId() {
        return subAgentTaskId;
    }

    public void setSubAgentTaskId(String subAgentTaskId) {
        this.subAgentTaskId = subAgentTaskId;
    }

    /**
     * 累加输入 token 数量。
     */
    public void addInputTokens(long tokens) {
        this.totalInputTokens += tokens;
    }

    /**
     * 累加输出 token 数量。
     */
    public void addOutputTokens(long tokens) {
        this.totalOutputTokens += tokens;
    }

    /**
     * 递增循环计数。
     */
    public void incrementLoopCount() {
        this.loopCount++;
    }
}
