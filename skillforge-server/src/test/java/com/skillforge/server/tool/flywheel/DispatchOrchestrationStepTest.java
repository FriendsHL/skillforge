package com.skillforge.server.tool.flywheel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.flywheel.orchestrator.OrchestratorAgentExecutor;
import com.skillforge.server.flywheel.orchestrator.OrchestratorWorkerSpec;
import com.skillforge.server.flywheel.orchestrator.StepResult;
import com.skillforge.server.service.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OPT-LOOP-FRAMEWORK Sprint 2: unit tests for the parent-side fan-out Tool.
 * Plan §5 IT case 3 (minimal use case) + validation paths.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DispatchOrchestrationStep Tool")
class DispatchOrchestrationStepTest {

    @Mock private OrchestratorAgentExecutor executor;
    @Mock private SessionService sessionService;

    private ObjectMapper objectMapper;
    private DispatchOrchestrationStep tool;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        tool = new DispatchOrchestrationStep(executor, sessionService, objectMapper);
    }

    @Test
    @DisplayName("Case 3: happy path — execute returns {stepResults, allSucceeded:true}")
    void case3_happyPath_returnsStructuredPayload() throws Exception {
        SessionEntity parent = new SessionEntity();
        parent.setId("parent-sess");
        parent.setUserId(7L);
        when(sessionService.getSession("parent-sess")).thenReturn(parent);

        List<StepResult> mockResults = List.of(
                new StepResult("step-1", "completed", null, null),
                new StepResult("step-2", "completed",
                        objectMapper.valueToTree(Map.of("k", "v")), null));
        when(executor.awaitDispatch(any(), any(), any(), anyLong())).thenReturn(mockResults);

        Map<String, Object> worker1 = new LinkedHashMap<>();
        worker1.put("agentId", 11);
        worker1.put("agentName", "a1");
        worker1.put("task", "task-1");
        worker1.put("inputJson", Map.of("foo", 1));
        worker1.put("timeoutSeconds", 30);

        Map<String, Object> worker2 = new LinkedHashMap<>();
        worker2.put("agentId", 22);
        worker2.put("task", "task-2");

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("parentRunId", "run-3");
        input.put("workers", List.of(worker1, worker2));

        SkillContext ctx = new SkillContext("/tmp", "parent-sess", 7L);
        SkillResult result = tool.execute(input, ctx);

        assertThat(result.isSuccess()).isTrue();

        // Verify executor was called with parsed specs
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OrchestratorWorkerSpec>> specCaptor = ArgumentCaptor.forClass(List.class);
        verify(executor).awaitDispatch(any(), any(), specCaptor.capture(), anyLong());
        List<OrchestratorWorkerSpec> specs = specCaptor.getValue();
        assertThat(specs).hasSize(2);
        assertThat(specs.get(0).agentId()).isEqualTo(11L);
        assertThat(specs.get(0).inputJson()).contains("\"foo\":1");
        assertThat(specs.get(0).timeoutSeconds()).isEqualTo(30);
        // worker2 — timeoutSeconds defaulted; inputJson defaulted to "{}"
        assertThat(specs.get(1).agentId()).isEqualTo(22L);
        assertThat(specs.get(1).timeoutSeconds()).isEqualTo(300);
        assertThat(specs.get(1).inputJson()).isEqualTo("{}");

        // Output JSON structure
        Map<?, ?> outMap = objectMapper.readValue(result.getOutput(), Map.class);
        assertThat(outMap.get("allSucceeded")).isEqualTo(true);
        List<?> stepResults = (List<?>) outMap.get("stepResults");
        assertThat(stepResults).hasSize(2);
        Map<?, ?> r1 = (Map<?, ?>) stepResults.get(0);
        assertThat(r1.get("stepRunId")).isEqualTo("step-1");
        assertThat(r1.get("status")).isEqualTo("completed");
    }

    @Test
    @DisplayName("Validation: missing parentRunId → validationError, executor never called")
    void validation_missingParentRunId() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("workers", List.of(Map.of("agentId", 1, "task", "t")));
        SkillResult result = tool.execute(input, new SkillContext("/tmp", "ps", 7L));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("parentRunId");
        verify(executor, never()).awaitDispatch(any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("Validation: empty workers list → validationError")
    void validation_emptyWorkers() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("parentRunId", "run");
        input.put("workers", List.of());
        SkillResult result = tool.execute(input, new SkillContext("/tmp", "ps", 7L));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("workers");
    }

    @Test
    @DisplayName("Validation: worker missing agentId → validationError with index")
    void validation_workerMissingAgentId() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("parentRunId", "run");
        input.put("workers", List.of(Map.of("task", "t")));
        SkillResult result = tool.execute(input, new SkillContext("/tmp", "ps", 7L));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("workers[0]");
        assertThat(result.getError()).contains("agentId");
    }

    @Test
    @DisplayName("allSucceeded=false when any worker reported error")
    void mixedResults_allSucceededFalse() throws Exception {
        SessionEntity parent = new SessionEntity();
        parent.setId("ps");
        when(sessionService.getSession("ps")).thenReturn(parent);

        when(executor.awaitDispatch(any(), any(), any(), anyLong())).thenReturn(List.of(
                new StepResult("s1", "completed", null, null),
                new StepResult("s2", "error", null, "boom")));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("parentRunId", "r");
        input.put("workers", List.of(
                Map.of("agentId", 1, "task", "t"),
                Map.of("agentId", 2, "task", "t")));
        SkillResult result = tool.execute(input, new SkillContext("/tmp", "ps", 7L));

        assertThat(result.isSuccess()).isTrue();
        Map<?, ?> out = objectMapper.readValue(result.getOutput(), Map.class);
        assertThat(out.get("allSucceeded")).isEqualTo(false);
        List<?> stepResults = (List<?>) out.get("stepResults");
        assertThat(stepResults).hasSize(2);
        Map<?, ?> r2 = (Map<?, ?>) stepResults.get(1);
        assertThat(r2.get("errorReason")).isEqualTo("boom");
    }
}
