package com.skillforge.server.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.observability.api.LlmTraceStore;
import com.skillforge.observability.api.LlmTraceStore.TraceWithSpans;
import com.skillforge.observability.domain.LlmSpan;
import com.skillforge.observability.domain.LlmSpanSource;
import com.skillforge.observability.domain.LlmTrace;
import com.skillforge.observability.entity.LlmTraceEntity;
import com.skillforge.observability.repository.LlmTraceRepository;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.service.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * OBS-2 M4 — GetTraceTool reads from {@link LlmTraceStore} (unified
 * {@code t_llm_trace} + {@code t_llm_span}) instead of the legacy
 * {@code TraceSpanRepository} ({@code t_trace_span}). Mocks reflect the new
 * read path; output shape is asserted to stay close to the pre-M4 form.
 */
@ExtendWith(MockitoExtension.class)
class GetTraceToolTest {

    @Mock
    private LlmTraceStore traceStore;
    @Mock
    private LlmTraceRepository traceRepository;
    @Mock
    private SessionService sessionService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private GetTraceTool tool;

    @BeforeEach
    void setUp() {
        tool = new GetTraceTool(traceStore, traceRepository, sessionService, objectMapper);
    }

    @Test
    void listTraces_defaultsToCurrentSessionAndSummarizesAggregates() throws Exception {
        Instant t = Instant.parse("2026-04-26T00:00:00Z");
        LlmTraceEntity te = traceEntity("root", "s1", "Agent loop", t, t.plusMillis(1234),
                "ok", null, 1234L, 3, 1, "Greeter");
        when(sessionService.getSession("s1")).thenReturn(session("s1", 1L));
        when(traceRepository.findBySessionIdOrderByStartedAtDesc("s1"))
                .thenReturn(List.of(te));
        // OBS-2 M4: per-trace LLM call count from t_llm_span.
        when(traceStore.listSpansByTrace(eq("root"), eq(Set.of("llm")), anyInt()))
                .thenReturn(List.of(llmSpan("llm-1", "root", "s1", t)));

        SkillResult result = tool.execute(Map.of("action", "list_traces"), new SkillContext(null, "s1", 1L));

        assertThat(result.isSuccess()).isTrue();
        JsonNode json = objectMapper.readTree(result.getOutput());
        assertThat(json.path("count").asInt()).isEqualTo(1);
        JsonNode trace = json.path("traces").get(0);
        assertThat(trace.path("traceId").asText()).isEqualTo("root");
        // OBS-2 M4: name now prefers agentName over rootName.
        assertThat(trace.path("name").asText()).isEqualTo("Greeter");
        assertThat(trace.path("status").asText()).isEqualTo("ok");
        assertThat(trace.path("success").asBoolean()).isTrue();
        assertThat(trace.path("durationMs").asLong()).isEqualTo(1234L);
        // Counts come from aggregate (tool / event) and a bounded span query (llm).
        assertThat(trace.path("llmCallCount").asInt()).isEqualTo(1);
        assertThat(trace.path("toolCallCount").asInt()).isEqualTo(3);
        assertThat(trace.path("eventCount").asInt()).isEqualTo(1);
    }

    @Test
    void getTraceReturnsRootFromLlmTraceAndCappedSpansFromLlmSpan() throws Exception {
        Instant t = Instant.parse("2026-04-26T00:00:00Z");
        LlmTrace trace = new LlmTrace(
                "root", "s1", null, 1L, "Agent loop",
                t, t.plusMillis(2000),
                100, 200, BigDecimal.ZERO, LlmSpanSource.LIVE,
                "ok", null, 2000L, 1, 0, "Greeter");
        LlmSpan llm = llmSpan("llm-1", "root", "s1", t);
        LlmSpan toolWithLongInput = toolSpan("tool-1", "root", "s1", "Bash", "x".repeat(700), "ok", t.plusMillis(100));

        when(sessionService.getSession("s1")).thenReturn(session("s1", 1L));
        when(traceStore.readByTraceId("root"))
                .thenReturn(Optional.of(new TraceWithSpans(trace, List.of(llm, toolWithLongInput))));
        // listSpansByTrace called with maxSpans+1=2; we return 2 to flag truncated when maxSpans=1.
        when(traceStore.listSpansByTrace("root", null, 2))
                .thenReturn(List.of(llm, toolWithLongInput));

        SkillResult result = tool.execute(Map.of(
                "action", "get_trace",
                "traceId", "root",
                "maxSpans", 1
        ), new SkillContext(null, "s1", 1L));

        assertThat(result.isSuccess()).isTrue();
        JsonNode json = objectMapper.readTree(result.getOutput());
        assertThat(json.path("returnedSpans").asInt()).isEqualTo(1);
        assertThat(json.path("truncated").asBoolean()).isTrue();
        JsonNode root = json.path("root");
        assertThat(root.path("spanType").asText()).isEqualTo("AGENT_LOOP");
        assertThat(root.path("name").asText()).isEqualTo("Greeter");
        assertThat(root.path("durationMs").asLong()).isEqualTo(2000L);
        // First returned span is the LLM call; spanType is mapped back to legacy form.
        JsonNode firstSpan = json.path("spans").get(0);
        assertThat(firstSpan.path("spanType").asText()).isEqualTo("LLM_CALL");
        assertThat(firstSpan.path("kind").asText()).isEqualTo("llm");
    }

    @Test
    void getTraceTruncatesLongInputPayload() throws Exception {
        Instant t = Instant.parse("2026-04-26T00:00:00Z");
        LlmTrace trace = new LlmTrace(
                "root", "s1", null, 1L, "Agent loop",
                t, t.plusMillis(100),
                0, 0, BigDecimal.ZERO, LlmSpanSource.LIVE,
                "ok", null, 100L, 1, 0, "Greeter");
        LlmSpan toolWithLongInput = toolSpan("tool-1", "root", "s1", "Bash", "x".repeat(700), "ok", t);

        when(sessionService.getSession("s1")).thenReturn(session("s1", 1L));
        when(traceStore.readByTraceId("root"))
                .thenReturn(Optional.of(new TraceWithSpans(trace, List.of(toolWithLongInput))));
        when(traceStore.listSpansByTrace(eq("root"), any(), anyInt()))
                .thenReturn(List.of(toolWithLongInput));

        SkillResult result = tool.execute(Map.of(
                "action", "get_trace",
                "traceId", "root"
        ), new SkillContext(null, "s1", 1L));

        assertThat(result.isSuccess()).isTrue();
        JsonNode json = objectMapper.readTree(result.getOutput());
        // 500 chars + "..." = 503
        assertThat(json.path("spans").get(0).path("input").asText()).hasSize(503);
        assertThat(json.path("spans").get(0).path("spanType").asText()).isEqualTo("TOOL_CALL");
    }

    @Test
    void rejectsOtherUsersSession() {
        when(sessionService.getSession("other")).thenReturn(session("other", 99L));

        SkillResult result = tool.execute(Map.of(
                "action", "list_traces",
                "sessionId", "other"
        ), new SkillContext(null, "s1", 1L));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("not accessible");
    }

    @Test
    void getTraceRejectsCrossSessionTraceId() {
        Instant t = Instant.parse("2026-04-26T00:00:00Z");
        LlmTrace trace = new LlmTrace(
                "root", "s1", null, 1L, "Agent loop",
                t, t.plusMillis(100),
                0, 0, BigDecimal.ZERO, LlmSpanSource.LIVE,
                "ok", null, 100L, 0, 0, null);
        when(traceStore.readByTraceId("root"))
                .thenReturn(Optional.of(new TraceWithSpans(trace, List.of())));

        SkillResult result = tool.execute(Map.of(
                "action", "get_trace",
                "traceId", "root",
                "sessionId", "different-session"
        ), new SkillContext(null, "s1", 1L));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("does not belong to");
    }

    private static SessionEntity session(String id, Long userId) {
        SessionEntity session = new SessionEntity();
        session.setId(id);
        session.setUserId(userId);
        return session;
    }

    private static LlmTraceEntity traceEntity(String traceId, String sessionId, String rootName,
                                              Instant startedAt, Instant endedAt,
                                              String status, String error,
                                              long durationMs, int toolCount, int eventCount,
                                              String agentName) {
        LlmTraceEntity te = new LlmTraceEntity();
        te.setTraceId(traceId);
        te.setSessionId(sessionId);
        te.setRootName(rootName);
        te.setAgentName(agentName);
        te.setStartedAt(startedAt);
        te.setEndedAt(endedAt);
        te.setStatus(status);
        te.setError(error);
        te.setTotalDurationMs(durationMs);
        te.setToolCallCount(toolCount);
        te.setEventCount(eventCount);
        te.setSource("live");
        return te;
    }

    private static LlmSpan llmSpan(String spanId, String traceId, String sessionId, Instant startedAt) {
        return new LlmSpan(
                spanId, traceId, traceId, sessionId, 1L,
                "claude", "claude-3-sonnet",
                0, true, "messages: 1", "ok",
                null, null, null, "ok",
                10, 20, null, null, BigDecimal.ZERO,
                100L, startedAt, startedAt.plusMillis(100),
                "stop", null, null,
                null, null, null,
                Map.of(), LlmSpanSource.LIVE,
                "llm", null, null);
    }

    private static LlmSpan toolSpan(String spanId, String traceId, String sessionId,
                                    String name, String inputSummary, String outputSummary,
                                    Instant startedAt) {
        return new LlmSpan(
                spanId, traceId, traceId, sessionId, 1L,
                null, null,
                0, false, inputSummary, outputSummary,
                null, null, null, "ok",
                0, 0, null, null, BigDecimal.ZERO,
                50L, startedAt, startedAt.plusMillis(50),
                null, null, null,
                null, null, "tool-use-id-" + spanId,
                Map.of(), LlmSpanSource.LIVE,
                "tool", null, name);
    }
}
