package com.skillforge.core.skill;

import java.util.Collections;
import java.util.Set;

/**
 * Skill 执行上下文，提供运行时环境信息。
 */
public class SkillContext {

    private String workingDirectory;
    private String sessionId;
    private Long userId;
    private String toolUseId;
    private String approvalToken;

    /**
     * Memory v2 (PR-2): ids of memories already injected into the system prompt by the
     * {@code memoryProvider}. Read-only view forwarded from {@link com.skillforge.core.engine.LoopContext}
     * on every tool dispatch so tools (e.g. {@code memory_search}) can avoid double-presenting
     * the same memories that are already visible in the prompt. Always non-null.
     */
    private Set<Long> injectedMemoryIds = Collections.emptySet();

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

    public String getToolUseId() {
        return toolUseId;
    }

    public void setToolUseId(String toolUseId) {
        this.toolUseId = toolUseId;
    }

    public String getApprovalToken() {
        return approvalToken;
    }

    public void setApprovalToken(String approvalToken) {
        this.approvalToken = approvalToken;
    }

    /**
     * Memory v2 (PR-2): returns ids of memories already injected into the system prompt.
     * Always non-null; immutable view defends against tool mutation.
     */
    public Set<Long> getInjectedMemoryIds() {
        return injectedMemoryIds;
    }

    /**
     * Memory v2 (PR-2): set the injected memory ids. {@code null} collapses to an immutable
     * empty set; otherwise stored as an immutable {@link Set#copyOf} snapshot.
     */
    public void setInjectedMemoryIds(Set<Long> injectedMemoryIds) {
        this.injectedMemoryIds = injectedMemoryIds == null
                ? Collections.emptySet()
                : Set.copyOf(injectedMemoryIds);
    }
}
