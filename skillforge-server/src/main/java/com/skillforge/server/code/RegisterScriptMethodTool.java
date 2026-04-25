package com.skillforge.server.code;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.Tool;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.entity.ScriptMethodEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RegisterScriptMethod — persist a bash/node script as a hook method under the
 * {@code agent.*} namespace. After registration, the method is immediately available to
 * other Agents as a {@code method}-type hook handler.
 */
public class RegisterScriptMethodTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(RegisterScriptMethodTool.class);

    private static final String REF_PREFIX = "agent.";

    private final ScriptMethodService scriptMethodService;
    private final ObjectMapper objectMapper;

    public RegisterScriptMethodTool(ScriptMethodService scriptMethodService, ObjectMapper objectMapper) {
        this.scriptMethodService = scriptMethodService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "RegisterScriptMethod";
    }

    @Override
    public String getDescription() {
        return "Register a bash or node script as a reusable hook method under the 'agent.*' namespace. "
                + "After registration, any Agent can reference it via a {type: \"method\", methodRef: \"agent.<name>\"} hook handler. "
                + "The ref must start with 'agent.'. Always test the script with CodeSandbox and review it with "
                + "CodeReview before calling this tool.";
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("ref", Map.of(
                "type", "string",
                "description", "Unique reference key, must start with 'agent.' — e.g. 'agent.slack-token-report'"
        ));
        properties.put("displayName", Map.of(
                "type", "string",
                "description", "Human-readable name shown in the UI"
        ));
        properties.put("description", Map.of(
                "type", "string",
                "description", "Short description of what the method does"
        ));
        properties.put("lang", Map.of(
                "type", "string",
                "enum", List.of("bash", "node"),
                "description", "Script interpreter"
        ));
        properties.put("scriptBody", Map.of(
                "type", "string",
                "description", "The script source to run on hook invocation"
        ));
        properties.put("argsSchema", Map.of(
                "type", "object",
                "description", "Optional: JSON object describing arguments the script expects (for UI)"
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("ref", "displayName", "lang", "scriptBody"));

        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        String ref = asString(input.get("ref"));
        String displayName = asString(input.get("displayName"));
        String description = asString(input.get("description"));
        String lang = asString(input.get("lang"));
        String scriptBody = asString(input.get("scriptBody"));

        if (ref == null || !ref.startsWith(REF_PREFIX)) {
            return SkillResult.error("ref is required and must start with '" + REF_PREFIX + "'");
        }
        if (displayName == null || displayName.isBlank()) {
            return SkillResult.error("displayName is required");
        }
        if (lang == null || lang.isBlank()) {
            return SkillResult.error("lang is required (bash | node)");
        }
        if (scriptBody == null || scriptBody.isBlank()) {
            return SkillResult.error("scriptBody is required");
        }

        String argsSchemaJson = serializeArgsSchema(input.get("argsSchema"));

        try {
            ScriptMethodEntity saved = scriptMethodService.create(new ScriptMethodService.CreateRequest(
                    ref, displayName, description, lang, scriptBody, argsSchemaJson, context.getUserId()
            ));
            log.info("Code Agent registered script method ref={} id={} session={}",
                    saved.getRef(), saved.getId(), context.getSessionId());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", saved.getId());
            payload.put("ref", saved.getRef());
            payload.put("enabled", saved.isEnabled());
            return SkillResult.success(toJson(payload));
        } catch (ScriptMethodService.ScriptMethodException e) {
            return SkillResult.error("register_failed: " + e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Failed to register script method ref={}: {}", ref, e.toString());
            return SkillResult.error("register_failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private String serializeArgsSchema(Object value) {
        if (value == null) return null;
        if (value instanceof String s) return s;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return value.toString();
        }
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }
}
