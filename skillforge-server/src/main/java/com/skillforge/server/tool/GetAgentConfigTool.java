package com.skillforge.server.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.service.AgentTargetResolver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class GetAgentConfigTool implements Tool {

    private static final TypeReference<List<Object>> LIST_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final AgentTargetResolver targetResolver;
    private final ObjectMapper objectMapper;

    public GetAgentConfigTool(AgentTargetResolver targetResolver, ObjectMapper objectMapper) {
        this.targetResolver = targetResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "GetAgentConfig";
    }

    @Override
    public String getDescription() {
        return "Read the full editable configuration for the current or a visible target agent. "
                + "Use GetAgentHooks for the separated system/user/agent-authored hook view.";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("targetAgentId", Map.of(
                "type", "integer",
                "description", "Optional target agent id. Omit to inspect the current session's agent."
        ));
        properties.put("targetAgentName", Map.of(
                "type", "string",
                "description", "Optional exact target agent name. Used only when targetAgentId is omitted."
        ));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        if (context == null || context.getSessionId() == null) {
            return SkillResult.error("sessionId is required to resolve visible agents");
        }
        try {
            AgentEntity target = targetResolver.resolveVisibleTarget(
                    context.getSessionId(),
                    input != null ? input.get("targetAgentId") : null,
                    input != null ? input.get("targetAgentName") : null);
            return SkillResult.success(objectMapper.writeValueAsString(toConfig(target)));
        } catch (RuntimeException e) {
            return SkillResult.error("get_agent_config_failed: " + e.getMessage());
        } catch (Exception e) {
            return SkillResult.error("get_agent_config_failed: " + e.getMessage());
        }
    }

    private Map<String, Object> toConfig(AgentEntity agent) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", agent.getId());
        out.put("name", agent.getName());
        out.put("description", agent.getDescription());
        out.put("role", agent.getRole());
        out.put("modelId", agent.getModelId());
        out.put("systemPrompt", agent.getSystemPrompt());
        out.put("soulPrompt", agent.getSoulPrompt());
        out.put("toolsPrompt", agent.getToolsPrompt());
        out.put("skills", parseStringList(agent.getSkillIds()));
        out.put("tools", parseStringList(agent.getToolIds()));
        out.put("config", parseObject(agent.getConfig()));
        out.put("configRaw", agent.getConfig());
        out.put("behaviorRules", parseObject(agent.getBehaviorRules()));
        out.put("behaviorRulesRaw", agent.getBehaviorRules());
        out.put("userLifecycleHooksRaw", agent.getLifecycleHooks());
        out.put("visibility", Boolean.TRUE.equals(agent.isPublic()) ? "public" : "private");
        out.put("status", agent.getStatus());
        out.put("ownerId", agent.getOwnerId());
        out.put("maxLoops", agent.getMaxLoops());
        out.put("executionMode", agent.getExecutionMode());
        out.put("thinkingMode", agent.getThinkingMode());
        out.put("reasoningEffort", agent.getReasoningEffort());
        out.put("createdAt", agent.getCreatedAt());
        out.put("updatedAt", agent.getUpdatedAt());
        return out;
    }

    private List<String> parseStringList(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return List.of();
        }
        try {
            List<Object> raw = objectMapper.readValue(rawJson, LIST_TYPE);
            List<String> out = new ArrayList<>();
            for (Object item : raw) {
                if (item != null) {
                    out.add(item.toString());
                }
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> parseObject(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(rawJson, MAP_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
