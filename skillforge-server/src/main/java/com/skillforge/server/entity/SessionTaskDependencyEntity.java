package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

@Entity
@Table(name = "t_session_task_dependency")
@IdClass(SessionTaskDependencyId.class)
public class SessionTaskDependencyEntity {
    @Column(name = "session_id", nullable = false, length = 36) private String sessionId;
    @Id @Column(name = "task_id", nullable = false, length = 36) private String taskId;
    @Id @Column(name = "blocked_by_task_id", nullable = false, length = 36) private String blockedByTaskId;
    public SessionTaskDependencyEntity() {}
    public SessionTaskDependencyEntity(String sessionId, String taskId, String blockedByTaskId) {
        this.sessionId = sessionId; this.taskId = taskId; this.blockedByTaskId = blockedByTaskId;
    }
    public String getSessionId() { return sessionId; } public void setSessionId(String v) { sessionId = v; }
    public String getTaskId() { return taskId; } public void setTaskId(String v) { taskId = v; }
    public String getBlockedByTaskId() { return blockedByTaskId; } public void setBlockedByTaskId(String v) { blockedByTaskId = v; }
}
