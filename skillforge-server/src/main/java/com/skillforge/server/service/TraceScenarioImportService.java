package com.skillforge.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.observability.entity.LlmTraceEntity;
import com.skillforge.observability.entity.LlmSpanEntity;
import com.skillforge.observability.repository.LlmSpanRepository;
import com.skillforge.observability.repository.LlmTraceRepository;
import com.skillforge.server.entity.EvalScenarioEntity;
import com.skillforge.server.entity.SessionMessageEntity;
import com.skillforge.server.repository.EvalScenarioDraftRepository;
import com.skillforge.server.repository.SessionMessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TraceScenarioImportService {

    private static final int DEFAULT_NAME_MAX = 80;
    private static final int DEFAULT_MIN_TOKENS = 2000;
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_SCAN_TRACES = 200;

    private final LlmTraceRepository traceRepository;
    private final LlmSpanRepository spanRepository;
    private final SessionMessageRepository sessionMessageRepository;
    private final EvalScenarioDraftRepository evalScenarioDraftRepository;
    private final ObjectMapper objectMapper;

    public TraceScenarioImportService(LlmTraceRepository traceRepository,
                                      LlmSpanRepository spanRepository,
                                      SessionMessageRepository sessionMessageRepository,
                                      EvalScenarioDraftRepository evalScenarioDraftRepository,
                                      ObjectMapper objectMapper) {
        this.traceRepository = traceRepository;
        this.spanRepository = spanRepository;
        this.sessionMessageRepository = sessionMessageRepository;
        this.evalScenarioDraftRepository = evalScenarioDraftRepository;
        this.objectMapper = objectMapper;
    }

    public record TraceImportCandidate(
            String traceId,
            String rootTraceId,
            String sessionId,
            String agentId,
            String agentName,
            String preview,
            String status,
            int tokenCount,
            int llmCallCount,
            int toolCallCount,
            List<String> reasonCodes,
            String startedAt
    ) {}

    @Transactional
    public EvalScenarioEntity importFromTrace(Map<String, Object> request) {
        return importFromTrace(request, "active");
    }

    @Transactional(readOnly = true)
    public List<TraceImportCandidate> suggestImportCandidates(Map<String, ?> filter) {
        int minTokens = intValue(filter.get("minTokens"), DEFAULT_MIN_TOKENS);
        int limit = intValue(filter.get("limit"), DEFAULT_LIMIT);
        String statusFilter = stringValue(filter.get("status"));
        String agentIdFilter = stringValue(filter.get("agentId"));
        Boolean hasToolCalls = booleanValue(filter.get("hasToolCalls"));

        List<LlmTraceEntity> recent = traceRepository.findByOriginOrderByStartedAtDesc("production");
        if (recent.isEmpty()) {
            return List.of();
        }

        Map<String, List<LlmTraceEntity>> tracesByRoot = recent.stream()
                .limit(MAX_SCAN_TRACES)
                .collect(Collectors.groupingBy(
                        trace -> Optional.ofNullable(trace.getRootTraceId()).orElse(trace.getTraceId()),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        List<String> traceIds = tracesByRoot.values().stream()
                .flatMap(Collection::stream)
                .map(LlmTraceEntity::getTraceId)
                .toList();
        Map<String, List<LlmSpanEntity>> spansByTrace = traceIds.isEmpty()
                ? Collections.emptyMap()
                : spanRepository.findByTraceIdInOrderByStartedAtAsc(traceIds).stream()
                .collect(Collectors.groupingBy(LlmSpanEntity::getTraceId));

        Map<String, String> previewByTrace = new HashMap<>();
        for (Object[] row : sessionMessageRepository.findFirstUserMessageContentByTraceIds(traceIds)) {
            String traceId = (String) row[0];
            String preview = flattenContentText((String) row[1]);
            if (preview != null && !preview.isBlank()) {
                previewByTrace.put(traceId, truncate(preview, 160));
            }
        }

        List<TraceImportCandidate> result = new ArrayList<>();
        for (List<LlmTraceEntity> rootTraces : tracesByRoot.values()) {
            LlmTraceEntity primary = rootTraces.get(0);
            if ("running".equals(primary.getStatus())) {
                continue;
            }
            if (agentIdFilter != null && !agentIdFilter.equals(String.valueOf(primary.getAgentId()))) {
                continue;
            }
            int tokens = rootTraces.stream()
                    .mapToInt(trace -> trace.getTotalInputTokens() + trace.getTotalOutputTokens())
                    .sum();
            int toolCalls = rootTraces.stream().mapToInt(LlmTraceEntity::getToolCallCount).sum();
            List<LlmSpanEntity> spans = rootTraces.stream()
                    .flatMap(trace -> spansByTrace.getOrDefault(trace.getTraceId(), List.of()).stream())
                    .toList();
            int llmCalls = (int) spans.stream().filter(span -> "llm".equals(span.getKind())).count();

            Set<String> reasons = new LinkedHashSet<>();
            if ("error".equals(primary.getStatus()) || "cancelled".equals(primary.getStatus()) || isPresent(primary.getError())) {
                reasons.add("agent_error");
            }
            if (spans.stream().anyMatch(this::isToolFailure)) {
                reasons.add("tool_failure");
            }
            if (spans.stream().anyMatch(this::isNonToolSpanError)) {
                reasons.add("span_error");
            }
            if (tokens >= minTokens) {
                reasons.add("high_token");
            }
            if (llmCalls >= 2) {
                reasons.add("multi_turn");
            }
            if (toolCalls > 0) {
                reasons.add("has_tool_calls");
            }

            if (reasons.isEmpty()) {
                continue;
            }
            if (statusFilter != null && !statusFilter.equals(primary.getStatus())) {
                continue;
            }
            if (hasToolCalls != null && hasToolCalls != (toolCalls > 0)) {
                continue;
            }

            String preview = previewByTrace.getOrDefault(primary.getTraceId(), primary.getRootName());
            result.add(new TraceImportCandidate(
                    primary.getTraceId(),
                    Optional.ofNullable(primary.getRootTraceId()).orElse(primary.getTraceId()),
                    primary.getSessionId(),
                    primary.getAgentId() == null ? null : String.valueOf(primary.getAgentId()),
                    primary.getAgentName(),
                    preview,
                    primary.getStatus(),
                    tokens,
                    llmCalls,
                    toolCalls,
                    List.copyOf(reasons),
                    primary.getStartedAt() == null ? null : primary.getStartedAt().toString()
            ));
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    @Transactional
    public List<EvalScenarioEntity> createDraftsFromTraces(Map<String, Object> request) {
        List<String> rootTraceIds = stringList(request.get("rootTraceIds"));
        if (rootTraceIds.isEmpty()) {
            rootTraceIds = stringList(request.get("traceIds"));
        }
        if (rootTraceIds.isEmpty()) {
            throw new IllegalArgumentException("rootTraceIds is required");
        }

        List<EvalScenarioEntity> drafts = new ArrayList<>();
        for (String rootTraceId : rootTraceIds) {
            Map<String, Object> single = new LinkedHashMap<>();
            single.put("rootTraceId", rootTraceId);
            drafts.add(importFromTrace(single, "draft"));
        }
        return drafts;
    }

    private EvalScenarioEntity importFromTrace(Map<String, Object> request, String status) {
        String rootTraceId = stringValue(request.get("rootTraceId"));
        if (rootTraceId == null) {
            throw new IllegalArgumentException("rootTraceId is required");
        }

        List<LlmTraceEntity> traces = traceRepository.findByRootTraceIdOrderByStartedAtAsc(rootTraceId);
        if (traces.isEmpty()) {
            throw new NoSuchElementException("Trace not found: " + rootTraceId);
        }

        LlmTraceEntity primaryTrace = traces.stream()
                .filter(trace -> rootTraceId.equals(trace.getTraceId()))
                .findFirst()
                .orElse(traces.get(0));

        if (primaryTrace.getAgentId() == null) {
            throw new IllegalStateException("Trace has no agentId: " + rootTraceId);
        }

        String traceId = primaryTrace.getTraceId();
        String task = sessionMessageRepository.findTopByTraceIdAndRoleOrderBySeqNoAsc(traceId, "user")
                .map(SessionMessageEntity::getContentJson)
                .map(this::flattenContentText)
                .filter(text -> text != null && !text.isBlank())
                .orElseThrow(() -> new IllegalStateException("Trace has no user input: " + traceId));

        String expected = sessionMessageRepository.findTopByTraceIdAndRoleOrderBySeqNoDesc(traceId, "assistant")
                .map(SessionMessageEntity::getContentJson)
                .map(this::flattenContentText)
                .filter(text -> text != null && !text.isBlank())
                .orElse(null);

        EvalScenarioEntity entity = new EvalScenarioEntity();
        entity.setId(Optional.ofNullable(stringValue(request.get("scenarioId"))).orElse(UUID.randomUUID().toString()));
        entity.setAgentId(String.valueOf(primaryTrace.getAgentId()));
        entity.setName(Optional.ofNullable(stringValue(request.get("name"))).orElse(defaultName(task)));
        entity.setTask(task);
        entity.setOracleType("llm_judge");
        entity.setOracleExpected(expected);
        entity.setStatus(status);
        if ("active".equals(status)) {
            entity.setReviewedAt(Instant.now());
        }
        entity.setSourceSessionId(primaryTrace.getSessionId());
        entity.setExtractionRationale(("draft".equals(status) ? "Candidate imported from trace " : "Imported from trace ") + rootTraceId);
        entity.setCategory("trace_import");
        entity.setSplit("held_out");
        entity.setVersion(1);
        entity.setParentScenarioId(null);
        return evalScenarioDraftRepository.save(entity);
    }

    private String flattenContentText(String contentJson) {
        if (contentJson == null || contentJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(contentJson);
            if (node.isTextual()) {
                return node.asText();
            }
            if (!node.isArray()) {
                return contentJson;
            }
            StringBuilder out = new StringBuilder();
            for (JsonNode block : node) {
                if (!block.isObject()) {
                    continue;
                }
                String type = block.path("type").asText("");
                if ("text".equals(type)) {
                    appendBlock(out, block.path("text").asText(""));
                } else if ("tool_use".equals(type)) {
                    appendBlock(out, "🔧 " + block.path("name").asText(""));
                } else if ("tool_result".equals(type)) {
                    appendBlock(out, block.path("content").asText(""));
                }
            }
            return out.toString().trim();
        } catch (Exception e) {
            return contentJson;
        }
    }

    private boolean isToolFailure(LlmSpanEntity span) {
        return "tool".equals(span.getKind()) && (isPresent(span.getError()) || "error".equals(span.getFinishReason()));
    }

    private boolean isNonToolSpanError(LlmSpanEntity span) {
        return !"tool".equals(span.getKind()) && (isPresent(span.getError()) || "error".equals(span.getFinishReason()));
    }

    private void appendBlock(StringBuilder out, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (!out.isEmpty()) {
            out.append('\n');
        }
        out.append(text.trim());
    }

    private static List<String> stringList(Object raw) {
        if (!(raw instanceof Collection<?> values)) {
            return List.of();
        }
        return values.stream()
                .map(TraceScenarioImportService::stringValue)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
    }

    private static String stringValue(Object raw) {
        if (raw == null) {
            return null;
        }
        String text = String.valueOf(raw).trim();
        return text.isEmpty() ? null : text;
    }

    private static Integer intValue(Object raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(raw));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Boolean booleanValue(Object raw) {
        if (raw == null) {
            return null;
        }
        return Boolean.parseBoolean(String.valueOf(raw));
    }

    private static boolean isPresent(String text) {
        return text != null && !text.isBlank();
    }

    private static String defaultName(String task) {
        String compact = task.replaceAll("\\s+", " ").trim();
        if (compact.length() <= DEFAULT_NAME_MAX) {
            return compact;
        }
        return compact.substring(0, DEFAULT_NAME_MAX - 1) + "…";
    }

    private static String truncate(String text, int max) {
        String compact = text.replaceAll("\\s+", " ").trim();
        if (compact.length() <= max) {
            return compact;
        }
        return compact.substring(0, max - 1) + "…";
    }
}
