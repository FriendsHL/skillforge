package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.time.LocalDateTime;

@Entity
@Table(name = "t_agent")
@EntityListeners(AuditingEntityListener.class)
public class AgentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    private String modelId;

    @Column(columnDefinition = "TEXT")
    private String systemPrompt;

    @Column(columnDefinition = "TEXT")
    private String skillIds;

    /** JSON array of Tool names this agent can use. Null/empty = all tools available. */
    @Column(columnDefinition = "TEXT")
    private String toolIds;

    @Column(columnDefinition = "TEXT")
    private String config;

    @Column(columnDefinition = "TEXT")
    private String soulPrompt;

    @Column(columnDefinition = "TEXT")
    private String toolsPrompt;

    /** JSON: {"builtinRuleIds":["rule-id",...],"customRules":["text",...]} */
    @Column(columnDefinition = "TEXT")
    private String behaviorRules;

    /** JSON: {"version":1,"hooks":{"SessionStart":[...],...}} — see LifecycleHooksConfig */
    @Column(columnDefinition = "TEXT")
    private String lifecycleHooks;

    private Long ownerId;

    private boolean isPublic = false;

    private String status = "active";

    /** Max loop iterations for this agent (null = use engine default 25) */
    private Integer maxLoops;

    /** 执行模式: ask / auto,默认 ask */
    @Column(length = 16)
    private String executionMode = "ask";

    @Column(length = 36)
    private String activePromptVersionId;

    private boolean autoImprovePaused = false;

    private int abDeclineCount = 0;

    private Instant lastPromotedAt;

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    public AgentEntity() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public String getSkillIds() {
        return skillIds;
    }

    public void setSkillIds(String skillIds) {
        this.skillIds = skillIds;
    }

    public String getToolIds() {
        return toolIds;
    }

    public void setToolIds(String toolIds) {
        this.toolIds = toolIds;
    }

    public String getConfig() {
        return config;
    }

    public void setConfig(String config) {
        this.config = config;
    }

    public String getSoulPrompt() {
        return soulPrompt;
    }

    public void setSoulPrompt(String soulPrompt) {
        this.soulPrompt = soulPrompt;
    }

    public String getToolsPrompt() {
        return toolsPrompt;
    }

    public void setToolsPrompt(String toolsPrompt) {
        this.toolsPrompt = toolsPrompt;
    }

    public String getBehaviorRules() {
        return behaviorRules;
    }

    public void setBehaviorRules(String behaviorRules) {
        this.behaviorRules = behaviorRules;
    }

    public String getLifecycleHooks() {
        return lifecycleHooks;
    }

    public void setLifecycleHooks(String lifecycleHooks) {
        this.lifecycleHooks = lifecycleHooks;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }

    public boolean isPublic() {
        return isPublic;
    }

    public void setPublic(boolean isPublic) {
        this.isPublic = isPublic;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getMaxLoops() {
        return maxLoops;
    }

    public void setMaxLoops(Integer maxLoops) {
        this.maxLoops = maxLoops;
    }

    public String getExecutionMode() {
        return executionMode;
    }

    public void setExecutionMode(String executionMode) {
        this.executionMode = executionMode;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getActivePromptVersionId() {
        return activePromptVersionId;
    }

    public void setActivePromptVersionId(String activePromptVersionId) {
        this.activePromptVersionId = activePromptVersionId;
    }

    public boolean isAutoImprovePaused() {
        return autoImprovePaused;
    }

    public void setAutoImprovePaused(boolean autoImprovePaused) {
        this.autoImprovePaused = autoImprovePaused;
    }

    public int getAbDeclineCount() {
        return abDeclineCount;
    }

    public void setAbDeclineCount(int abDeclineCount) {
        this.abDeclineCount = abDeclineCount;
    }

    public Instant getLastPromotedAt() {
        return lastPromotedAt;
    }

    public void setLastPromotedAt(Instant lastPromotedAt) {
        this.lastPromotedAt = lastPromotedAt;
    }
}
