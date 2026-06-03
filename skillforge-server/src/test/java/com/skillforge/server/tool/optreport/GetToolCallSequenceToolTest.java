package com.skillforge.server.tool.optreport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.observability.entity.LlmSpanEntity;
import com.skillforge.observability.repository.LlmSpanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetToolCallSequenceTool")
class GetToolCallSequenceToolTest {

    private static final Instant T0 = Instant.parse("2026-06-03T10:00:00Z");

    @Mock private LlmSpanRepository spanRepository;

    private ObjectMapper objectMapper;
    private GetToolCallSequenceTool tool;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        tool = new GetToolCallSequenceTool(spanRepository, objectMapper);
    }

    @Test
    @DisplayName("returns ordered tool-only sequence with error/inputPreview, skips llm spans")
    void returnsOrderedToolSequence() throws Exception {
        List<LlmSpanEntity> spans = List.of(
                toolSpan("sess-1", 0, "Read", "{\"path\":\"/a.txt\"}", null, null),
                llmSpan("sess-1", 0),
                toolSpan("sess-1", 1, "Edit", "{\"old_string\":\"x\"}",
                        "old_string not found in file", "edit_error"),
                toolSpan("sess-1", 2, "Bash", "{\"cmd\":\"ls\"}", null, null));
        when(spanRepository.findBySessionIdOrderByStartedAtAsc("sess-1")).thenReturn(spans);

        SkillResult r = tool.execute(Map.of("sessionId", "sess-1"), new SkillContext(null, null, 0L));

        assertThat(r.isSuccess()).isTrue();
        JsonNode root = objectMapper.readTree(r.getOutput());
        assertThat(root.path("sessionId").asText()).isEqualTo("sess-1");
        assertThat(root.path("toolCallCount").asInt()).isEqualTo(3); // llm span excluded
        assertThat(root.path("truncated").asBoolean()).isFalse();

        JsonNode calls = root.path("toolCalls");
        assertThat(calls).hasSize(3);
        // Order preserved (started_at ASC as returned by repo).
        assertThat(calls.get(0).path("name").asText()).isEqualTo("Read");
        assertThat(calls.get(0).path("error").isNull()).isTrue();
        assertThat(calls.get(1).path("name").asText()).isEqualTo("Edit");
        assertThat(calls.get(1).path("error").asText()).isEqualTo("old_string not found in file");
        assertThat(calls.get(1).path("errorType").asText()).isEqualTo("edit_error");
        assertThat(calls.get(1).path("inputPreview").asText()).contains("old_string");
        assertThat(calls.get(2).path("name").asText()).isEqualTo("Bash");
    }

    @Test
    @DisplayName("session with no tool spans → empty toolCalls, count 0 (not error)")
    void noToolSpans_emptyOk() throws Exception {
        when(spanRepository.findBySessionIdOrderByStartedAtAsc("sess-2"))
                .thenReturn(List.of(llmSpan("sess-2", 0)));

        SkillResult r = tool.execute(Map.of("sessionId", "sess-2"), new SkillContext(null, null, 0L));

        assertThat(r.isSuccess()).isTrue();
        JsonNode root = objectMapper.readTree(r.getOutput());
        assertThat(root.path("toolCallCount").asInt()).isZero();
        assertThat(root.path("toolCalls")).isEmpty();
    }

    @Test
    @DisplayName("validation: missing sessionId → VALIDATION error")
    void validation_missingSessionId() {
        SkillResult r = tool.execute(Map.of(), new SkillContext(null, null, 0L));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
    }

    private static LlmSpanEntity toolSpan(String sessionId, int iter, String name,
                                          String inputSummary, String error, String errorType) {
        LlmSpanEntity s = baseSpan(sessionId, iter);
        s.setKind("tool");
        s.setName(name);
        s.setInputSummary(inputSummary);
        s.setError(error);
        s.setErrorType(errorType);
        return s;
    }

    private static LlmSpanEntity llmSpan(String sessionId, int iter) {
        LlmSpanEntity s = baseSpan(sessionId, iter);
        s.setKind("llm");
        return s;
    }

    private static LlmSpanEntity baseSpan(String sessionId, int iter) {
        LlmSpanEntity s = new LlmSpanEntity();
        s.setSpanId(sessionId + "-" + iter);
        s.setTraceId("trace-" + sessionId);
        s.setSessionId(sessionId);
        s.setIterationIndex(iter);
        s.setStartedAt(T0);
        return s;
    }
}
