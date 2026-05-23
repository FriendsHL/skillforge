package com.skillforge.server.tool.optreport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.entity.SessionAnnotationEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.SessionAnnotationRepository;
import com.skillforge.server.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("LoadSessionBatchTool")
class LoadSessionBatchToolTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-05-22T10:00:00Z");
    private static final ZoneId UTC = ZoneId.of("UTC");

    @Mock private SessionRepository sessionRepository;
    @Mock private SessionAnnotationRepository annotationRepository;
    @Mock private com.skillforge.server.repository.AgentRepository agentRepository;

    private ObjectMapper objectMapper;
    private Clock clock;
    private LoadSessionBatchTool tool;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        clock = Clock.fixed(FIXED_NOW, UTC);
        tool = new LoadSessionBatchTool(sessionRepository, annotationRepository, agentRepository, objectMapper, clock);
    }

    @Test
    @DisplayName("happy path: returns paginated production sessions with annotations + total count")
    void happyPath_returnsSessionsAndAnnotations() throws Exception {
        // 3 candidate sessions for agent 7; one is non-production, one is a sub-session.
        SessionEntity prod1 = newSession("sess-1", 7L, FIXED_NOW.minus(java.time.Duration.ofDays(1)),
                SessionEntity.ORIGIN_PRODUCTION, null);
        SessionEntity prod2 = newSession("sess-2", 7L, FIXED_NOW.minus(java.time.Duration.ofDays(3)),
                SessionEntity.ORIGIN_PRODUCTION, null);
        SessionEntity sub = newSession("sess-sub", 7L, FIXED_NOW.minus(java.time.Duration.ofDays(2)),
                SessionEntity.ORIGIN_PRODUCTION, "sess-1");
        SessionEntity evalSess = newSession("sess-eval", 7L, FIXED_NOW.minus(java.time.Duration.ofDays(2)),
                SessionEntity.ORIGIN_EVAL, null);
        // 10 days old → outside default 7-day window
        SessionEntity old = newSession("sess-old", 7L, FIXED_NOW.minus(java.time.Duration.ofDays(10)),
                SessionEntity.ORIGIN_PRODUCTION, null);

        when(sessionRepository.findByAgentId(7L)).thenReturn(List.of(prod1, prod2, sub, evalSess, old));

        SessionAnnotationEntity a1 = newAnnotation("sess-1", "outcome", "success", "llm", "0.92", null);
        SessionAnnotationEntity a2 = newAnnotation("sess-1", "suspect_surface", "prompt", "llm", "0.85", "Span 12 lookup error");
        when(annotationRepository.findBySessionId("sess-1")).thenReturn(List.of(a1, a2));
        when(annotationRepository.findBySessionId("sess-2")).thenReturn(List.of());

        Map<String, Object> input = new HashMap<>();
        input.put("agentId", 7L);
        input.put("windowDays", 7);
        input.put("offset", 0);
        input.put("limit", 50);

        SkillResult result = tool.execute(input, new SkillContext(null, null, 0L));

        assertThat(result.isSuccess()).isTrue();
        JsonNode root = objectMapper.readTree(result.getOutput());
        assertThat(root.path("agentId").asLong()).isEqualTo(7L);
        assertThat(root.path("windowDays").asInt()).isEqualTo(7);
        assertThat(root.path("total").asInt()).isEqualTo(2); // only prod1 + prod2 qualify
        JsonNode items = root.path("items");
        assertThat(items.isArray()).isTrue();
        assertThat(items).hasSize(2);
        // Sorted newest-first → sess-1 (1 day ago) before sess-2 (3 days ago)
        assertThat(items.get(0).path("sessionId").asText()).isEqualTo("sess-1");
        assertThat(items.get(0).path("annotations")).hasSize(2);
        assertThat(items.get(0).path("annotations").get(0).path("type").asText()).isEqualTo("outcome");
        assertThat(items.get(0).path("annotations").get(0).path("value").asText()).isEqualTo("success");
        assertThat(items.get(1).path("sessionId").asText()).isEqualTo("sess-2");
        assertThat(items.get(1).path("annotations")).isEmpty();
    }

    @Test
    @DisplayName("validation: missing agentId → VALIDATION error")
    void validation_missingAgentId() {
        SkillResult r = tool.execute(Map.of(), new SkillContext(null, null, 0L));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
    }

    @Test
    @DisplayName("validation: negative agentId → VALIDATION error")
    void validation_negativeAgentId() {
        SkillResult r = tool.execute(Map.of("agentId", -3),
                new SkillContext(null, null, 0L));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
    }

    @Test
    @DisplayName("clamping: windowDays=999 / limit=999 → 30 / 200; negative offset → 0")
    void clamping_windowDaysAndLimit() throws Exception {
        when(sessionRepository.findByAgentId(7L)).thenReturn(List.of());
        // any-orphan annotation stub left lenient since list is empty.
        lenient().when(annotationRepository.findBySessionId(eq("nothing"))).thenReturn(List.of());

        Map<String, Object> input = new HashMap<>();
        input.put("agentId", 7L);
        input.put("windowDays", 999);
        input.put("limit", 999);
        input.put("offset", -10);

        SkillResult r = tool.execute(input, new SkillContext(null, null, 0L));
        assertThat(r.isSuccess()).isTrue();
        JsonNode root = objectMapper.readTree(r.getOutput());
        assertThat(root.path("windowDays").asInt()).isEqualTo(30);
        assertThat(root.path("offset").asInt()).isZero();
        assertThat(root.path("total").asInt()).isZero();
    }

    @Test
    @DisplayName("no matching sessions → returns empty items + total=0 (not error)")
    void noMatchingSessions_emptyOk() throws Exception {
        when(sessionRepository.findByAgentId(7L)).thenReturn(List.of());

        SkillResult r = tool.execute(Map.of("agentId", 7L),
                new SkillContext(null, null, 0L));
        assertThat(r.isSuccess()).isTrue();
        JsonNode root = objectMapper.readTree(r.getOutput());
        assertThat(root.path("total").asInt()).isZero();
        assertThat(root.path("items")).isEmpty();
    }

    private static SessionEntity newSession(String id, long agentId, Instant createdAt, String origin, String parentId) {
        SessionEntity s = new SessionEntity();
        s.setId(id);
        s.setAgentId(agentId);
        s.setCreatedAt(LocalDateTime.ofInstant(createdAt, ZoneId.systemDefault()));
        s.setOrigin(origin);
        s.setParentSessionId(parentId);
        s.setRuntimeStatus("idle");
        s.setMessageCount(4);
        return s;
    }

    private static SessionAnnotationEntity newAnnotation(String sessionId, String type, String value,
                                                         String source, String confidence, String reasoning) {
        SessionAnnotationEntity a = new SessionAnnotationEntity();
        a.setSessionId(sessionId);
        a.setAnnotationType(type);
        a.setAnnotationValue(value);
        a.setSource(source);
        a.setConfidence(new BigDecimal(confidence));
        a.setReasoning(reasoning);
        return a;
    }
}
