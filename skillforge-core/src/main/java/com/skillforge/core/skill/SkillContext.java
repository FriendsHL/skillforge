package com.skillforge.core.skill;

/**
 * Skill 执行上下文，提供运行时环境信息。
 */
public class SkillContext {

    private String workingDirectory;
    private String sessionId;
    private Long userId;

    public SkillContext() {
    }

    public SkillContext(String workingDirectory, String sessionId, Long userId) {
        this.workingDirectory = workingDirectory;
        this.sessionId = sessionId;
        this.userId = userId;
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public void setWorkingDirectory(String workingDirectory) {
        this.workingDirectory = workingDirectory;
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
}
