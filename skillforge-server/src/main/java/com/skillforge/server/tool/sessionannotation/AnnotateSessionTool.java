package com.skillforge.server.tool.sessionannotation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.sessionannotation.SessionAnnotationLlmService;
import com.skillforge.server.sessionannotation.SessionAnnotationLlmService.SessionAnnotationConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PROD-LABEL-CLUSTER V1 (Phase 1.3): thin agent-facing wrapper around
 * {@link SessionAnnotationLlmService#annotateSession}.
 *
 * <p>STEP 2.2 of the {@code session-annotator} agent pipeline (§4.1
 * "Judge + annotate"). The agent calls this once per session after STEP 2.1
 * (GetTrace fetch); the tool persists 2-3 {@code source='llm'} annotations
 * (outcome / suspect_surface / optional top_failing_tool) and returns the
 * inserted ids.
 *
 * <p>All business logic + validation lives in the service — this tool is the
 * JSON adapter (parse 6 fields, format output) and an LLM-facing description.
 * The service unit tests own the main behavior; this tool's tests verify
 * wiring + JSON shape.
 */
public class AnnotateSessionTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(AnnotateSessionTool.class);

    private final SessionAnnotationLlmService llmService;
    private final ObjectMapper objectMapper;

    public AnnotateSessionTool(SessionAnnotationLlmService llmService, ObjectMapper objectMapper) {
        this.llmService = llmService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "AnnotateSession";
    }

    @Override
    public String getDescription() {
        return "STEP 2.2 of the session-annotator pipeline. After STEP 2.1 fetched "
                + "the trace + span tree via GetTrace, call this tool with your LLM "
                + "judgment: outcome (success | partial_success | failure | cancelled), "
                + "suspect_surface (skill | prompt | behavior_rule | other | unclear), "
                + "confidence (0..1), reasoning (1-2 sentences), and optional "
                + "top_failing_tool (tool name or null). Writes 2-3 rows to "
                + "t_session_annotation as source='llm'. Idempotent on identical re-run "
                + "via UNIQUE constraint; different judgments append (most-recent wins).";
    }

    @Override
    public boolean isReadOnly() {
        // Writes t_session_annotation. Keep default false.
        return false;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("sessionId", Map.of(
                "type", "string",
                "description", "t_session.id (VARCHAR 36)"));
        properties.put("outcome", Map.of(
                "type", "string",
                "description", "One of: success | partial_success | failure | cancelled",
                "enum", List.copyOf(SessionAnnotationConstants.OUTCOME_VALUES)));
        properties.put("suspect_surface", Map.of(
                "type", "string",
                "description", "One of: skill | prompt | behavior_rule | other | unclear",
                "enum", List.copyOf(SessionAnnotationConstants.SUSPECT_SURFACE_VALUES)));
        properties.put("confidence", Map.of(
                "type", "number",
                "description", "Decimal between 0 and 1 inclusive. Below 0.5 is persisted "
                        + "but excluded from clustering."));
        properties.put("reasoning", Map.of(
                "type", "string",
                "description", "1-2 sentences justifying the judgment. Cite a specific "
                        + "span id when relevant."));
        properties.put("top_failing_tool", Map.of(
                "type", "string",
                "description", "Optional name of the tool that errored most in this "
                        + "session's traces. Pass null or omit when no tool failure dominates."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of(
                "sessionId", "outcome", "suspect_surface", "confidence", "reasoning"));
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            if (input == null) {
                return SkillResult.error("input is required");
            }
            String sessionId = stringValue(input.get("sessionId"));
            String outcome = stringValue(input.get("outcome"));
            String suspectSurface = stringValue(input.get("suspect_surface"));
            BigDecimal confidence = decimalValue(input.get("confidence"));
            String reasoning = stringValue(input.get("reasoning"));
            String topFailingTool = stringValue(input.get("top_failing_tool"));

            List<Long> ids = llmService.annotateSession(
                    sessionId, outcome, suspectSurface, confidence, reasoning, topFailingTool);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ok", true);
            payload.put("sessionId", sessionId);
            payload.put("annotation_ids", ids);
            payload.put("rows_written", ids.size());
            log.info("AnnotateSessionTool: sessionId={} outcome={} surface={} rowsWritten={}",
                    sessionId, outcome, suspectSurface, ids.size());
            return SkillResult.success(objectMapper.writeValueAsString(payload));
        } catch (IllegalArgumentException iae) {
            // Validation errors are user-visible — return the message verbatim so the
            // agent can correct itself on the next iteration.
            log.warn("AnnotateSessionTool validation error: {}", iae.getMessage());
            return SkillResult.error("AnnotateSession validation: " + iae.getMessage());
        } catch (Exception e) {
            log.error("AnnotateSessionTool execute failed", e);
            return SkillResult.error("AnnotateSession error: " + e.getMessage());
        }
    }

    private static String stringValue(Object value) {
        if (value == null) return null;
        String s = value.toString();
        return s.isBlank() ? null : s;
    }

    private static BigDecimal decimalValue(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number n) return new BigDecimal(n.toString());
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException nfe) {
            throw new IllegalArgumentException("confidence must be a number; got '" + value + "'");
        }
    }
}
