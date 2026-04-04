package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "t_sub_agent_task")
public class SubAgentTaskEntity {

    @Id
    private String taskId;

    private String parentSessionId;

    private Long targetAgentId;

    private String targetAgentName;

    @Column(columnDefinition = "TEXT")
    private String taskDescription;

    private String status;

    @Column(columnDefinition = "TEXT")
    private String confirmQuestion;

    @Column(columnDefinition = "TEXT")
    private String confirmResponse;

    @Column(columnDefinition = "TEXT")
    private String result;

    @Column(columnDefinition = "TEXT")
    private String error;

    private int maxTurns;

    private LocalDateTime createdAt;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    public SubAgentTaskEntity() {
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
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
