package com.skillforge.server.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.server.entity.AgentEntity;

/** Partial HTTP update: absent values stay null rather than inheriting entity creation defaults. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentUpdateRequest(
        String name,
        String description,
        String role,
        String modelId,
        String systemPrompt,
        String skillIds,
        String toolIds,
        String config,
        String soulPrompt,
        String toolsPrompt,
        String behaviorRules,
        String lifecycleHooks,
        Long ownerId,
        String status,
        Integer maxLoops,
        String executionMode,
        String thinkingMode,
        String reasoningEffort,
        Boolean thinkingVisible,
        @JsonProperty("public") Boolean isPublic,
        String mcpServerIds) {

    public AgentEntity toPatchEntity() {
        AgentEntity patch = new AgentEntity();
        patch.setName(name);
        patch.setDescription(description);
        patch.setRole(role);
        patch.setModelId(modelId);
        patch.setSystemPrompt(systemPrompt);
        patch.setSkillIds(skillIds);
        patch.setToolIds(toolIds);
        patch.setConfig(config);
        patch.setSoulPrompt(soulPrompt);
        patch.setToolsPrompt(toolsPrompt);
        patch.setBehaviorRules(behaviorRules);
        patch.setLifecycleHooks(lifecycleHooks);
        patch.setOwnerId(ownerId);
        patch.setStatus(status);
        patch.setMaxLoops(maxLoops);
        patch.setExecutionMode(executionMode);
        patch.setThinkingMode(thinkingMode);
        patch.setReasoningEffort(reasoningEffort);
        patch.setThinkingVisible(thinkingVisible);
        patch.setPublic(isPublic);
        patch.setMcpServerIds(mcpServerIds);
        return patch;
    }
}
