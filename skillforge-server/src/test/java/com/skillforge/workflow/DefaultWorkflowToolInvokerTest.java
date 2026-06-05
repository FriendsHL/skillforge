package com.skillforge.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.flywheel.run.FlywheelRunService;
import com.skillforge.server.flywheel.run.FlywheelRunStepEntity;
import com.skillforge.workflow.engine.WorkflowEvolveToolRegistryFactory;
import com.skillforge.workflow.exception.WorkflowToolException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AUTOEVOLVE-CLOSE-LOOP P1 — {@link DefaultWorkflowToolInvoker} step-lifecycle unit
 * tests: a tool_call step is appended pending and transitioned completed (with the
 * parsed result) on success, error on failure / unknown tool.
 */
class DefaultWorkflowToolInvokerTest {

    private final ObjectMapper om = new ObjectMapper();
    private FlywheelRunService flywheel;
    private WorkflowEvolveToolRegistryFactory registryFactory;
    private SkillRegistry registry;

    @BeforeEach
    void setUp() {
        flywheel = mock(FlywheelRunService.class);
        registryFactory = mock(WorkflowEvolveToolRegistryFactory.class);
        registry = new SkillRegistry();
        when(registryFactory.workflowEvolveToolRegistry()).thenReturn(registry);
        when(flywheel.appendStep(any(), any(), eq(FlywheelRunStepEntity.STEP_KIND_TOOL_CALL), any()))
                .thenReturn("step-1");
    }

    private DefaultWorkflowToolInvoker invoker() {
        return new DefaultWorkflowToolInvoker(flywheel, registryFactory, om, "run-1", 0L);
    }

    @Test
    @DisplayName("success: appends tool_call step (pending) → completed(result, durationMs), returns parsed Map")
    void successPath() {
        registry.registerTool(fakeTool("FakeTool", SkillResult.success("{\"k\":\"v\",\"n\":3}")));

        Object out = invoker().invoke("FakeTool", Map.of("a", 1), 7);

        assertThat(out).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) out;
        assertThat(m).containsEntry("k", "v");
        assertThat(((Number) m.get("n")).intValue()).isEqualTo(3);

        verify(flywheel).appendStep(eq("run-1"), any(), eq(FlywheelRunStepEntity.STEP_KIND_TOOL_CALL), eq(7));
        ArgumentCaptor<JsonNode> outCap = ArgumentCaptor.forClass(JsonNode.class);
        verify(flywheel).transitionStepStatus(
                eq("step-1"), eq(FlywheelRunStepEntity.STATUS_COMPLETED), outCap.capture(), eq(null));
        JsonNode stored = outCap.getValue();
        assertThat(stored.path("toolName").asText()).isEqualTo("FakeTool");
        assertThat(stored.path("result").path("k").asText()).isEqualTo("v");
        assertThat(stored.has("durationMs")).isTrue();
    }

    @Test
    @DisplayName("non-success result: step → error, throws WorkflowToolException")
    void failurePath() {
        registry.registerTool(fakeTool("FailTool", SkillResult.error("boom")));

        assertThatThrownBy(() -> invoker().invoke("FailTool", Map.of(), 2))
                .isInstanceOf(WorkflowToolException.class)
                .hasMessageContaining("boom");

        verify(flywheel).transitionStepStatus(
                eq("step-1"), eq(FlywheelRunStepEntity.STATUS_ERROR), eq(null), eq("boom"));
    }

    @Test
    @DisplayName("unknown tool: throws WorkflowToolException, no step appended")
    void unknownTool() {
        assertThatThrownBy(() -> invoker().invoke("Nope", Map.of(), 0))
                .isInstanceOf(WorkflowToolException.class)
                .hasMessageContaining("not a whitelisted workflow tool");

        verify(flywheel, never()).appendStep(any(), any(), any(), any());
    }

    private static Tool fakeTool(String name, SkillResult result) {
        return new Tool() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return name; }
            @Override public ToolSchema getToolSchema() {
                return new ToolSchema(name, name, Map.of("type", "object"));
            }
            @Override public SkillResult execute(Map<String, Object> input, SkillContext context) {
                return result;
            }
        };
    }
}
