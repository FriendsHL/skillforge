package com.skillforge.server.tool.flywheel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.flywheel.orchestrator.OrchestrationStepCompletedEvent;
import com.skillforge.server.flywheel.run.FlywheelRunService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * OPT-LOOP-FRAMEWORK Sprint 2: unit tests for the worker-side Tool.
 * Plan §5 IT case 4 (happy path → step_output_json + OrchestrationStepCompletedEvent).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RecordOrchestrationStepResult Tool")
class RecordOrchestrationStepResultTest {

    @Mock private FlywheelRunService flywheelRunService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;

    private ObjectMapper objectMapper;
    private RecordOrchestrationStepResult tool;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        tool = new RecordOrchestrationStepResult(flywheelRunService, applicationEventPublisher, objectMapper);
    }

    @Test
    @DisplayName("Case 4: happy path — transitionStepStatus writes outputJson + OrchestrationStepCompletedEvent fired")
    void case4_happyPath_writesAndFiresEvent() throws Exception {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("stepRunId", "step-4");
        input.put("status", "completed");
        input.put("outputJson", Map.of("count", 5, "ids", java.util.List.of("a", "b")));

        SkillResult result = tool.execute(input, new SkillContext("/tmp", "worker-sess", 7L));

        assertThat(result.isSuccess()).isTrue();

        // 1. Row write — outputJson made it through
        ArgumentCaptor<JsonNode> outputCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(flywheelRunService).transitionStepStatus(
                eq("step-4"), eq("completed"), outputCaptor.capture(), eq((String) null));
        assertThat(outputCaptor.getValue().get("count").asInt()).isEqualTo(5);
        assertThat(outputCaptor.getValue().get("ids").size()).isEqualTo(2);

        // 2. OrchestrationStepCompletedEvent published with trigger=tool
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(applicationEventPublisher).publishEvent(eventCaptor.capture());
        Object evt = eventCaptor.getValue();
        assertThat(evt).isInstanceOf(OrchestrationStepCompletedEvent.class);
        OrchestrationStepCompletedEvent typed = (OrchestrationStepCompletedEvent) evt;
        assertThat(typed.stepRunId()).isEqualTo("step-4");
        assertThat(typed.status()).isEqualTo("completed");
        assertThat(typed.triggerSource()).isEqualTo("tool");

        // 3. Tool output payload
        Map<?, ?> out = objectMapper.readValue(result.getOutput(), Map.class);
        assertThat(out.get("ok")).isEqualTo(true);
        assertThat(out.get("stepRunId")).isEqualTo("step-4");
        assertThat(out.get("status")).isEqualTo("completed");
    }

    @Test
    @DisplayName("Error status requires errorReason — happy path writes it")
    void errorStatus_writesReason() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("stepRunId", "step-err");
        input.put("status", "error");
        input.put("errorReason", "downstream timeout");

        SkillResult result = tool.execute(input, new SkillContext("/tmp", "ws", 7L));
        assertThat(result.isSuccess()).isTrue();

        verify(flywheelRunService).transitionStepStatus(
                eq("step-err"), eq("error"), any(), eq("downstream timeout"));
    }

    @Test
    @DisplayName("Validation: missing stepRunId → validationError")
    void validation_missingStepRunId() {
        SkillResult result = tool.execute(Map.of("status", "completed"), new SkillContext("/tmp", "ws", 7L));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("stepRunId");
        verify(flywheelRunService, never()).transitionStepStatus(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Validation: illegal status enum → validationError")
    void validation_illegalStatus() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("stepRunId", "step-x");
        input.put("status", "running");
        SkillResult result = tool.execute(input, new SkillContext("/tmp", "ws", 7L));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("status");
    }

    @Test
    @DisplayName("Default status — missing 'status' input falls back to 'completed' (LLM grace)")
    void status_defaultsToCompleted() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("stepRunId", "step-d");
        // no status passed

        SkillResult result = tool.execute(input, new SkillContext("/tmp", "ws", 7L));

        assertThat(result.isSuccess()).isTrue();
        verify(flywheelRunService).transitionStepStatus(
                eq("step-d"), eq("completed"), any(), eq((String) null));
    }

    @Test
    @DisplayName("Tool is idempotent on disallowed transition — returns ok but row write was no-op")
    void idempotent_disallowedTransition() throws Exception {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("stepRunId", "step-i");
        input.put("status", "completed");
        input.put("outputJson", Map.of());
        org.mockito.Mockito.doThrow(new IllegalStateException(
                "Disallowed step transition: completed → completed"))
                .when(flywheelRunService).transitionStepStatus(eq("step-i"), eq("completed"), any(), any());

        SkillResult result = tool.execute(input, new SkillContext("/tmp", "ws", 7L));

        // Tool still returns ok (so the worker loop can terminate cleanly);
        // event still fires (listener will no-op via executor's pendingSteps map).
        assertThat(result.isSuccess()).isTrue();
        verify(applicationEventPublisher).publishEvent(any(OrchestrationStepCompletedEvent.class));
    }
}
