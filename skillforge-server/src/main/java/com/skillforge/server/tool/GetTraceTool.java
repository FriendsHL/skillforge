package com.skillforge.server.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.observability.api.LlmTraceStore;
import com.skillforge.observability.api.LlmTraceStore.TraceWithSpans;
import com.skillforge.observability.domain.LlmSpan;
import com.skillforge.observability.domain.LlmTrace;
import com.skillforge.observability.entity.LlmTraceEntity;
import com.skillforge.observability.repository.LlmTraceRepository;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only self-inspection tool for trace summaries and span trees.
 *
 * <p>OBS-2 M4 — read path migrated from {@code t_trace_span} (legacy
 * {@code TraceSpanRepository}) to the unified {@code t_llm_trace} +
 * {@code t_llm_span} tables via {@link LlmTraceStore}.
 *
 * <p>Output shape stays close to the pre-M4 form so existing LLM prompt
 * habits keep working:
 * <ul>
 *   <li>{@code list_traces} returns {@code traceId}, {@code sessionId},
 *       {@code name}, {@code startTime}, {@code endTime}, {@code durationMs},
 *       {@code inputTokens}, {@code outputTokens}, {@code success},
 *       {@code error}, {@code input}, {@code output} (now nulled per M3 —
 *       lives on individual spans), and the count fields
 *       {@code llmCallCount} / {@code toolCallCount} / {@code eventCount}.</li>
 *   <li>{@code get_trace} returns {@code root} (synthesised from
 *       {@code t_llm_trace}, {@code spanType="AGENT_LOOP"} for compat) plus
 *       a flat {@code spans} list (kind=llm/tool/event) ordered by
 *       {@code startedAt ASC}, capped at {@code maxSpans}.</li>
 * </ul>
 */
public class GetTraceTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(GetTraceTool.class);
    private static final int DEFAULT_MAX_SPANS = 30;
    private static final int HARD_MAX_SPANS = 100;
    private static final int IO_PREVIEW_CHARS = 500;

    private final LlmTraceStore traceStore;
    private final LlmTraceRepository traceRepository;
    private final SessionService sessionService;
    private final ObjectMapper objectMapper;

    public GetTraceTool(LlmTraceStore traceStore,
                        LlmTraceRepository traceRepository,
                        SessionService sessionService,
                        ObjectMapper objectMapper) {
        this.traceStore = traceStore;
        this.traceRepository = traceRepository;
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

        // OBS-2 M4: trace list comes from t_llm_trace (aggregate columns), no longer
        // from t_trace_span where span_type='AGENT_LOOP'. Per-trace LLM-call count is
        // computed via listSpansByTrace(kind={llm}, limit=HARD_MAX_SPANS) — bounded by
        // HARD_MAX_SPANS so no unbounded scan even on long sessions; trace's own
        // toolCallCount / eventCount are reused from the aggregate.
        List<LlmTraceEntity> traces = traceRepository.findBySessionIdOrderByStartedAtDesc(sessionId);
        List<Map<String, Object>> summaries = new java.util.ArrayList<>(traces.size());
        for (LlmTraceEntity te : traces) {
            summaries.add(toTraceSummary(te));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", "list_traces");
        out.put("sessionId", sessionId);
        out.put("count", summaries.size());
        out.put("traces", summaries);
        return SkillResult.success(objectMapper.writeValueAsString(out));
    }

    private SkillResult getTrace(Map<String, Object> input, SkillContext context) throws JsonProcessingException {
        String traceId = stringValue(input.get("traceId"));
        if (traceId == null || traceId.isBlank()) {
            return SkillResult.error("traceId is required for get_trace");
        }
        TraceWithSpans tws = traceStore.readByTraceId(traceId)
                .orElseThrow(() -> new IllegalArgumentException("trace not found: " + traceId));
        LlmTrace trace = tws.trace();
        String requestedSessionId = stringValue(input.get("sessionId"));
        if (requestedSessionId != null && !requestedSessionId.isBlank()
                && !requestedSessionId.equals(trace.sessionId())) {
            return SkillResult.error("traceId does not belong to sessionId=" + requestedSessionId);
        }
        assertSessionAccessible(trace.sessionId(), context);

        int maxSpans = intValue(input.get("maxSpans"), DEFAULT_MAX_SPANS, 1, HARD_MAX_SPANS);
        // OBS-2 M4: span tree via flat t_llm_span trace_id fetch (kind discriminates).
        // limit pushed to SQL via listSpansByTrace's Pageable to avoid loading all spans
        // for long traces. Fetch maxSpans+1 to detect truncation.
        List<LlmSpan> all = traceStore.listSpansByTrace(traceId, null, maxSpans + 1);
        boolean truncated = all.size() > maxSpans;
        List<LlmSpan> spans = truncated ? all.subList(0, maxSpans) : all;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", "get_trace");
        out.put("traceId", trace.traceId());
        out.put("sessionId", trace.sessionId());
        out.put("root", toRootMap(trace));
        out.put("maxSpans", maxSpans);
        out.put("returnedSpans", spans.size());
        out.put("truncated", truncated);
        out.put("spans", spans.stream().map(GetTraceTool::toSpanMap).toList());
        return SkillResult.success(objectMapper.writeValueAsString(out));
    }

    private Map<String, Object> toTraceSummary(LlmTraceEntity te) {
        // Per-trace LLM call count — single bounded query against t_llm_span where
        // trace_id=? AND kind='llm'. Bounded by HARD_MAX_SPANS so very long traces
        // report at most 100 (matches the existing get_trace cap).
        int llmCallCount = traceStore
                .listSpansByTrace(te.getTraceId(), java.util.Set.of("llm"), HARD_MAX_SPANS)
                .size();

        String status = te.getStatus();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("traceId", te.getTraceId());
        m.put("sessionId", te.getSessionId());
        m.put("name", te.getAgentName() != null ? te.getAgentName() : te.getRootName());
        m.put("startTime", instantValue(te.getStartedAt()));
        m.put("endTime", instantValue(te.getEndedAt()));
        m.put("durationMs", te.getTotalDurationMs());
        m.put("inputTokens", te.getTotalInputTokens());
        m.put("outputTokens", te.getTotalOutputTokens());
        // OBS-2 M4: trace-level model id no longer exists on t_llm_trace (lives on
        // individual LLM spans). Kept null for shape compat with pre-M4 output.
        m.put("modelId", null);
        m.put("status", status);
        m.put("success", "ok".equals(status));
        m.put("error", truncate(te.getError(), IO_PREVIEW_CHARS));
        // Pre-M4 surfaced root TraceSpan input/output here. With t_llm_trace those
        // payloads live on individual spans; null preserves the field for
        // backward-compat consumers.
        m.put("input", null);
        m.put("output", null);
        m.put("llmCallCount", llmCallCount);
        m.put("toolCallCount", te.getToolCallCount());
        m.put("eventCount", te.getEventCount());
        return m;
    }

    private Map<String, Object> toRootMap(LlmTrace trace) {
        String status = trace.status();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", trace.traceId());
        m.put("sessionId", trace.sessionId());
        m.put("parentSpanId", null);
        // Synthetic span_type for backward compat with the pre-M4 output shape.
        m.put("spanType", "AGENT_LOOP");
        m.put("name", trace.agentName() != null ? trace.agentName() : trace.rootName());
        m.put("input", null);
        m.put("output", null);
        m.put("startTime", instantValue(trace.startedAt()));
        m.put("endTime", instantValue(trace.endedAt()));
        m.put("durationMs", trace.totalDurationMs());
        m.put("iterationIndex", 0);
        m.put("inputTokens", trace.totalInputTokens());
        m.put("outputTokens", trace.totalOutputTokens());
        m.put("modelId", null);
        m.put("status", status);
        m.put("success", "ok".equals(status));
        m.put("error", truncate(trace.error(), IO_PREVIEW_CHARS));
        m.put("toolUseId", null);
        return m;
    }

    private static Map<String, Object> toSpanMap(LlmSpan s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.spanId());
        m.put("sessionId", s.sessionId());
        m.put("parentSpanId", s.parentSpanId());
        m.put("spanType", legacySpanType(s));
        m.put("kind", s.kind());
        m.put("eventType", s.eventType());
        m.put("name", s.name() != null ? s.name() : s.model());
        m.put("input", truncate(s.inputSummary(), IO_PREVIEW_CHARS));
        m.put("output", truncate(s.outputSummary(), IO_PREVIEW_CHARS));
        m.put("startTime", instantValue(s.startedAt()));
        m.put("endTime", instantValue(s.endedAt()));
        m.put("durationMs", s.latencyMs());
        m.put("iterationIndex", s.iterationIndex());
        m.put("inputTokens", s.inputTokens());
        m.put("outputTokens", s.outputTokens());
        m.put("modelId", s.model());
        m.put("success", s.error() == null);
        m.put("error", truncate(s.error(), IO_PREVIEW_CHARS));
        m.put("toolUseId", s.toolUseId());
        return m;
    }

    /**
     * Map OBS-2 M4 {@code kind} (+{@code eventType}) back to the legacy
     * {@code span_type} string so LLM prompt habits keep working.
     */
    private static String legacySpanType(LlmSpan s) {
        String kind = s.kind();
        if ("llm".equals(kind)) return "LLM_CALL";
        if ("tool".equals(kind)) return "TOOL_CALL";
        if ("event".equals(kind)) {
            String et = s.eventType();
            return et == null ? "EVENT" : et.toUpperCase(java.util.Locale.ROOT);
        }
        return kind == null ? "UNKNOWN" : kind.toUpperCase(java.util.Locale.ROOT);
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

    private static String instantValue(Instant value) {
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
