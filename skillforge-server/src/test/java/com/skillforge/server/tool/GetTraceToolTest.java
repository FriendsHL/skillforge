package com.skillforge.server.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.TraceSpanEntity;
import com.skillforge.server.repository.TraceSpanRepository;
import com.skillforge.server.service.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetTraceToolTest {

    @Mock
    private TraceSpanRepository spanRepository;
    @Mock
    private SessionService sessionService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private GetTraceTool tool;

    @BeforeEach
    void setUp() {
        tool = new GetTraceTool(spanRepository, sessionService, objectMapper);
    }

    @Test
    void listTraces_defaultsToCurrentSessionAndSummarizesRoots() throws Exception {
        TraceSpanEntity root = span("root", "s1", null, "AGENT_LOOP", "Agent loop");
        root.setInput("hello");
        root.setOutput("done");
        TraceSpanEntity llm = span("llm", "s1", "root", "LLM_CALL", "chat");
        TraceSpanEntity toolCall = span("tool", "s1", "root", "TOOL_CALL", "Grep");
        when(sessionService.getSession("s1")).thenReturn(session("s1", 1L));
        when(spanRepository.findBySessionIdAndSpanTypeOrderByStartTimeDesc("s1", "AGENT_LOOP"))
                .thenReturn(List.of(root));
        when(spanRepository.findByParentSpanIdOrderByStartTimeAsc("root")).thenReturn(List.of(llm, toolCall));

        SkillResult result = tool.execute(Map.of("action", "list_traces"), new SkillContext(null, "s1", 1L));

        assertThat(result.isSuccess()).isTrue();
        JsonNode json = objectMapper.readTree(result.getOutput());
        assertThat(json.path("count").asInt()).isEqualTo(1);
        assertThat(json.path("traces").get(0).path("traceId").asText()).isEqualTo("root");
        assertThat(json.path("traces").get(0).path("llmCallCount").asInt()).isEqualTo(1);
        assertThat(json.path("traces").get(0).path("toolCallCount").asInt()).isEqualTo(1);
    }

    @Test
    void getTraceReturnsCappedSpanTreeAndTruncatesIo() throws Exception {
        TraceSpanEntity root = span("root", "s1", null, "AGENT_LOOP", "Agent loop");
        TraceSpanEntity child1 = span("c1", "s1", "root", "LLM_CALL", "chat");
        child1.setInput("x".repeat(700));
        TraceSpanEntity child2 = span("c2", "s1", "root", "TOOL_CALL", "Bash");
        when(sessionService.getSession("s1")).thenReturn(session("s1", 1L));
        when(spanRepository.findById("root")).thenReturn(Optional.of(root));
        when(spanRepository.findByParentSpanIdOrderByStartTimeAsc("root")).thenReturn(List.of(child1, child2));

        SkillResult result = tool.execute(Map.of(
                "action", "get_trace",
                "traceId", "root",
                "maxSpans", 1
        ), new SkillContext(null, "s1", 1L));

        assertThat(result.isSuccess()).isTrue();
        JsonNode json = objectMapper.readTree(result.getOutput());
        assertThat(json.path("returnedSpans").asInt()).isEqualTo(1);
        assertThat(json.path("truncated").asBoolean()).isTrue();
        assertThat(json.path("spans").get(0).path("input").asText()).hasSize(503);
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

    private static SessionEntity session(String id, Long userId) {
        SessionEntity session = new SessionEntity();
        session.setId(id);
        session.setUserId(userId);
        return session;
    }

    private static TraceSpanEntity span(String id, String sessionId, String parentSpanId,
                                        String spanType, String name) {
        TraceSpanEntity span = new TraceSpanEntity();
        span.setId(id);
        span.setSessionId(sessionId);
        span.setParentSpanId(parentSpanId);
        span.setSpanType(spanType);
        span.setName(name);
        span.setStartTime(Instant.parse("2026-04-26T00:00:00Z"));
        span.setEndTime(Instant.parse("2026-04-26T00:00:01Z"));
        span.setDurationMs(1000);
        span.setSuccess(true);
        return span;
    }
}
