package com.skillforge.server.tool.sessionannotation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.sessionannotation.SessionAnnotationLlmService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Wiring-level tests for {@link AnnotateSessionTool}. The LLM service tests own
 * the main behavior (enum validation / idempotency / row shapes); these tests
 * lock JSON input parsing + JSON output shape + the optional top_failing_tool
 * threading.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AnnotateSessionTool")
class AnnotateSessionToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock private SessionAnnotationLlmService llmService;

    @Test
    @DisplayName("execute calls service with the correct args and returns the §4.3 JSON shape")
    void execute_callsServiceWithCorrectArgs() throws Exception {
        when(llmService.annotateSession(
                eq("sess-A"), eq("failure"), eq("skill"),
                any(BigDecimal.class), eq("FileWrite returned non-zero"), eq("FileWriteTool")))
                .thenReturn(List.of(101L, 102L, 103L));

        AnnotateSessionTool tool = new AnnotateSessionTool(llmService, objectMapper);
        Map<String, Object> input = new HashMap<>();
        input.put("sessionId", "sess-A");
        input.put("outcome", "failure");
        input.put("suspect_surface", "skill");
        input.put("confidence", 0.85);
        input.put("reasoning", "FileWrite returned non-zero");
        input.put("top_failing_tool", "FileWriteTool");

        SkillResult result = tool.execute(input, new SkillContext(null, null, 0L));

        assertThat(result.isSuccess()).isTrue();
        JsonNode root = objectMapper.readTree(result.getOutput());
        assertThat(root.path("ok").asBoolean()).isTrue();
        assertThat(root.path("sessionId").asText()).isEqualTo("sess-A");
        assertThat(root.path("rows_written").asInt()).isEqualTo(3);
        JsonNode ids = root.path("annotation_ids");
        assertThat(ids.isArray()).isTrue();
        assertThat(ids).hasSize(3);
        assertThat(ids.get(0).asLong()).isEqualTo(101L);
        assertThat(ids.get(2).asLong()).isEqualTo(103L);

        // Confidence was parsed correctly into a BigDecimal.
        ArgumentCaptor<BigDecimal> conf = ArgumentCaptor.forClass(BigDecimal.class);
        verify(llmService).annotateSession(
                eq("sess-A"), eq("failure"), eq("skill"),
                conf.capture(), eq("FileWrite returned non-zero"), eq("FileWriteTool"));
        assertThat(conf.getValue()).isEqualByComparingTo("0.85");
    }

    @Test
    @DisplayName("execute returns JSON shape with empty annotation_ids on idempotent re-run")
    void execute_returnsJsonShapeWithAnnotationIds_emptyOnRerun() throws Exception {
        // Service returns empty list when all rows conflicted on UNIQUE.
        when(llmService.annotateSession(anyString(), anyString(), anyString(),
                any(BigDecimal.class), anyString(), anyString()))
                .thenReturn(List.of());

        AnnotateSessionTool tool = new AnnotateSessionTool(llmService, objectMapper);
        SkillResult result = tool.execute(Map.of(
                "sessionId", "sess-DUP",
                "outcome", "failure",
                "suspect_surface", "skill",
                "confidence", 0.9,
                "reasoning", "duplicate",
                "top_failing_tool", "BashTool"
        ), new SkillContext(null, null, 0L));

        assertThat(result.isSuccess()).isTrue();
        JsonNode root = objectMapper.readTree(result.getOutput());
        assertThat(root.path("rows_written").asInt()).isEqualTo(0);
        assertThat(root.path("annotation_ids").isArray()).isTrue();
        assertThat(root.path("annotation_ids")).isEmpty();
    }

    @Test
    @DisplayName("execute handles null top_failing_tool — threads through as null")
    void execute_handlesNullTopFailingToolGracefully() {
        when(llmService.annotateSession(
                eq("sess-N"), eq("success"), eq("unclear"),
                any(BigDecimal.class), eq("all good"), isNull()))
                .thenReturn(List.of(1L, 2L));

        AnnotateSessionTool tool = new AnnotateSessionTool(llmService, objectMapper);
        Map<String, Object> input = new HashMap<>();
        input.put("sessionId", "sess-N");
        input.put("outcome", "success");
        input.put("suspect_surface", "unclear");
        input.put("confidence", 1.0);
        input.put("reasoning", "all good");
        // top_failing_tool intentionally omitted

        SkillResult result = tool.execute(input, new SkillContext(null, null, 0L));

        assertThat(result.isSuccess()).isTrue();
        verify(llmService).annotateSession(
                eq("sess-N"), eq("success"), eq("unclear"),
                any(BigDecimal.class), eq("all good"), isNull());
    }

    @Test
    @DisplayName("execute returns an error SkillResult on service IllegalArgumentException")
    void execute_returnsErrorOnInvalidInput() {
        when(llmService.annotateSession(anyString(), anyString(), anyString(),
                any(BigDecimal.class), anyString(), any()))
                .thenThrow(new IllegalArgumentException("outcome must be one of [...]; got bogus"));

        AnnotateSessionTool tool = new AnnotateSessionTool(llmService, objectMapper);
        SkillResult result = tool.execute(Map.of(
                "sessionId", "sess-X",
                "outcome", "bogus",
                "suspect_surface", "skill",
                "confidence", 0.5,
                "reasoning", "x"
        ), new SkillContext(null, null, 0L));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("AnnotateSession validation");
        assertThat(result.getError()).contains("outcome");
    }

    @Test
    @DisplayName("execute rejects null input map upfront — no service call")
    void execute_returnsErrorOnNullInput() {
        AnnotateSessionTool tool = new AnnotateSessionTool(llmService, objectMapper);

        SkillResult result = tool.execute(null, new SkillContext(null, null, 0L));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).containsIgnoringCase("input is required");
        verify(llmService, never()).annotateSession(
                anyString(), anyString(), anyString(),
                any(BigDecimal.class), anyString(), any());
    }
}
