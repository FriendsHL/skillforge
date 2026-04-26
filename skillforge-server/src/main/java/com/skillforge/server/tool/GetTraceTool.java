package com.skillforge.server.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.TraceSpanEntity;
import com.skillforge.server.repository.TraceSpanRepository;
import com.skillforge.server.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only self-inspection tool for trace summaries and span trees.
 */
public class GetTraceTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(GetTraceTool.class);
    private static final int DEFAULT_MAX_SPANS = 30;
    private static final int HARD_MAX_SPANS = 100;
    private static final int IO_PREVIEW_CHARS = 500;

    private final TraceSpanRepository spanRepository;
    private final SessionService sessionService;
    private final ObjectMapper objectMapper;

    public GetTraceTool(TraceSpanRepository spanRepository,
                        SessionService sessionService,
                        ObjectMapper objectMapper) {
        this.spanRepository = spanRepository;
        this.sessionService = sessionService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "GetTrace";
    }

    @Override
    public String getDescription() {
        return "Inspect trace data for the current session. "
                + "Use action=list_traces to list AGENT_LOOP trace summaries for a session, "
                + "or action=get_trace to fetch a trace root plus a capped span tree.";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", Map.of(
                "type", "string",
                "description", "list_traces or get_trace",
                "enum", List.of("list_traces", "get_trace")
        ));
        properties.put("sessionId", Map.of(
                "type", "string",
                "description", "Optional session ID. Defaults to the current session. Must belong to the same user."
        ));
        properties.put("traceId", Map.of(
                "type", "string",
                "description", "Trace root span ID, required for action=get_trace"
        ));
        properties.put("maxSpans", Map.of(
                "type", "integer",
                "description", "Maximum descendant spans to return for get_trace. Default 30, hard cap 100."
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("action"));
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            if (input == null) {
                input = Map.of();
            }
            String action = stringValue(input.get("action"));
            if (action == null || action.isBlank()) {
                return SkillResult.error("action is required");
            }
            return switch (action) {
                case "list_traces" -> listTraces(input, context);
                case "get_trace" -> getTrace(input, context);
                default -> SkillResult.error("Unknown action: " + action + ". Supported: list_traces, get_trace");
            };
        } catch (IllegalArgumentException e) {
            return SkillResult.error(e.getMessage());
        } catch (Exception e) {
            log.error("GetTraceTool execute failed", e);
            return SkillResult.error("GetTrace error: " + e.getMessage());
        }
    }

    private SkillResult listTraces(Map<String, Object> input, SkillContext context) throws JsonProcessingException {
        String sessionId = resolveSessionId(input, context);
        assertSessionAccessible(sessionId, context);
        List<Map<String, Object>> traces = spanRepository
                .findBySessionIdAndSpanTypeOrderByStartTimeDesc(sessionId, "AGENT_LOOP")
                .stream()
                .map(this::toTraceSummary)
                .toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", "list_traces");
        out.put("sessionId", sessionId);
        out.put("count", traces.size());
        out.put("traces", traces);
        return SkillResult.success(objectMapper.writeValueAsString(out));
    }

    private SkillResult getTrace(Map<String, Object> input, SkillContext context) throws JsonProcessingException {
        String traceId = stringValue(input.get("traceId"));
        if (traceId == null || traceId.isBlank()) {
            return SkillResult.error("traceId is required for get_trace");
        }
        TraceSpanEntity root = spanRepository.findById(traceId)
                .orElseThrow(() -> new IllegalArgumentException("trace not found: " + traceId));
        String requestedSessionId = stringValue(input.get("sessionId"));
        if (requestedSessionId != null && !requestedSessionId.isBlank()
                && !requestedSessionId.equals(root.getSessionId())) {
            return SkillResult.error("traceId does not belong to sessionId=" + requestedSessionId);
        }
        assertSessionAccessible(root.getSessionId(), context);

        int maxSpans = intValue(input.get("maxSpans"), DEFAULT_MAX_SPANS, 1, HARD_MAX_SPANS);
        List<TraceSpanEntity> collected = collectDescendants(root.getId(), maxSpans + 1);
        boolean truncated = collected.size() > maxSpans;
        List<TraceSpanEntity> descendants = truncated ? collected.subList(0, maxSpans) : collected;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", "get_trace");
        out.put("traceId", root.getId());
        out.put("sessionId", root.getSessionId());
        out.put("root", toSpanMap(root));
        out.put("maxSpans", maxSpans);
        out.put("returnedSpans", descendants.size());
        out.put("truncated", truncated);
        out.put("spans", descendants.stream().map(this::toSpanMap).toList());
        return SkillResult.success(objectMapper.writeValueAsString(out));
    }

    private List<TraceSpanEntity> collectDescendants(String rootId, int maxSpans) {
        List<TraceSpanEntity> out = new ArrayList<>();
        List<String> frontier = new ArrayList<>();
        frontier.add(rootId);
        while (!frontier.isEmpty() && out.size() < maxSpans) {
            List<String> next = new ArrayList<>();
            for (String parentId : frontier) {
                if (out.size() >= maxSpans) {
                    break;
                }
                List<TraceSpanEntity> children = spanRepository.findByParentSpanIdOrderByStartTimeAsc(parentId);
                for (TraceSpanEntity child : children) {
                    if (out.size() >= maxSpans) {
                        break;
                    }
                    out.add(child);
                    next.add(child.getId());
                }
            }
            frontier = next;
        }
        return out;
    }

    private Map<String, Object> toTraceSummary(TraceSpanEntity root) {
        List<TraceSpanEntity> children = spanRepository.findByParentSpanIdOrderByStartTimeAsc(root.getId());
        int llmCallCount = 0;
        int toolCallCount = 0;
        for (TraceSpanEntity child : children) {
            if ("LLM_CALL".equals(child.getSpanType())) {
                llmCallCount++;
            } else if ("TOOL_CALL".equals(child.getSpanType())) {
                toolCallCount++;
            }
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("traceId", root.getId());
        m.put("sessionId", root.getSessionId());
        m.put("name", root.getName());
        m.put("startTime", instantValue(root.getStartTime()));
        m.put("endTime", instantValue(root.getEndTime()));
        m.put("durationMs", root.getDurationMs());
        m.put("inputTokens", root.getInputTokens());
        m.put("outputTokens", root.getOutputTokens());
        m.put("modelId", root.getModelId());
        m.put("success", root.isSuccess());
        m.put("error", truncate(root.getError(), IO_PREVIEW_CHARS));
        m.put("input", truncate(root.getInput(), 200));
        m.put("output", truncate(root.getOutput(), 200));
        m.put("llmCallCount", llmCallCount);
        m.put("toolCallCount", toolCallCount);
        return m;
    }

    private Map<String, Object> toSpanMap(TraceSpanEntity span) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", span.getId());
        m.put("sessionId", span.getSessionId());
        m.put("parentSpanId", span.getParentSpanId());
        m.put("spanType", span.getSpanType());
        m.put("name", span.getName());
        m.put("input", truncate(span.getInput(), IO_PREVIEW_CHARS));
        m.put("output", truncate(span.getOutput(), IO_PREVIEW_CHARS));
        m.put("startTime", instantValue(span.getStartTime()));
        m.put("endTime", instantValue(span.getEndTime()));
        m.put("durationMs", span.getDurationMs());
        m.put("iterationIndex", span.getIterationIndex());
        m.put("inputTokens", span.getInputTokens());
        m.put("outputTokens", span.getOutputTokens());
        m.put("modelId", span.getModelId());
        m.put("success", span.isSuccess());
        m.put("error", truncate(span.getError(), IO_PREVIEW_CHARS));
        m.put("toolUseId", span.getToolUseId());
        return m;
    }

    private String resolveSessionId(Map<String, Object> input, SkillContext context) {
        String explicit = stringValue(input.get("sessionId"));
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        String current = context.getSessionId();
        if (current == null || current.isBlank()) {
            throw new IllegalArgumentException("sessionId is required when no current session is available");
        }
        return current;
    }

    private void assertSessionAccessible(String sessionId, SkillContext context) {
        SessionEntity session = sessionService.getSession(sessionId);
        Long callerUserId = context.getUserId();
        if (callerUserId != null && session.getUserId() != null && !callerUserId.equals(session.getUserId())) {
            throw new IllegalArgumentException("session is not accessible: " + sessionId);
        }
    }

    private static String stringValue(Object value) {
        return value != null ? value.toString() : null;
    }

    private static String instantValue(java.time.Instant value) {
        return value != null ? value.toString() : null;
    }

    private static int intValue(Object value, int defaultValue, int min, int max) {
        int parsed = defaultValue;
        if (value instanceof Number n) {
            parsed = n.intValue();
        } else if (value != null) {
            try {
                parsed = Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
                parsed = defaultValue;
            }
        }
        return Math.max(min, Math.min(max, parsed));
    }

    private static String truncate(String value, int maxLen) {
        if (value == null || value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen) + "...";
    }
}
