package com.skillforge.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.AgentLoopEngine;
import com.skillforge.core.engine.LoopContext;
import com.skillforge.core.engine.LoopResult;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.flywheel.run.FlywheelRunService;
import com.skillforge.server.flywheel.run.FlywheelRunStepEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.service.AgentService;
import com.skillforge.server.service.SessionService;
import com.skillforge.workflow.engine.WorkflowSubAgentEngineFactory;
import com.skillforge.workflow.exception.SchemaViolationException;
import com.skillforge.workflow.exception.WorkflowAgentNotFoundException;
import com.skillforge.workflow.schema.SchemaValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task D — {@link DefaultWorkflowAgentInvoker} synchronous engine.run path
 * (plan §5.1). Engine + repos mocked; verifies slug resolution, step
 * registration, and output persistence.
 */
@ExtendWith(MockitoExtension.class)
class DefaultWorkflowAgentInvokerTest {

    @Mock private AgentRepository agentRepository;
    @Mock private AgentService agentService;
    @Mock private SessionService sessionService;
    @Mock private FlywheelRunService flywheelRunService;
    @Mock private WorkflowSubAgentEngineFactory engineFactory;
    @Mock private AgentLoopEngine engine;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DefaultWorkflowAgentInvoker invoker;

    @BeforeEach
    void setUp() {
        SessionEntity anchor = new SessionEntity();
        anchor.setId("anchor-1");
        invoker = new DefaultWorkflowAgentInvoker(agentRepository, agentService, sessionService,
                flywheelRunService, engineFactory, objectMapper, new SchemaValidator(),
                "run-1", anchor, 7L);
    }

    @Test
    @DisplayName("resolves slug → engine.run → returns finalResponse + persists completed step")
    void invokeHappyPath() {
        AgentEntity entity = new AgentEntity();
        entity.setId(42L);
        entity.setName("worker");
        when(agentRepository.findFirstByName("worker")).thenReturn(Optional.of(entity));

        AgentDefinition def = new AgentDefinition();
        def.setName("worker");
        when(agentService.toAgentDefinition(entity)).thenReturn(def);

        SessionEntity sub = new SessionEntity();
        sub.setId("sub-9");
        when(sessionService.createSubSession(any(), eq(42L), eq("run-1"))).thenReturn(sub);

        when(flywheelRunService.appendStep(eq("run-1"), any(), any(), any())).thenReturn("step-3");
        when(engineFactory.buildWorkflowEngine(any(SkillRegistry.class))).thenReturn(engine);

        LoopResult result = new LoopResult();
        result.setFinalResponse("hi-there");
        result.setLoopCount(2);
        when(engine.run(eq(def), eq("say hi"), eq(null), eq("sub-9"), eq(7L), any(LoopContext.class)))
                .thenReturn(result);

        Object out = invoker.invoke("say hi", Map.of("agentSlug", "worker"), 0);

        assertThat(out).isEqualTo("hi-there");
        verify(flywheelRunService).attachStepSubAgentSession("step-3", "sub-9");

        ArgumentCaptor<JsonNode> outCap = ArgumentCaptor.forClass(JsonNode.class);
        verify(flywheelRunService).transitionStepStatus(
                eq("step-3"), eq(FlywheelRunStepEntity.STATUS_COMPLETED), outCap.capture(), eq(null));
        assertThat(outCap.getValue().get("finalResponse").asText()).isEqualTo("hi-there");
        assertThat(outCap.getValue().get("loopCount").asInt()).isEqualTo(2);
        assertThat(outCap.getValue().get("subSessionId").asText()).isEqualTo("sub-9");
    }

    @Test
    @DisplayName("missing agentSlug throws WorkflowAgentNotFoundException")
    void missingSlugThrows() {
        assertThatThrownBy(() -> invoker.invoke("p", Map.of(), 0))
                .isInstanceOf(WorkflowAgentNotFoundException.class);
    }

    @Test
    @DisplayName("unknown agentSlug throws WorkflowAgentNotFoundException")
    void unknownSlugThrows() {
        when(agentRepository.findFirstByName("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> invoker.invoke("p", Map.of("agentSlug", "ghost"), 0))
                .isInstanceOf(WorkflowAgentNotFoundException.class);
    }

    @Test
    @DisplayName("engine.run failure marks step error and rethrows")
    void engineFailureMarksError() {
        AgentEntity entity = new AgentEntity();
        entity.setId(42L);
        entity.setName("worker");
        when(agentRepository.findFirstByName("worker")).thenReturn(Optional.of(entity));
        when(agentService.toAgentDefinition(entity)).thenReturn(new AgentDefinition());
        SessionEntity sub = new SessionEntity();
        sub.setId("sub-9");
        when(sessionService.createSubSession(any(), eq(42L), eq("run-1"))).thenReturn(sub);
        when(flywheelRunService.appendStep(eq("run-1"), any(), any(), any())).thenReturn("step-3");
        when(engineFactory.buildWorkflowEngine(any(SkillRegistry.class))).thenReturn(engine);
        when(engine.run(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("boom"));

        assertThatThrownBy(() -> invoker.invoke("say hi", Map.of("agentSlug", "worker"), 0))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("boom");

        verify(flywheelRunService).transitionStepStatus(
                eq("step-3"), eq(FlywheelRunStepEntity.STATUS_ERROR), eq(null), eq("boom"));
    }

    // ── Task E: schema enforcement + retry (3 total attempts, W2) ──

    /** Schema requiring an object with a required string field {@code x}. */
    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "required", List.of("x"),
            "properties", Map.of("x", Map.of("type", "string")));

    private void stubWorker(AgentDefinition def) {
        AgentEntity entity = new AgentEntity();
        entity.setId(42L);
        entity.setName("worker");
        when(agentRepository.findFirstByName("worker")).thenReturn(Optional.of(entity));
        when(agentService.toAgentDefinition(entity)).thenReturn(def);
        SessionEntity sub = new SessionEntity();
        sub.setId("sub-9");
        when(sessionService.createSubSession(any(), eq(42L), eq("run-1"))).thenReturn(sub);
        when(flywheelRunService.appendStep(eq("run-1"), any(), any(), any())).thenReturn("step-3");
        when(engineFactory.buildWorkflowEngine(any(SkillRegistry.class))).thenReturn(engine);
    }

    private static LoopResult resp(String finalResponse) {
        LoopResult r = new LoopResult();
        r.setFinalResponse(finalResponse);
        r.setLoopCount(1);
        return r;
    }

    @Test
    @DisplayName("schema valid on first attempt → returns parsed Map, engine.run once")
    void schemaValidFirstAttempt() {
        stubWorker(new AgentDefinition());
        when(engine.run(any(), any(), any(), any(), any(), any())).thenReturn(resp("{\"x\":\"hi\"}"));

        Object out = invoker.invoke("say hi", Map.of("agentSlug", "worker", "schema", SCHEMA), 0);

        assertThat(out).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) out).get("x")).isEqualTo("hi");
        verify(engine, times(1)).run(any(), any(), any(), any(), any(), any());
        verify(flywheelRunService).transitionStepStatus(
                eq("step-3"), eq(FlywheelRunStepEntity.STATUS_COMPLETED), any(), eq(null));
    }

    @Test
    @DisplayName("schema invalid then valid → retries once, returns parsed Map")
    void schemaRetryThenPass() {
        stubWorker(new AgentDefinition());
        when(engine.run(any(), any(), any(), any(), any(), any()))
                .thenReturn(resp("{\"y\":1}"), resp("{\"x\":\"ok\"}"));

        Object out = invoker.invoke("say hi", Map.of("agentSlug", "worker", "schema", SCHEMA), 0);

        assertThat(((Map<?, ?>) out).get("x")).isEqualTo("ok");
        verify(engine, times(2)).run(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("schema violated on all 3 attempts → SchemaViolationException + step error")
    void schemaAllAttemptsFail() {
        stubWorker(new AgentDefinition());
        when(engine.run(any(), any(), any(), any(), any(), any())).thenReturn(resp("{\"y\":1}"));

        assertThatThrownBy(() ->
                invoker.invoke("say hi", Map.of("agentSlug", "worker", "schema", SCHEMA), 5))
                .isInstanceOf(SchemaViolationException.class);

        verify(engine, times(3)).run(any(), any(), any(), any(), any(), any());
        verify(flywheelRunService).transitionStepStatus(
                eq("step-3"), eq(FlywheelRunStepEntity.STATUS_ERROR), eq(null), any());
    }

    @Test
    @DisplayName("non-JSON output counts as a schema violation")
    void schemaParseFailureIsViolation() {
        stubWorker(new AgentDefinition());
        when(engine.run(any(), any(), any(), any(), any(), any())).thenReturn(resp("not json at all"));

        assertThatThrownBy(() ->
                invoker.invoke("say hi", Map.of("agentSlug", "worker", "schema", SCHEMA), 0))
                .isInstanceOf(SchemaViolationException.class);
        verify(engine, times(3)).run(any(), any(), any(), any(), any(), any());
    }
}
