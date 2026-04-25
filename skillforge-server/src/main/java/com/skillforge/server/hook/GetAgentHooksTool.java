package com.skillforge.server.hook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.service.AgentTargetResolver;
import com.skillforge.server.service.LifecycleHookViewService;

import java.util.LinkedHashMap;
import java.util.Map;

public class GetAgentHooksTool implements Tool {

    private final AgentTargetResolver targetResolver;
    private final LifecycleHookViewService viewService;
    private final ObjectMapper objectMapper;

    public GetAgentHooksTool(AgentTargetResolver targetResolver,
                             LifecycleHookViewService viewService,
                             ObjectMapper objectMapper) {
        this.targetResolver = targetResolver;
        this.viewService = viewService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "GetAgentHooks";
    }

    @Override
    public String getDescription() {
        return "Read effective lifecycle hooks for the current or a visible target agent, grouped by system/user/agent-authored source.";
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
            return SkillResult.success(toJson(viewService.getAgentHooks(target.getId())));
        } catch (RuntimeException e) {
            return SkillResult.error("get_agent_hooks_failed: " + e.getMessage());
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return value.toString();
        }
    }
}
