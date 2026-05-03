package com.skillforge.server.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.observability.entity.LlmSpanEntity;
import com.skillforge.observability.entity.LlmTraceEntity;
import com.skillforge.observability.repository.LlmSpanRepository;
import com.skillforge.observability.repository.LlmTraceRepository;
import com.skillforge.server.repository.SessionMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Traces API：提供 Langfuse 风格的链路追踪查询。
 *
 * <p>OBS-2 M3 — read path cut-over from {@code t_trace_span} to the unified
 * {@code t_llm_trace} + {@code t_llm_span} tables:
 * <ul>
 *   <li>{@code GET /api/traces?sessionId=X} — driven by {@code t_llm_trace} aggregates
 *   ({@code total_duration_ms} / {@code tool_call_count} / {@code event_count} / {@code status}),
 *   eliminating the previous N+1 child-span scan</li>
 *   <li>{@code GET /api/traces/{traceId}/spans} — flat list from {@code t_llm_span where trace_id=?}
 *   (kind column distinguishes llm/tool/event; the legacy BFS over parent_span_id is gone)</li>
 *   <li>{@code GET /api/traces/session/{sessionId}} — kept for backward-compat (returns flat list
 *   from {@code t_llm_span}, all kinds)</li>
 * </ul>
 *
 * <p>Response shape is intentionally close to the pre-M3 form so existing UI clients keep
 * rendering; the {@code llmCallCount} field counts spans of {@code kind='llm'} on the trace.
 */
@RestController
@RequestMapping("/api/traces")
public class TracesController {

    private static final Logger log = LoggerFactory.getLogger(TracesController.class);
    private static final int INPUT_PREVIEW_MAX = 200;

    private final LlmTraceRepository traceRepository;
    private final LlmSpanRepository spanRepository;
    private final SessionMessageRepository sessionMessageRepository;
    private final ObjectMapper objectMapper;

    public TracesController(LlmTraceRepository traceRepository,
                            LlmSpanRepository spanRepository,
                            SessionMessageRepository sessionMessageRepository,
                            ObjectMapper objectMapper) {
        this.traceRepository = traceRepository;
        this.spanRepository = spanRepository;
        this.sessionMessageRepository = sessionMessageRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 列出 traces，支持按 sessionId 过滤。
     * 返回每个 trace 的摘要信息（不含 input/output 全文）。
     *
     * <p>Pre-M3 implementation read {@code t_trace_span where span_type='AGENT_LOOP'} and
     * fanned out one query per trace to count children — N+1 on long sessions. M3 reads
     * the aggregate columns ({@code tool_call_count} / {@code event_count} / {@code total_duration_ms})
     * directly from {@code t_llm_trace}, written by {@code AgentLoopEngine.finalizeTrace}.
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listTraces(
            @RequestParam(required = false) String sessionId) {
        List<LlmTraceEntity> traces;
        if (sessionId != null && !sessionId.isBlank()) {
            traces = traceRepository.findBySessionIdOrderByStartedAtDesc(sessionId);
        } else {
            // Pre-M3 behaviour returned all traces ordered by recency. We don't expose a
            // global findAllOrderByStartedAtDesc — admins / cron tools call the per-session form.
            traces = new ArrayList<>(traceRepository.findAll());
            traces.sort((a, b) -> {
                if (a.getStartedAt() == null && b.getStartedAt() == null) return 0;
                if (a.getStartedAt() == null) return 1;
                if (b.getStartedAt() == null) return -1;
                return b.getStartedAt().compareTo(a.getStartedAt());
            });
        }

        // r2 B-1 fix: batch-count llm spans across all traces in a single GROUP BY query
        // instead of per-trace findByTraceIdOrderByStartedAtAsc. Tool / event counts are
        // already stamped on t_llm_trace by AgentLoopEngine.finalizeTrace; only LLM count
        // needs an aggregate from t_llm_span (no llm_call_count column on t_llm_trace today).
        List<String> traceIds = new ArrayList<>(traces.size());
        for (LlmTraceEntity t : traces) traceIds.add(t.getTraceId());
        Map<String, Long> llmCountByTrace = traceIds.isEmpty()
                ? Collections.emptyMap()
                : new HashMap<>();
        if (!traceIds.isEmpty()) {
            for (Object[] row : spanRepository.countByTraceIdsAndKind(traceIds, "llm")) {
                llmCountByTrace.put((String) row[0], (Long) row[1]);
            }
        }

        // OBS-2 M3 follow-up: derive trace.input from first user message of each trace.
        // M3 cut /api/traces over to t_llm_trace, which has no input column. Pre-M3 callers
        // (TraceSidebar, Traces.tsx) used trace.input as the list-item title; we rebuild it
        // here so the dashboard title fallback no longer collapses to agent_name.
        Map<String, String> firstUserInputByTrace = traceIds.isEmpty()
                ? Collections.emptyMap()
                : new HashMap<>();
        if (!traceIds.isEmpty()) {
            for (Object[] row : sessionMessageRepository.findFirstUserMessageContentByTraceIds(traceIds)) {
                String tid = (String) row[0];
                String contentJson = (String) row[1];
                String text = flattenUserContent(contentJson);
                if (text != null && !text.isEmpty()) {
                    firstUserInputByTrace.put(tid, truncate(text, INPUT_PREVIEW_MAX));
                }
            }
        }

        List<Map<String, Object>> result = new ArrayList<>(traces.size());
        for (LlmTraceEntity t : traces) {
            int llmCallCount = llmCountByTrace.getOrDefault(t.getTraceId(), 0L).intValue();

            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("traceId", t.getTraceId());
            dto.put("sessionId", t.getSessionId());
            dto.put("name", t.getAgentName() != null ? t.getAgentName() : t.getRootName());
            dto.put("input", firstUserInputByTrace.get(t.getTraceId()));   // OBS-2 M3 follow-up: derived from first user message
            dto.put("output", null);   // output blobs live on individual spans
            dto.put("startTime", t.getStartedAt());
            dto.put("endTime", t.getEndedAt());
            dto.put("durationMs", t.getTotalDurationMs());
            dto.put("inputTokens", t.getTotalInputTokens());
            dto.put("outputTokens", t.getTotalOutputTokens());
            dto.put("modelId", null);  // model lives on individual LLM spans
            // status enum: running | ok | error | cancelled. Map to legacy boolean for
            // backward compat with existing dashboard.
            String status = t.getStatus();
            dto.put("status", status);
            dto.put("success", "ok".equals(status));
            dto.put("error", t.getError());
            dto.put("llmCallCount", llmCallCount);
            dto.put("toolCallCount", t.getToolCallCount());
            dto.put("eventCount", t.getEventCount());
            dto.put("agentName", t.getAgentName());
            result.add(dto);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 获取某个 trace 的完整 span 列表（按 startedAt ASC）。
     *
     * <p>OBS-2 M3 — flat list from {@code t_llm_span where trace_id=?}. The pre-M3 BFS over
     * {@code parent_span_id} is unnecessary because {@code kind} now distinguishes llm/tool/event,
     * and {@code t_llm_trace} carries the root metadata directly.
     */
    @GetMapping("/{traceId}/spans")
    public ResponseEntity<Map<String, Object>> getTraceSpans(@PathVariable String traceId) {
        LlmTraceEntity trace = traceRepository.findById(traceId).orElse(null);
        if (trace == null) {
            return ResponseEntity.notFound().build();
        }

        List<LlmSpanEntity> spans = spanRepository.findByTraceIdOrderByStartedAtAsc(traceId);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("id", trace.getTraceId());
        root.put("sessionId", trace.getSessionId());
        root.put("parentSpanId", null);
        root.put("spanType", "AGENT_LOOP");
        root.put("name", trace.getAgentName() != null ? trace.getAgentName() : trace.getRootName());
        root.put("input", null);
        root.put("output", null);
        root.put("startTime", trace.getStartedAt());
        root.put("endTime", trace.getEndedAt());
        root.put("durationMs", trace.getTotalDurationMs());
        root.put("iterationIndex", 0);
        root.put("inputTokens", trace.getTotalInputTokens());
        root.put("outputTokens", trace.getTotalOutputTokens());
        root.put("modelId", null);
        root.put("status", trace.getStatus());
        root.put("success", "ok".equals(trace.getStatus()));
        root.put("error", trace.getError());

        List<Map<String, Object>> spanDtos = new ArrayList<>(spans.size());
        for (LlmSpanEntity s : spans) {
            spanDtos.add(toMap(s));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("root", root);
        result.put("spans", spanDtos);
        return ResponseEntity.ok(result);
    }

    /**
     * 获取某个 session 的所有 span（扁平列表，按 startTime 正序）。
     *
     * <p>Backward-compat endpoint kept for any existing client that built links to
     * {@code /api/traces/session/{id}}; the cut-over reads {@code t_llm_span} directly.
     */
    @GetMapping("/session/{sessionId}")
    public ResponseEntity<List<Map<String, Object>>> getSessionSpans(@PathVariable String sessionId) {
        List<LlmSpanEntity> spans = spanRepository.findBySessionIdOrderByStartedAtAsc(sessionId);
        List<Map<String, Object>> result = new ArrayList<>(spans.size());
        for (LlmSpanEntity s : spans) {
            result.add(toMap(s));
        }
        return ResponseEntity.ok(result);
    }

    private Map<String, Object> toMap(LlmSpanEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getSpanId());
        m.put("sessionId", e.getSessionId());
        m.put("parentSpanId", e.getParentSpanId());
        // Map back to legacy spanType strings for any client still keying off it.
        m.put("spanType", legacySpanType(e));
        m.put("kind", e.getKind());
        m.put("eventType", e.getEventType());
        m.put("name", e.getName() != null ? e.getName() : e.getModel());
        m.put("input", e.getInputSummary());
        m.put("output", e.getOutputSummary());
        m.put("startTime", e.getStartedAt());
        m.put("endTime", e.getEndedAt());
        m.put("durationMs", e.getLatencyMs());
        m.put("iterationIndex", e.getIterationIndex());
        m.put("inputTokens", e.getInputTokens());
        m.put("outputTokens", e.getOutputTokens());
        m.put("modelId", e.getModel());
        m.put("success", e.getError() == null);
        m.put("error", e.getError());
        return m;
    }

    private static String legacySpanType(LlmSpanEntity e) {
        String kind = e.getKind();
        if ("llm".equals(kind)) return "LLM_CALL";
        if ("tool".equals(kind)) return "TOOL_CALL";
        if ("event".equals(kind)) {
            String et = e.getEventType();
            return et == null ? "EVENT" : et.toUpperCase(java.util.Locale.ROOT);
        }
        return kind == null ? "UNKNOWN" : kind.toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * Extract user-visible text from a t_session_message.content_json payload.
     * Content can be:
     * <ul>
     *   <li>JSON-encoded string ({@code "looking for skills"}) — common for plain user messages</li>
     *   <li>JSON array of content blocks ({@code [{type:"text",text:"..."},{type:"tool_use",...}]}) —
     *       Claude/OpenAi assistant + tool messages</li>
     *   <li>Plain string fallback when not valid JSON</li>
     * </ul>
     * Returns the first text block's text (or the whole string for plain string).
     * Returns null on any parse error so the caller falls back gracefully.
     */
    private String flattenUserContent(String contentJson) {
        if (contentJson == null || contentJson.isEmpty()) return null;
        try {
            JsonNode node = objectMapper.readTree(contentJson);
            if (node.isTextual()) {
                return node.asText();
            }
            if (node.isArray()) {
                for (JsonNode block : node) {
                    if (block.has("type") && "text".equals(block.path("type").asText())
                            && block.has("text")) {
                        return block.path("text").asText();
                    }
                }
            }
            return null;
        } catch (Exception e) {
            log.debug("flattenUserContent failed for trace input derivation: {}", e.toString());
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
