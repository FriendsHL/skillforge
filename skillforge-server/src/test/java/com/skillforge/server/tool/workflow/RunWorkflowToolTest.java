package com.skillforge.server.tool.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.workflow.WorkflowDefinition;
import com.skillforge.workflow.WorkflowDefinitionRegistry;
import com.skillforge.workflow.WorkflowRunnerService;
import com.skillforge.workflow.exception.WorkflowAlreadyRunningException;
import com.skillforge.workflow.exception.WorkflowMetaException;
import com.skillforge.workflow.exception.WorkflowNotFoundException;
import com.skillforge.workflow.exception.WorkflowNotPausedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AUTOEVOLVING — unit test for {@link RunWorkflowTool}: the three execution modes
 * (name / inline / resume), validation errors, and engine-exception mapping.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RunWorkflowTool")
class RunWorkflowToolTest {

    @Mock
    private WorkflowRunnerService runnerService;

    @Mock
    private WorkflowDefinitionRegistry registry;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private RunWorkflowTool tool;

    @BeforeEach
    void setUp() {
        tool = new RunWorkflowTool(runnerService, registry, objectMapper);
    }

    @Test
    void descriptionCoversInlineDslContractAndMinimalRunnableExample() {
        assertThat(tool.getDescription())
                .contains(
                        "runId",
                        "name",
                        "inline",
                        "resume",
                        "export const meta",
                        "phase",
                        "log",
                        "agent",
                        "tool",
                        "parallel",
                        "pipeline",
                        "humanApprove",
                        "ctx",
                        "args",
                        "return")
                .contains("没有已注册工作流")
                .contains("不保存")
                .contains("文件或网络")
                .hasSizeLessThan(2_600);
    }

    // ───────────────────────────── name mode ─────────────────────────────

    @Test
    @DisplayName("name mode: calls startRun(name, args, userId) and returns runId")
    void nameMode_startsRunByName() {
        when(runnerService.startRun(eq("opt-report"), any(), eq(7L))).thenReturn("run-1");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("mode", "name");
        input.put("name", "opt-report");
        input.put("args", Map.of("batchSize", 10));

        SkillResult result = tool.execute(input, new SkillContext("/tmp", "sess", 7L));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("\"runId\":\"run-1\"");
        assertThat(result.getOutput()).contains("\"status\":\"started\"");
        assertThat(result.getOutput()).contains("\"mode\":\"name\"");
        verify(runnerService).startRun(eq("opt-report"),
                eq(Map.of("batchSize", 10)), eq(7L));
    }

    @Test
    @DisplayName("name mode: missing name → validation error, no startRun")
    void nameMode_missingName_validationError() {
        Map<String, Object> input = Map.of("mode", "name");

        SkillResult result = tool.execute(input, new SkillContext());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        assertThat(result.getError()).contains("name is required");
        verify(runnerService, never()).startRun(any(String.class), any(), any());
    }

    // ──────────────────────────── inline mode ────────────────────────────

    @Test
    @DisplayName("inline mode: parseInline + startRun(def, args, userId)")
    void inlineMode_parsesAndStartsDirectly() {
        String script = "export const meta = { name: 'inline-wf', description: 'd' }\nreturn 1;";
        WorkflowDefinition def = new WorkflowDefinition(
                "inline-wf", "d", List.of(), "return 1;", "hash-x");
        when(registry.parseInline(script)).thenReturn(def);
        when(runnerService.startRun(eq(def), any(), eq(9L))).thenReturn("run-2");

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("mode", "inline");
        input.put("script", script);

        SkillResult result = tool.execute(input, new SkillContext("/tmp", "sess", 9L));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("\"runId\":\"run-2\"");
        assertThat(result.getOutput()).contains("\"mode\":\"inline\"");
        verify(registry).parseInline(script);
        verify(runnerService).startRun(eq(def), eq(Map.of()), eq(9L));
        // inline must NOT go through the by-name path.
        verify(runnerService, never()).startRun(any(String.class), any(), any());
    }

    @Test
    @DisplayName("inline mode: missing script → validation error")
    void inlineMode_missingScript_validationError() {
        Map<String, Object> input = Map.of("mode", "inline");

        SkillResult result = tool.execute(input, new SkillContext());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("script is required");
        verify(runnerService, never()).startRun(any(WorkflowDefinition.class), any(), any());
    }

    @Test
    @DisplayName("inline mode: bad source (parseInline throws meta) → validation error")
    void inlineMode_parseError_validationError() {
        String script = "phase('x');\nreturn 1;";
        when(registry.parseInline(script))
                .thenThrow(new WorkflowMetaException("inline.workflow.js: missing meta"));
        Map<String, Object> input = Map.of("mode", "inline", "script", script);

        SkillResult result = tool.execute(input, new SkillContext());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        assertThat(result.getError()).contains("missing meta");
    }

    // ──────────────────────────── resume mode ────────────────────────────

    @Test
    @DisplayName("resume mode: approved → resume(runId, true, reason, agent:userId)")
    void resumeMode_approved_callsResume() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("mode", "resume");
        input.put("resumeRunId", "run-3");
        input.put("decision", "approved");
        input.put("reason", "looks good");

        SkillResult result = tool.execute(input, new SkillContext("/tmp", "sess", 42L));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("\"runId\":\"run-3\"");
        assertThat(result.getOutput()).contains("\"status\":\"resumed\"");
        verify(runnerService).resume("run-3", true, "looks good", "agent:42");
    }

    @Test
    @DisplayName("resume mode: rejected → resume(runId, false, ...)")
    void resumeMode_rejected_callsResumeFalse() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("mode", "resume");
        input.put("resumeRunId", "run-4");
        input.put("decision", "rejected");

        SkillResult result = tool.execute(input, new SkillContext("/tmp", "sess", 5L));

        assertThat(result.isSuccess()).isTrue();
        verify(runnerService).resume("run-4", false, null, "agent:5");
    }

    @Test
    @DisplayName("resume mode: missing resumeRunId → validation error")
    void resumeMode_missingRunId_validationError() {
        Map<String, Object> input = Map.of("mode", "resume", "decision", "approved");

        SkillResult result = tool.execute(input, new SkillContext());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("resumeRunId is required");
        verify(runnerService, never()).resume(any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any());
    }

    @Test
    @DisplayName("resume mode: invalid decision → validation error")
    void resumeMode_invalidDecision_validationError() {
        Map<String, Object> input = Map.of(
                "mode", "resume", "resumeRunId", "run-5", "decision", "maybe");

        SkillResult result = tool.execute(input, new SkillContext());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("approved");
    }

    // ──────────────────────── mode-level validation ──────────────────────

    @Test
    @DisplayName("missing mode → validation error")
    void missingMode_validationError() {
        SkillResult result = tool.execute(Map.of("name", "x"), new SkillContext());
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("mode is required");
    }

    @Test
    @DisplayName("empty input → validation error")
    void emptyInput_validationError() {
        SkillResult result = tool.execute(Map.of(), new SkillContext());
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("input is required");
    }

    @Test
    @DisplayName("unknown mode → validation error")
    void unknownMode_validationError() {
        SkillResult result = tool.execute(Map.of("mode", "bogus"), new SkillContext());
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("unknown mode");
    }

    // ───────────────────────── engine exception mapping ──────────────────

    @Test
    @DisplayName("name mode: WorkflowNotFoundException → SkillResult.error")
    void nameMode_notFound_returnsError() {
        when(runnerService.startRun(eq("ghost"), any(), any()))
                .thenThrow(new WorkflowNotFoundException("ghost"));
        Map<String, Object> input = Map.of("mode", "name", "name", "ghost");

        SkillResult result = tool.execute(input, new SkillContext());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.EXECUTION);
        assertThat(result.getError()).contains("Workflow not found");
    }

    @Test
    @DisplayName("name mode: WorkflowAlreadyRunningException (409) → SkillResult.error")
    void nameMode_alreadyRunning_returnsError() {
        when(runnerService.startRun(eq("opt-report"), any(), any()))
                .thenThrow(new WorkflowAlreadyRunningException("opt-report"));
        Map<String, Object> input = Map.of("mode", "name", "name", "opt-report");

        SkillResult result = tool.execute(input, new SkillContext());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("already running");
    }

    @Test
    @DisplayName("resume mode: WorkflowNotPausedException → SkillResult.error")
    void resumeMode_notPaused_returnsError() {
        org.mockito.Mockito.doThrow(new WorkflowNotPausedException("run-6", "completed"))
                .when(runnerService).resume(eq("run-6"), eq(true), any(), any());
        Map<String, Object> input = Map.of(
                "mode", "resume", "resumeRunId", "run-6", "decision", "approved");

        SkillResult result = tool.execute(input, new SkillContext());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("not paused");
    }

    // ──────────────────────────── schema shape ───────────────────────────

    @Test
    @DisplayName("getName / getDescription / getToolSchema / isReadOnly")
    void schemaShape() {
        assertThat(tool.getName()).isEqualTo("RunWorkflow");
        assertThat(tool.getDescription()).isNotBlank();
        assertThat(tool.getToolSchema()).isNotNull();
        assertThat(tool.getToolSchema().getInputSchema()).containsKey("properties");
        assertThat(tool.isReadOnly()).isFalse();
    }
}
