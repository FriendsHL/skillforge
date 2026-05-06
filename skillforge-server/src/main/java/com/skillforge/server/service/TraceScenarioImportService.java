package com.skillforge.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.observability.entity.LlmTraceEntity;
import com.skillforge.observability.repository.LlmTraceRepository;
import com.skillforge.server.entity.EvalScenarioEntity;
import com.skillforge.server.entity.SessionMessageEntity;
import com.skillforge.server.repository.EvalScenarioDraftRepository;
import com.skillforge.server.repository.SessionMessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

@Service
public class TraceScenarioImportService {

    private static final int DEFAULT_NAME_MAX = 80;

    private final LlmTraceRepository traceRepository;
    private final SessionMessageRepository sessionMessageRepository;
    private final EvalScenarioDraftRepository evalScenarioDraftRepository;
    private final ObjectMapper objectMapper;

    public TraceScenarioImportService(LlmTraceRepository traceRepository,
                                      SessionMessageRepository sessionMessageRepository,
                                      EvalScenarioDraftRepository evalScenarioDraftRepository,
                                      ObjectMapper objectMapper) {
        this.traceRepository = traceRepository;
        this.sessionMessageRepository = sessionMessageRepository;
        this.evalScenarioDraftRepository = evalScenarioDraftRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public EvalScenarioEntity importFromTrace(Map<String, Object> request) {
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
        entity.setStatus("active");
        entity.setReviewedAt(Instant.now());
        entity.setSourceSessionId(primaryTrace.getSessionId());
        entity.setExtractionRationale("Imported from trace " + rootTraceId);
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

    private void appendBlock(StringBuilder out, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (!out.isEmpty()) {
            out.append('\n');
        }
        out.append(text.trim());
    }

    private static String stringValue(Object raw) {
        if (raw == null) {
            return null;
        }
        String text = String.valueOf(raw).trim();
        return text.isEmpty() ? null : text;
    }

    private static String defaultName(String task) {
        String compact = task.replaceAll("\\s+", " ").trim();
        if (compact.length() <= DEFAULT_NAME_MAX) {
            return compact;
        }
        return compact.substring(0, DEFAULT_NAME_MAX - 1) + "…";
    }
}
