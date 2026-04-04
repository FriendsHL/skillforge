package com.skillforge.core.engine;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 子 Agent 任务模型，描述一个异步分派给子 Agent 的任务。
 */
public class SubAgentTask {

    private String taskId;
    private String parentSessionId;
    private Long targetAgentId;
    private String targetAgentName;
    private String taskDescription;
    private TaskStatus status;
    private String confirmQuestion;
    private String confirmResponse;
    private String result;
    private String error;
    private List<String> toolCallSummary;
    private int maxTurns;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    public SubAgentTask() {
        this.toolCallSummary = new ArrayList<>();
    }

    public SubAgentTask(String taskId, String parentSessionId, Long targetAgentId,
                        String targetAgentName, String taskDescription, int maxTurns) {
        this.taskId = taskId;
        this.parentSessionId = parentSessionId;
        this.targetAgentId = targetAgentId;
        this.targetAgentName = targetAgentName;
        this.taskDescription = taskDescription;
        this.maxTurns = maxTurns;
        this.status = TaskStatus.PENDING;
        this.toolCallSummary = new ArrayList<>();
        this.createdAt = LocalDateTime.now();
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getParentSessionId() {
        return parentSessionId;
    }

    public void setParentSessionId(String parentSessionId) {
        this.parentSessionId = parentSessionId;
    }

    public Long getTargetAgentId() {
        return targetAgentId;
    }

    public void setTargetAgentId(Long targetAgentId) {
        this.targetAgentId = targetAgentId;
    }

    public String getTargetAgentName() {
        return targetAgentName;
    }

    public void setTargetAgentName(String targetAgentName) {
        this.targetAgentName = targetAgentName;
    }

    public String getTaskDescription() {
        return taskDescription;
    }

    public void setTaskDescription(String taskDescription) {
        this.taskDescription = taskDescription;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public String getConfirmQuestion() {
        return confirmQuestion;
    }

    public void setConfirmQuestion(String confirmQuestion) {
        this.confirmQuestion = confirmQuestion;
    }

    public String getConfirmResponse() {
        return confirmResponse;
    }

    public void setConfirmResponse(String confirmResponse) {
        this.confirmResponse = confirmResponse;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public List<String> getToolCallSummary() {
        return toolCallSummary;
    }

    public void setToolCallSummary(List<String> toolCallSummary) {
        this.toolCallSummary = toolCallSummary;
    }

    public int getMaxTurns() {
        return maxTurns;
    }

    public void setMaxTurns(int maxTurns) {
        this.maxTurns = maxTurns;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }
}
