package com.skillforge.server.tool.optreport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.observability.entity.LlmSpanEntity;
import com.skillforge.observability.repository.LlmSpanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AUTOEVOLVE-CLOSE-LOOP P2-a G5 — 段 2 (根因诊断) of the
 * {@code holistic-error-span-analyzer} workflow sub-agent.
 *
 * <p>Given a {@code sessionId}, returns the session's <em>ordered</em> tool-call
 * sequence ({@code kind='tool'}, by {@code started_at ASC}) as a compact list:
 * {@code [{iterationIndex, name, inputPreview, error, errorType}]}. The analyzer
 * reads 2-3 representative sessions per symptom group to infer the common
 * <em>precondition</em> root cause — i.e. what step was missing before the
 * failing call.
 *
 * <p><b>Why not reuse {@code GetTrace}.</b> {@code GetTrace.get_trace} is keyed by
 * {@code traceId} (requiring a {@code list_traces} round-trip first), returns a
 * per-trace (not per-session) tree of heavy 18-field span maps including llm
 * spans + token/cost fields, and caps at 100 spans. For "read the ordered tool
 * sequence of a session to spot a missing precondition" that is too heavy and
 * the wrong key. This tool is the compact, per-session, tool-only projection the
 * 段 2 step actually needs (KISS).
 *
 * <p>{@code inputPreview} (truncated) is included beyond the minimal
 * {@code [iterationIndex, toolName, error]} because the precondition inference
 * hinges on <em>which</em> target a call acted on (e.g. an {@code Edit} on file
 * X with no prior {@code Read} of file X) — the tool name sequence alone cannot
 * disambiguate that. It exposes no data the session-annotator's existing
 * {@code GetTrace} read does not already surface.
 *
 * <p>Read-only. Reachable only by the privileged workflow holistic analyzer (via
 * {@code WorkflowSkillRegistryFactory} + the agent's {@code tool_ids}), so it
 * carries no per-user ownership guard — same scoping model as
 * {@link LoadErrorSpanBatchTool} / {@link LoadSessionBatchTool}.
 */
public class GetToolCallSequenceTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(GetToolCallSequenceTool.class);

    /** Hard cap on tool calls returned (sequence is ordered; truncation logged). */
    static final int MAX_TOOL_CALLS = 200;
    /** Truncation cap for each call's input preview. */
    static final int INPUT_PREVIEW_MAX = 200;

    private static final String KIND_TOOL = "tool";

    private final LlmSpanRepository spanRepository;
    private final ObjectMapper objectMapper;

    public GetToolCallSequenceTool(LlmSpanRepository spanRepository, ObjectMapper objectMapper) {
        this.spanRepository = spanRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "GetToolCallSequence";
    }

    @Override
    public String getDescription() {
        return "AUTOEVOLVE G5 段2: given a sessionId, return the session's ordered "
                + "tool-call sequence (kind='tool', by started_at ASC) as "
                + "{sessionId, toolCallCount, truncated, toolCalls:[{iterationIndex, name, "
                + "inputPreview, error, errorType}]}. Use it to read a representative "
                + "session's full tool sequence and infer what precondition step was "
                + "missing before a failing call. error is null for successful calls. "
                + "toolCalls is [] (never null) for a session with no tool spans.";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("sessionId", Map.of(
                "type", "string",
                "description", "t_session.id (VARCHAR 36) — the session whose tool sequence to read."));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("sessionId"));
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            String sessionId = null;
            if (input != null && input.get("sessionId") != null) {
                sessionId = input.get("sessionId").toString().trim();
            }
            if (sessionId == null || sessionId.isEmpty()) {
                return SkillResult.validationError("sessionId is required");
            }

            List<LlmSpanEntity> spans = spanRepository.findBySessionIdOrderByStartedAtAsc(sessionId);
            List<Map<String, Object>> toolCalls = new ArrayList<>();
            int total = 0;
            boolean truncated = false;
            for (LlmSpanEntity s : spans) {
                if (!KIND_TOOL.equals(s.getKind())) continue;
                total++;
                if (toolCalls.size() >= MAX_TOOL_CALLS) {
                    truncated = true;
                    continue;
                }
                Map<String, Object> call = new LinkedHashMap<>();
                call.put("iterationIndex", s.getIterationIndex());
                call.put("name", s.getName());
                call.put("inputPreview", truncate(s.getInputSummary(), INPUT_PREVIEW_MAX));
                call.put("error", s.getError());
                call.put("errorType", s.getErrorType());
                toolCalls.add(call);
            }

            if (truncated) {
                log.warn("GetToolCallSequenceTool: sessionId={} tool calls {} exceed cap {} — truncating",
                        sessionId, total, MAX_TOOL_CALLS);
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sessionId", sessionId);
            payload.put("toolCallCount", total);
            payload.put("truncated", truncated);
            payload.put("toolCalls", toolCalls);

            log.info("GetToolCallSequenceTool: sessionId={} toolCallCount={} returned={} truncated={}",
                    sessionId, total, toolCalls.size(), truncated);
            return SkillResult.success(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.error("GetToolCallSequenceTool execute failed", e);
            return SkillResult.error("GetToolCallSequence error: " + e.getMessage());
        }
    }

    private static String truncate(String value, int maxLen) {
        if (value == null || value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen) + "...";
    }
}
