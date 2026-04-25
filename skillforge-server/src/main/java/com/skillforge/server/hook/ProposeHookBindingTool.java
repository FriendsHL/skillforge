package com.skillforge.server.hook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.entity.AgentAuthoredHookEntity;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.service.AgentAuthoredHookService;
import com.skillforge.server.service.AgentTargetResolver;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ProposeHookBindingTool implements Tool {

    private final AgentTargetResolver targetResolver;
    private final AgentAuthoredHookService agentAuthoredHookService;
    private final ObjectMapper objectMapper;

    public ProposeHookBindingTool(AgentTargetResolver targetResolver,
                                  AgentAuthoredHookService agentAuthoredHookService,
                                  ObjectMapper objectMapper) {
        this.targetResolver = targetResolver;
        this.agentAuthoredHookService = agentAuthoredHookService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "ProposeHookBinding";
    }

    @Override
    public String getDescription() {
        return "Submit a PENDING lifecycle hook binding proposal for the current or a visible target agent. "
                + "This tool never approves hooks. methodTarget must be immutable, e.g. compiled:<id>.";
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("targetAgentId", Map.of("type", "integer", "description", "Optional target agent id"));
        properties.put("targetAgentName", Map.of("type", "string", "description", "Optional exact target agent name"));
        properties.put("event", Map.of(
                "type", "string",
                "enum", List.of("SessionStart", "UserPromptSubmit", "PostToolUse", "Stop", "SessionEnd")
        ));
        properties.put("methodTarget", Map.of(
                "type", "string",
                "description", "Immutable method target. V1 supports compiled:<id>; builtin:<ref> may be used only for allowlisted builtin methods."
        ));
        properties.put("displayName", Map.of("type", "string"));
        properties.put("description", Map.of("type", "string", "description", "Rationale for human review"));
        properties.put("timeoutSeconds", Map.of("type", "integer", "minimum", 1, "maximum", 300));
        properties.put("failurePolicy", Map.of(
                "type", "string",
                "enum", List.of("CONTINUE", "ABORT", "SKIP_CHAIN")
        ));
        properties.put("async", Map.of("type", "boolean"));
        properties.put("args", Map.of("type", "object", "description", "Static method args to merge at execution time"));
        properties.put("parentHookId", Map.of("type", "integer"));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("event", "methodTarget", "description"));
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        if (context == null || context.getSessionId() == null) {
            return SkillResult.error("sessionId is required to resolve author agent");
        }
        Map<String, Object> args = input != null ? input : Map.of();
        try {
            Long authorAgentId = targetResolver.authorAgentIdForSession(context.getSessionId());
            AgentEntity target = targetResolver.resolveVisibleTarget(
                    context.getSessionId(),
                    args.get("targetAgentId"),
                    args.get("targetAgentName"));
            AgentAuthoredHookEntity saved = agentAuthoredHookService.propose(
                    new AgentAuthoredHookService.ProposeRequest(
                            target.getId(),
                            authorAgentId,
                            context.getSessionId(),
                            asString(args.get("event")),
                            asString(args.get("methodTarget")),
                            asString(args.get("displayName")),
                            asString(args.get("description")),
                            asInteger(args.get("timeoutSeconds")),
                            asString(args.get("failurePolicy")),
                            Boolean.TRUE.equals(args.get("async")),
                            args.get("args"),
                            asLong(args.get("parentHookId"))));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("hookId", saved.getId());
            payload.put("targetAgentId", saved.getTargetAgentId());
            payload.put("reviewState", saved.getReviewState());
            payload.put("methodRef", saved.getMethodRef());
            payload.put("requiresApproval", true);
            return SkillResult.success(toJson(payload));
        } catch (RuntimeException e) {
            return SkillResult.error("propose_hook_binding_failed: " + e.getMessage());
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return value.toString();
        }
    }

    private static String asString(Object value) {
        return value != null ? value.toString() : null;
    }

    private static Integer asInteger(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long asLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
