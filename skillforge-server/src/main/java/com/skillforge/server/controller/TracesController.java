package com.skillforge.server.controller;

import com.skillforge.server.entity.TraceSpanEntity;
import com.skillforge.server.repository.TraceSpanRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Traces API：提供 Langfuse 风格的链路追踪查询。
 * <p>
 * 一个 "trace" = 一个 AGENT_LOOP root span（一次用户请求的完整执行）。
 */
@RestController
@RequestMapping("/api/traces")
public class TracesController {

    private final TraceSpanRepository spanRepository;

    public TracesController(TraceSpanRepository spanRepository) {
        this.spanRepository = spanRepository;
    }

    /**
     * 列出 traces（AGENT_LOOP root span），支持按 sessionId 过滤。
     * 返回每个 trace 的摘要信息（不含 input/output 全文）。
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listTraces(
            @RequestParam(required = false) String sessionId) {
        List<TraceSpanEntity> roots;
        if (sessionId != null && !sessionId.isBlank()) {
            roots = spanRepository.findBySessionIdAndSpanTypeOrderByStartTimeDesc(sessionId, "AGENT_LOOP");
        } else {
            roots = spanRepository.findBySpanTypeOrderByStartTimeDesc("AGENT_LOOP");
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (TraceSpanEntity root : roots) {
            // 查询子 span 统计
            List<TraceSpanEntity> children = spanRepository.findByParentSpanIdOrderByStartTimeAsc(root.getId());
            int llmCallCount = 0;
            int toolCallCount = 0;
            int totalChildDuration = 0;
            for (TraceSpanEntity child : children) {
                if ("LLM_CALL".equals(child.getSpanType())) llmCallCount++;
                else if ("TOOL_CALL".equals(child.getSpanType())) toolCallCount++;
                totalChildDuration += child.getDurationMs();
            }

            Map<String, Object> trace = new LinkedHashMap<>();
            trace.put("traceId", root.getId());
            trace.put("sessionId", root.getSessionId());
            trace.put("name", root.getName());
            trace.put("input", truncate(root.getInput(), 200));
            trace.put("output", truncate(root.getOutput(), 200));
            trace.put("startTime", root.getStartTime());
            trace.put("endTime", root.getEndTime());
            trace.put("durationMs", root.getDurationMs());
            trace.put("inputTokens", root.getInputTokens());
            trace.put("outputTokens", root.getOutputTokens());
            trace.put("modelId", root.getModelId());
            trace.put("success", root.isSuccess());
            trace.put("error", root.getError());
            trace.put("llmCallCount", llmCallCount);
            trace.put("toolCallCount", toolCallCount);
            result.add(trace);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 获取某个 trace 的完整 span 树（root + 所有子 span）。
     */
    @GetMapping("/{traceId}/spans")
    public ResponseEntity<Map<String, Object>> getTraceSpans(@PathVariable String traceId) {
        TraceSpanEntity root = spanRepository.findById(traceId).orElse(null);
        if (root == null) {
            return ResponseEntity.notFound().build();
        }

        // BFS to collect all descendants, not just direct children
        List<TraceSpanEntity> allDescendants = new ArrayList<>();
        List<String> frontier = new ArrayList<>();
        frontier.add(traceId);
        while (!frontier.isEmpty()) {
            List<String> nextFrontier = new ArrayList<>();
            for (String parentId : frontier) {
                List<TraceSpanEntity> children = spanRepository.findByParentSpanIdOrderByStartTimeAsc(parentId);
                allDescendants.addAll(children);
                for (TraceSpanEntity child : children) {
                    nextFrontier.add(child.getId());
                }
            }
            frontier = nextFrontier;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("root", toMap(root));
        List<Map<String, Object>> spans = new ArrayList<>();
        for (TraceSpanEntity child : allDescendants) {
            spans.add(toMap(child));
        }
        result.put("spans", spans);
        return ResponseEntity.ok(result);
    }

    /**
     * 获取某个 session 的所有 span（扁平列表，按 startTime 正序）。
     */
    @GetMapping("/session/{sessionId}")
    public ResponseEntity<List<Map<String, Object>>> getSessionSpans(@PathVariable String sessionId) {
        List<TraceSpanEntity> spans = spanRepository.findBySessionIdOrderByStartTimeAsc(sessionId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (TraceSpanEntity span : spans) {
            result.add(toMap(span));
        }
        return ResponseEntity.ok(result);
    }

    private Map<String, Object> toMap(TraceSpanEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("sessionId", e.getSessionId());
        m.put("parentSpanId", e.getParentSpanId());
        m.put("spanType", e.getSpanType());
        m.put("name", e.getName());
        m.put("input", e.getInput());
        m.put("output", e.getOutput());
        m.put("startTime", e.getStartTime());
        m.put("endTime", e.getEndTime());
        m.put("durationMs", e.getDurationMs());
        m.put("iterationIndex", e.getIterationIndex());
        m.put("inputTokens", e.getInputTokens());
        m.put("outputTokens", e.getOutputTokens());
        m.put("modelId", e.getModelId());
        m.put("success", e.isSuccess());
        m.put("error", e.getError());
        return m;
    }

    private String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }
}
