package com.skillforge.server.tool.optreport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.observability.entity.LlmSpanEntity;
import com.skillforge.observability.repository.LlmSpanRepository;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("LoadErrorSpanBatchTool")
class LoadErrorSpanBatchToolTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-06-03T10:00:00Z");
    private static final ZoneId UTC = ZoneId.of("UTC");

    @Mock private SessionRepository sessionRepository;
    @Mock private LlmSpanRepository spanRepository;

    private ObjectMapper objectMapper;
    private LoadErrorSpanBatchTool tool;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        Clock clock = Clock.fixed(FIXED_NOW, UTC);
        tool = new LoadErrorSpanBatchTool(sessionRepository, spanRepository, objectMapper, clock);
    }

    @Test
    @DisplayName("happy path: groups error spans by (tool + masked signature) across sessions, count DESC")
    void happyPath_groupsBySignature() throws Exception {
        SessionEntity s1 = newSession("sess-1", 1L, FIXED_NOW.minus(Duration.ofDays(1)),
                SessionEntity.ORIGIN_PRODUCTION, null);
        SessionEntity s2 = newSession("sess-2", 1L, FIXED_NOW.minus(Duration.ofDays(2)),
                SessionEntity.ORIGIN_PRODUCTION, null);
        when(sessionRepository.findByAgentId(1L)).thenReturn(List.of(s1, s2));

        List<LlmSpanEntity> spans = new ArrayList<>();
        // Group A: Grep path errors — differ only by path → collapse into one group.
        // sess-1 x2, sess-2 x1 → count 3 across 2 sessions.
        spans.add(toolErrorSpan("sess-1", 0, "Grep", "Path is not a directory: /a/b/src", "path_error"));
        spans.add(toolErrorSpan("sess-1", 2, "Grep", "Path is not a directory: /c/d/src", "path_error"));
        spans.add(toolErrorSpan("sess-2", 1, "Grep", "Path is not a directory: /x/y/src", "path_error"));
        // Group B: Edit old_string not found — sess-1 x1.
        spans.add(toolErrorSpan("sess-1", 3, "Edit", "old_string not found in file", "edit_error"));
        // Non-error tool span (success) — excluded.
        spans.add(toolSuccessSpan("sess-1", 1, "Read"));
        // Non-tool span (llm) with an error — excluded (kind != tool).
        spans.add(llmErrorSpan("sess-2", 0, "rate limited"));

        when(spanRepository.findBySessionIdInOrderByStartedAtAsc(anyCollection())).thenReturn(spans);

        SkillResult result = tool.execute(Map.of("agentId", 1L, "windowDays", 30),
                new SkillContext(null, null, 0L));

        assertThat(result.isSuccess()).isTrue();
        JsonNode root = objectMapper.readTree(result.getOutput());
        assertThat(root.path("agentId").asLong()).isEqualTo(1L);
        assertThat(root.path("sessionCount").asInt()).isEqualTo(2);
        assertThat(root.path("errorSpanCount").asInt()).isEqualTo(4); // 3 Grep + 1 Edit
        assertThat(root.path("truncated").asBoolean()).isFalse();

        JsonNode groups = root.path("groups");
        assertThat(groups).hasSize(2);
        // Sorted count DESC → Grep group (3) first, Edit (1) second.
        JsonNode grepGroup = groups.get(0);
        assertThat(grepGroup.path("toolName").asText()).isEqualTo("Grep");
        assertThat(grepGroup.path("count").asInt()).isEqualTo(3);
        assertThat(grepGroup.path("sessionCount").asInt()).isEqualTo(2);
        assertThat(grepGroup.path("exampleSessionIds")).hasSize(2);
        assertThat(grepGroup.path("exampleError").asText()).contains("Path is not a directory");

        JsonNode editGroup = groups.get(1);
        assertThat(editGroup.path("toolName").asText()).isEqualTo("Edit");
        assertThat(editGroup.path("count").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("excludes non-error and non-tool spans entirely")
    void excludesNonErrorAndNonTool() throws Exception {
        SessionEntity s1 = newSession("sess-1", 1L, FIXED_NOW.minus(Duration.ofDays(1)),
                SessionEntity.ORIGIN_PRODUCTION, null);
        when(sessionRepository.findByAgentId(1L)).thenReturn(List.of(s1));

        List<LlmSpanEntity> spans = List.of(
                toolSuccessSpan("sess-1", 0, "Read"),
                llmErrorSpan("sess-1", 1, "boom"),
                toolBlankErrorSpan("sess-1", 2, "Bash")); // error present but blank → excluded
        when(spanRepository.findBySessionIdInOrderByStartedAtAsc(anyCollection())).thenReturn(spans);

        SkillResult r = tool.execute(Map.of("agentId", 1L), new SkillContext(null, null, 0L));

        assertThat(r.isSuccess()).isTrue();
        JsonNode root = objectMapper.readTree(r.getOutput());
        assertThat(root.path("errorSpanCount").asInt()).isZero();
        assertThat(root.path("groups")).isEmpty();
    }

    @Test
    @DisplayName("session universe: skips sub-sessions, eval origin, and out-of-window sessions")
    void sessionUniverse_filtersLikeLoadSessionBatch() throws Exception {
        SessionEntity prod = newSession("sess-prod", 1L, FIXED_NOW.minus(Duration.ofDays(1)),
                SessionEntity.ORIGIN_PRODUCTION, null);
        SessionEntity sub = newSession("sess-sub", 1L, FIXED_NOW.minus(Duration.ofDays(1)),
                SessionEntity.ORIGIN_PRODUCTION, "sess-prod");
        SessionEntity eval = newSession("sess-eval", 1L, FIXED_NOW.minus(Duration.ofDays(1)),
                SessionEntity.ORIGIN_EVAL, null);
        SessionEntity old = newSession("sess-old", 1L, FIXED_NOW.minus(Duration.ofDays(40)),
                SessionEntity.ORIGIN_PRODUCTION, null);
        when(sessionRepository.findByAgentId(1L)).thenReturn(List.of(prod, sub, eval, old));

        // Only sess-prod should be queried for spans.
        when(spanRepository.findBySessionIdInOrderByStartedAtAsc(anyCollection())).thenReturn(List.of(
                toolErrorSpan("sess-prod", 0, "Bash", "command failed", "exec_error")));

        SkillResult r = tool.execute(Map.of("agentId", 1L, "windowDays", 30),
                new SkillContext(null, null, 0L));

        assertThat(r.isSuccess()).isTrue();
        JsonNode root = objectMapper.readTree(r.getOutput());
        assertThat(root.path("sessionCount").asInt()).isEqualTo(1);
        assertThat(root.path("groups").get(0).path("exampleSessionIds").get(0).asText()).isEqualTo("sess-prod");
    }

    @Test
    @DisplayName("no production sessions → empty groups, errorSpanCount=0, spans never queried")
    void noSessions_emptyOk() throws Exception {
        when(sessionRepository.findByAgentId(1L)).thenReturn(List.of());
        // span repo stub left lenient — must not be invoked when there are no sessions.
        lenient().when(spanRepository.findBySessionIdInOrderByStartedAtAsc(anyCollection()))
                .thenReturn(List.of());

        SkillResult r = tool.execute(Map.of("agentId", 1L), new SkillContext(null, null, 0L));

        assertThat(r.isSuccess()).isTrue();
        JsonNode root = objectMapper.readTree(r.getOutput());
        assertThat(root.path("sessionCount").asInt()).isZero();
        assertThat(root.path("errorSpanCount").asInt()).isZero();
        assertThat(root.path("groups")).isEmpty();
    }

    @Test
    @DisplayName("validation: missing agentId → VALIDATION error")
    void validation_missingAgentId() {
        SkillResult r = tool.execute(Map.of(), new SkillContext(null, null, 0L));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
    }

    @Test
    @DisplayName("validation: non-positive agentId → VALIDATION error")
    void validation_negativeAgentId() {
        SkillResult r = tool.execute(Map.of("agentId", 0), new SkillContext(null, null, 0L));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
    }

    @Test
    @DisplayName("normalizeError masks paths, quotes, and numbers so variants collapse")
    void normalizeError_masksVolatileParts() {
        String a = LoadErrorSpanBatchTool.normalizeError("Path is not a directory: /a/b/src");
        String b = LoadErrorSpanBatchTool.normalizeError("Path is not a directory: /x/y/src");
        assertThat(a).isEqualTo(b);

        String q1 = LoadErrorSpanBatchTool.normalizeError("old_string \"foo\" not found at line 12");
        String q2 = LoadErrorSpanBatchTool.normalizeError("old_string \"bar\" not found at line 99");
        assertThat(q1).isEqualTo(q2);
    }

    // ── helpers ──

    private static SessionEntity newSession(String id, long agentId, Instant createdAt,
                                            String origin, String parentId) {
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

    private static LlmSpanEntity toolErrorSpan(String sessionId, int iter, String name,
                                               String error, String errorType) {
        LlmSpanEntity s = baseSpan(sessionId, iter);
        s.setKind("tool");
        s.setName(name);
        s.setError(error);
        s.setErrorType(errorType);
        return s;
    }

    private static LlmSpanEntity toolBlankErrorSpan(String sessionId, int iter, String name) {
        LlmSpanEntity s = baseSpan(sessionId, iter);
        s.setKind("tool");
        s.setName(name);
        s.setError("   "); // blank → not a real error
        return s;
    }

    private static LlmSpanEntity toolSuccessSpan(String sessionId, int iter, String name) {
        LlmSpanEntity s = baseSpan(sessionId, iter);
        s.setKind("tool");
        s.setName(name);
        s.setError(null);
        return s;
    }

    private static LlmSpanEntity llmErrorSpan(String sessionId, int iter, String error) {
        LlmSpanEntity s = baseSpan(sessionId, iter);
        s.setKind("llm");
        s.setError(error);
        return s;
    }

    private static LlmSpanEntity baseSpan(String sessionId, int iter) {
        LlmSpanEntity s = new LlmSpanEntity();
        s.setSpanId(sessionId + "-" + iter);
        s.setTraceId("trace-" + sessionId);
        s.setSessionId(sessionId);
        s.setIterationIndex(iter);
        s.setStartedAt(FIXED_NOW);
        return s;
    }
}
