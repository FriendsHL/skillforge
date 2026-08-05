package com.skillforge.server.entity;

import java.io.Serializable;
import java.util.Objects;

public class SessionTaskDependencyId implements Serializable {
    private String taskId;
    private String blockedByTaskId;
    public SessionTaskDependencyId() {}
    public SessionTaskDependencyId(String taskId, String blockedByTaskId) {
        this.taskId = taskId; this.blockedByTaskId = blockedByTaskId;
    }
    public String getTaskId() { return taskId; } public void setTaskId(String v) { taskId = v; }
    public String getBlockedByTaskId() { return blockedByTaskId; } public void setBlockedByTaskId(String v) { blockedByTaskId = v; }
    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SessionTaskDependencyId that)) return false;
        return Objects.equals(taskId, that.taskId) && Objects.equals(blockedByTaskId, that.blockedByTaskId);
    }
    @Override public int hashCode() { return Objects.hash(taskId, blockedByTaskId); }
}
