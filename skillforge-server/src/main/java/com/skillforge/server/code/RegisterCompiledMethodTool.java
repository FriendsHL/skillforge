package com.skillforge.server.code;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.Tool;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.entity.CompiledMethodEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RegisterCompiledMethod — Code Agent submits Java source for a {@code BuiltInMethod}
 * implementation; the skill persists it in {@code pending_review}, attempts an immediate compile,
 * and returns the resulting status. Approval (moving to {@code active}) is a human step via the
 * REST API — this skill never auto-approves.
 */
public class RegisterCompiledMethodTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(RegisterCompiledMethodTool.class);

    private static final String REF_PREFIX = "agent.";

    private final CompiledMethodService compiledMethodService;
    private final ObjectMapper objectMapper;

    public RegisterCompiledMethodTool(CompiledMethodService compiledMethodService, ObjectMapper objectMapper) {
        this.compiledMethodService = compiledMethodService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "RegisterCompiledMethod";
    }

    @Override
    public String getDescription() {
        return "Submit a Java class that implements BuiltInMethod as a reusable hook method under "
                + "the 'agent.*' namespace. The source is compiled in-process and stored in "
                + "'pending_review' status — a human must approve via the admin UI before it "
                + "goes active. The ref must start with 'agent.'. Always review the code with "
                + "CodeReview before calling this tool.";
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("ref", Map.of(
                "type", "string",
                "description", "Unique reference key, must start with 'agent.' — e.g. 'agent.notify-feishu'"
        ));
        properties.put("displayName", Map.of(
                "type", "string",
                "description", "Human-readable name shown in the admin UI"
        ));
        properties.put("description", Map.of(
                "type", "string",
                "description", "Short description of what the method does"
        ));
        properties.put("sourceCode", Map.of(
                "type", "string",
                "description", "Complete Java source for a public class implementing "
                        + "com.skillforge.core.engine.hook.BuiltInMethod. Must have a no-arg constructor."
        ));
        properties.put("argsSchema", Map.of(
                "type", "object",
                "description", "Optional: JSON object describing arguments the method expects (for UI)"
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("ref", "displayName", "sourceCode"));

        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        String ref = asString(input.get("ref"));
        String displayName = asString(input.get("displayName"));
        String description = asString(input.get("description"));
        String sourceCode = asString(input.get("sourceCode"));

        if (ref == null || !ref.startsWith(REF_PREFIX)) {
            return SkillResult.error("ref is required and must start with '" + REF_PREFIX + "'");
        }
        if (displayName == null || displayName.isBlank()) {
            return SkillResult.error("displayName is required");
        }
        if (sourceCode == null || sourceCode.isBlank()) {
            return SkillResult.error("sourceCode is required");
        }

        String argsSchemaJson = serializeArgsSchema(input.get("argsSchema"));

        try {
            CompiledMethodEntity submitted = compiledMethodService.submit(
                    new CompiledMethodService.SubmitRequest(
                            ref, displayName, description, sourceCode, argsSchemaJson,
                            context.getSessionId(), null));
            CompiledMethodEntity compiled = compiledMethodService.compile(submitted.getId());

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", compiled.getId());
            payload.put("ref", compiled.getRef());
            payload.put("status", compiled.getStatus());
            if (compiled.getCompileError() != null) {
                payload.put("compileError", compiled.getCompileError());
            }
            payload.put("requiresApproval", true);

            log.info("Code Agent submitted compiled method ref={} id={} status={} session={}",
                    compiled.getRef(), compiled.getId(), compiled.getStatus(), context.getSessionId());
            return SkillResult.success(toJson(payload));
        } catch (CompiledMethodService.CompiledMethodException e) {
            return SkillResult.error("register_failed: " + e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Failed to register compiled method ref={}: {}", ref, e.toString());
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
