package com.skillforge.server.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.confirm.ToolApprovalRegistry;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.service.AgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Creates an Agent only after the engine completes explicit human approval.
 */
public class CreateAgentTool implements Tool {

    public static final String NAME = "CreateAgent";

    private static final Logger log = LoggerFactory.getLogger(CreateAgentTool.class);
    private static final List<String> EXECUTION_MODES = List.of("ask", "auto");
    private static final List<String> THINKING_MODES = List.of("auto", "enabled", "disabled");
    private static final List<String> REASONING_EFFORTS = List.of("low", "medium", "high", "max");

    private final AgentService agentService;
    private final ObjectMapper objectMapper;
    private final ToolApprovalRegistry approvalRegistry;

    public CreateAgentTool(AgentService agentService,
                           ObjectMapper objectMapper,
                           ToolApprovalRegistry approvalRegistry) {
        this.agentService = agentService;
        this.objectMapper = objectMapper;
        this.approvalRegistry = approvalRegistry;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Create a new Agent after explicit human approval. "
                + "Use this when the user asks to persist a reusable agent configuration. "
                + "The engine will show a confirmation card before the agent is created.";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", Map.of(
                "type", "string",
                "description", "Required agent name"
        ));
        properties.put("description", Map.of(
                "type", "string",
                "description", "Short user-facing description"
        ));
        properties.put("role", Map.of(
                "type", "string",
                "description", "Optional role label, for example researcher, reviewer, analyzer"
        ));
        properties.put("modelId", Map.of(
                "type", "string",
                "description", "Optional model id, for example provider:model-name"
        ));
        properties.put("systemPrompt", Map.of(
                "type", "string",
                "description", "System prompt for the new agent"
        ));
        properties.put("skills", Map.of(
                "type", "array",
                "description", "Optional skill ids available to the agent",
                "items", Map.of("type", "string")
        ));
        properties.put("tools", Map.of(
                "type", "array",
                "description", "Optional Java tool names available to the agent. Empty or omitted means all tools.",
                "items", Map.of("type", "string")
        ));
        properties.put("visibility", Map.of(
                "type", "string",
                "description", "private or public. Defaults to private.",
                "enum", List.of("private", "public")
        ));
        properties.put("executionMode", Map.of(
                "type", "string",
                "description", "ask or auto. Defaults to ask.",
                "enum", EXECUTION_MODES
        ));
        properties.put("maxLoops", Map.of(
                "type", "integer",
                "description", "Optional max loop iterations, 1 to 100"
        ));
        properties.put("thinkingMode", Map.of(
                "type", "string",
                "description", "auto, enabled, or disabled",
                "enum", THINKING_MODES
        ));
        properties.put("reasoningEffort", Map.of(
                "type", "string",
                "description", "low, medium, high, or max",
                "enum", REASONING_EFFORTS
        ));
        properties.put("behaviorRules", Map.of(
                "type", "object",
                "description", "Optional behavior rules JSON: builtinRuleIds and customRules"
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("name"));
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            if (input == null) {
                input = Map.of();
            }
            AgentEntity agent = toAgent(input, context);
            if (!approvalRegistry.consume(
                    context.getApprovalToken(), context.getSessionId(), getName(), context.getToolUseId())) {
                return SkillResult.error("CreateAgent requires explicit user approval before execution.");
            }

            AgentEntity created = agentService.createAgent(agent);
            return SkillResult.success(objectMapper.writeValueAsString(toDto(created)));
        } catch (IllegalArgumentException e) {
            return SkillResult.validationError(e.getMessage());
        } catch (Exception e) {
            log.error("CreateAgentTool execute failed", e);
            return SkillResult.error("CreateAgent error: " + e.getMessage());
        }
    }

    private AgentEntity toAgent(Map<String, Object> input, SkillContext context) throws JsonProcessingException {
        String name = requiredString(input.get("name"), "name");
        AgentEntity agent = new AgentEntity();
        agent.setName(name);
        agent.setDescription(optionalString(input.get("description")));
        agent.setRole(optionalString(input.get("role")));
        agent.setModelId(optionalString(input.get("modelId")));
        agent.setSystemPrompt(optionalString(input.get("systemPrompt")));
        agent.setOwnerId(context.getUserId());
        agent.setStatus("active");
        agent.setPublic(parseVisibility(input));
        agent.setExecutionMode(enumValue(input.get("executionMode"), "executionMode", EXECUTION_MODES, "ask"));

        String thinkingMode = enumValue(input.get("thinkingMode"), "thinkingMode", THINKING_MODES, null);
        if (thinkingMode != null && !"auto".equals(thinkingMode)) {
            agent.setThinkingMode(thinkingMode);
        }
        agent.setReasoningEffort(enumValue(input.get("reasoningEffort"), "reasoningEffort", REASONING_EFFORTS, null));

        Integer maxLoops = intValue(input.get("maxLoops"), "maxLoops", 1, 100);
        if (maxLoops != null) {
            agent.setMaxLoops(maxLoops);
        }

        List<String> skills = stringList(firstNonNull(input.get("skills"), input.get("skillIds")), "skills");
        if (!skills.isEmpty()) {
            agent.setSkillIds(objectMapper.writeValueAsString(skills));
        }
        List<String> tools = stringList(firstNonNull(input.get("tools"), input.get("toolIds")), "tools");
        if (!tools.isEmpty()) {
            agent.setToolIds(objectMapper.writeValueAsString(tools));
        }

        Object behaviorRules = input.get("behaviorRules");
        if (behaviorRules instanceof Map<?, ?> map) {
            agent.setBehaviorRules(objectMapper.writeValueAsString(sanitizeMap(map)));
        }

        return agent;
    }

    private Map<String, Object> toDto(AgentEntity agent) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", agent.getId());
        out.put("name", agent.getName());
        out.put("description", agent.getDescription());
        out.put("role", agent.getRole());
        out.put("modelId", agent.getModelId());
        out.put("visibility", Boolean.TRUE.equals(agent.isPublic()) ? "public" : "private");
        out.put("status", agent.getStatus());
        out.put("ownerId", agent.getOwnerId());
        return out;
    }

    private static Boolean parseVisibility(Map<String, Object> input) {
        Object rawVisibility = input.get("visibility");
        if (rawVisibility != null) {
            String visibility = rawVisibility.toString().trim().toLowerCase(Locale.ROOT);
            return switch (visibility) {
                case "public" -> true;
                case "private" -> false;
                default -> throw new IllegalArgumentException("visibility must be public or private");
            };
        }
        Object rawPublic = input.get("isPublic");
        if (rawPublic instanceof Boolean b) {
            return b;
        }
        if (rawPublic != null) {
            return Boolean.parseBoolean(rawPublic.toString());
        }
        return false;
    }

    private static String requiredString(Object value, String field) {
        String s = optionalString(value);
        if (s == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return s;
    }

    private static String optionalString(Object value) {
        if (value == null) {
            return null;
        }
        String s = value.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static String enumValue(Object value, String field, List<String> allowed, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String s = value.toString().trim().toLowerCase(Locale.ROOT);
        if (s.isBlank()) {
            return defaultValue;
        }
        if (!allowed.contains(s)) {
            throw new IllegalArgumentException(field + " must be one of: " + String.join(", ", allowed));
        }
        return s;
    }

    private static Integer intValue(Object value, String field, int min, int max) {
        if (value == null) {
            return null;
        }
        int parsed;
        if (value instanceof Number n) {
            parsed = n.intValue();
        } else {
            try {
                parsed = Integer.parseInt(value.toString());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(field + " must be an integer");
            }
        }
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException(field + " must be between " + min + " and " + max);
        }
        return parsed;
    }

    private static List<String> stringList(Object value, String field) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> raw)) {
            throw new IllegalArgumentException(field + " must be an array of strings");
        }
        List<String> out = new ArrayList<>();
        for (Object item : raw) {
            String s = optionalString(item);
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    private static Map<String, Object> sanitizeMap(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() != null) {
                out.put(entry.getKey().toString(), entry.getValue());
            }
        }
        return out;
    }

    private static Object firstNonNull(Object a, Object b) {
        return a != null ? a : b;
    }
}
