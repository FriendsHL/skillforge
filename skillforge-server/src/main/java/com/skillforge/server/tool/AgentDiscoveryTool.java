package com.skillforge.server.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.service.AgentTargetResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only discovery tool for agents that can be called from the current session.
 */
public class AgentDiscoveryTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(AgentDiscoveryTool.class);

    private final AgentTargetResolver targetResolver;
    private final ObjectMapper objectMapper;

    public AgentDiscoveryTool(AgentTargetResolver targetResolver, ObjectMapper objectMapper) {
        this.targetResolver = targetResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "AgentDiscovery";
    }

    @Override
    public String getDescription() {
        return "List agents that are visible and callable from the current session. "
                + "Use this before SubAgent when you know an agent name but not its numeric ID. "
                + "Supports optional fuzzy query filtering over name and description.";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of(
                "type", "string",
                "description", "Optional fuzzy filter over agent name, description, or exact numeric ID"
        ));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            String sessionId = context.getSessionId();
            if (sessionId == null || sessionId.isBlank()) {
                return SkillResult.error("No session in context");
            }
            String query = input != null && input.get("query") != null ? input.get("query").toString() : null;
            List<Map<String, Object>> agents = targetResolver.listVisibleTargets(sessionId, query).stream()
                    .map(this::toDto)
                    .toList();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("count", agents.size());
            payload.put("agents", agents);
            return SkillResult.success(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.error("AgentDiscoveryTool execute failed", e);
            return SkillResult.error("AgentDiscovery error: " + e.getMessage());
        }
    }

    private Map<String, Object> toDto(AgentEntity agent) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", agent.getId());
        dto.put("name", agent.getName());
        dto.put("description", agent.getDescription());
        dto.put("visibility", Boolean.TRUE.equals(agent.isPublic()) ? "public" : "private");
        dto.put("skills", parseStringList(agent.getSkillIds()));
        dto.put("tools", parseStringList(agent.getToolIds()));
        return dto;
    }

    private List<String> parseStringList(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return List.of();
        }
        try {
            List<Object> raw = objectMapper.readValue(rawJson, new TypeReference<List<Object>>() {});
            List<String> out = new ArrayList<>();
            for (Object item : raw) {
                if (item != null) {
                    out.add(item.toString());
                }
            }
            return out;
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }
}
