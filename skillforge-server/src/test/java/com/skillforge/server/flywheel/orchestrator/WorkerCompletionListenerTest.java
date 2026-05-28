package com.skillforge.server.flywheel.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.server.flywheel.run.FlywheelRunService;
import com.skillforge.server.flywheel.run.FlywheelRunStepEntity;
import com.skillforge.server.flywheel.run.FlywheelRunStepRepository;
import com.skillforge.server.service.event.SessionLoopFinishedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OPT-LOOP-FRAMEWORK Sprint 2: unit tests for {@link WorkerCompletionListener}
 * covering Plan §5 IT cases 4, 9, 10 (idempotent part).
 *
 * <p>r2 fix W-4 (code-reviewer phantom IT): removed the stale Javadoc claim
 * that a {@code WorkerCompletionListenerIT} exists. {@code skillforge-server}
 * has no {@code @SpringBootTest} bootstrap today (see
 * {@code SkillImportIT}/{@code LlmTraceStoreFailDoesNotBlockChatIT} comments
 * for the matching note). AOP wiring + AFTER_COMMIT timing is instead
 * exercised:
 * <ul>
 *   <li>contract-level by {@link OrchestratorAopContractTest} (reflection
 *       asserts every annotation that drives Spring AOP behaviour),</li>
 *   <li>end-to-end by OPT-REPORT-V1 dogfood (production traffic) +
 *       Phase Final manual {@code mvn spring-boot:run} smoke that this
 *       Sprint's commit hook will gate on.</li>
 * </ul>
 * A future {@code @SpringBootTest} harness for the server module would
 * naturally absorb this verification (see attribution-curator /
 * eval-orchestrator tests' "BE-W1 follow-up" notes for the existing
 * cross-feature backlog item).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkerCompletionListener")
class WorkerCompletionListenerTest {

    @Mock private OrchestratorAgentExecutor executor;
    @Mock private FlywheelRunService flywheelRunService;
    @Mock private FlywheelRunStepRepository stepRepository;

    private WorkerCompletionListener listener;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        listener = new WorkerCompletionListener(executor, flywheelRunService, stepRepository, objectMapper);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Case 4: main-path event from RecordOrchestrationStepResult Tool →
    //         listener forwards to executor.completeStep
    // ─────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Case 4: onStepCompleted forwards Tool event to executor.completeStep with StepResult")
    void case4_onStepCompleted_callsExecutor() {
        JsonNode outputJson = objectMapper.valueToTree(java.util.Map.of("count", 5));
        OrchestrationStepCompletedEvent event = new OrchestrationStepCompletedEvent(
                "step-1", "completed", outputJson, null,
                OrchestrationStepCompletedEvent.TRIGGER_TOOL);

        listener.onStepCompleted(event);

        ArgumentCaptor<StepResult> resultCaptor = ArgumentCaptor.forClass(StepResult.class);
        verify(executor).completeStep(eq("step-1"), resultCaptor.capture());
        StepResult result = resultCaptor.getValue();
        assertThat(result.stepRunId()).isEqualTo("step-1");
        assertThat(result.status()).isEqualTo("completed");
        assertThat(result.outputJson().get("count").asInt()).isEqualTo(5);
        assertThat(result.errorReason()).isNull();
    }

    @Test
    @DisplayName("onStepCompleted swallows listener exception (defense-in-depth, doesn't bubble back into publishing tx)")
    void onStepCompleted_swallowsException() {
        org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(executor)
                .completeStep(any(), any());
        // Should not propagate.
        listener.onStepCompleted(new OrchestrationStepCompletedEvent(
                "step-x", "completed", null, null, OrchestrationStepCompletedEvent.TRIGGER_TOOL));
    }

    // ─────────────────────────────────────────────────────────────────────
    // Case 9: fallback path — worker didn't call the Tool, listener
    //         synthesizes StepResult from finalMessage
    // ─────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Case 9: fallback path — SessionLoopFinishedEvent → step found by sessionId, synthesized completed StepResult")
    void case9_fallback_completed_synthesizesResult() {
        FlywheelRunStepEntity pendingStep = pendingStep("step-9", "child-sess");
        when(stepRepository.findBySubAgentSessionIdAndStatus("child-sess", FlywheelRunStepEntity.STATUS_PENDING))
                .thenReturn(Optional.of(pendingStep));

        SessionLoopFinishedEvent event = new SessionLoopFinishedEvent(
                "child-sess", "all done!", "completed", 7L);
        listener.onSessionLoopFinishedFallback(event);

        // Row transition with synthesized outputJson
        ArgumentCaptor<JsonNode> outputCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(flywheelRunService).transitionStepStatus(
                eq("step-9"), eq("completed"), outputCaptor.capture(), eq((String) null));
        JsonNode synth = outputCaptor.getValue();
        assertThat(synth.get("triggerSource").asText()).isEqualTo("fallback");
        assertThat(synth.get("finalMessage").asText()).isEqualTo("all done!");
        assertThat(synth.get("finalStatus").asText()).isEqualTo("completed");

        // Executor woken
        verify(executor).completeStep(eq("step-9"), any(StepResult.class));
    }

    @Test
    @DisplayName("Case 9b: fallback path — worker errored → step error with synthesized reason")
    void case9b_fallback_error_synthesizesReason() {
        FlywheelRunStepEntity pendingStep = pendingStep("step-9b", "child-err");
        when(stepRepository.findBySubAgentSessionIdAndStatus("child-err", FlywheelRunStepEntity.STATUS_PENDING))
                .thenReturn(Optional.of(pendingStep));

        SessionLoopFinishedEvent event = new SessionLoopFinishedEvent(
                "child-err", "(no final message)", "error", 7L);
        listener.onSessionLoopFinishedFallback(event);

        verify(flywheelRunService).transitionStepStatus(
                eq("step-9b"), eq("error"), any(JsonNode.class),
                eq("fallback: worker terminated with status=error"));
        verify(executor).completeStep(eq("step-9b"), any(StepResult.class));
    }

    @Test
    @DisplayName("Case 9c: fallback path — no pending step (worker session unrelated) → no-op")
    void case9c_fallback_noMatchingStep_noOp() {
        when(stepRepository.findBySubAgentSessionIdAndStatus("unrelated-sess", FlywheelRunStepEntity.STATUS_PENDING))
                .thenReturn(Optional.empty());

        listener.onSessionLoopFinishedFallback(new SessionLoopFinishedEvent(
                "unrelated-sess", "msg", "completed", 7L));

        verify(flywheelRunService, never()).transitionStepStatus(any(), any(), any(), any());
        verify(executor, never()).completeStep(any(), any());
    }

    @Test
    @DisplayName("Case 9d: fallback path — waiting_user terminal status skipped (step stays pending)")
    void case9d_fallback_waitingUser_skipped() {
        // No stub needed — the listener early-returns before hitting the repository.
        listener.onSessionLoopFinishedFallback(new SessionLoopFinishedEvent(
                "child-sess", "asking user", "waiting_user", 7L));

        verify(stepRepository, never()).findBySubAgentSessionIdAndStatus(any(), any());
        verify(flywheelRunService, never()).transitionStepStatus(any(), any(), any(), any());
        verify(executor, never()).completeStep(any(), any());
    }

    @Test
    @DisplayName("Case 10b: fallback handles race-y disallowed transition — Tool path won, listener silently no-ops")
    void case10b_fallback_idempotentOnDisallowedTransition() {
        FlywheelRunStepEntity pendingStep = pendingStep("step-r", "race-sess");
        when(stepRepository.findBySubAgentSessionIdAndStatus("race-sess", FlywheelRunStepEntity.STATUS_PENDING))
                .thenReturn(Optional.of(pendingStep));
        // Simulate: row already transitioned by Tool path before fallback reaches it
        org.mockito.Mockito.doThrow(new IllegalStateException("Disallowed step transition: completed → completed"))
                .when(flywheelRunService).transitionStepStatus(eq("step-r"), any(), any(), any());

        listener.onSessionLoopFinishedFallback(new SessionLoopFinishedEvent(
                "race-sess", "late", "completed", 7L));

        // executor.completeStep was NOT called — fallback handler bailed gracefully
        verify(executor, never()).completeStep(any(), any());
    }

    private static FlywheelRunStepEntity pendingStep(String id, String childSessionId) {
        FlywheelRunStepEntity s = new FlywheelRunStepEntity();
        s.setId(id);
        s.setRunId("parent-run");
        s.setStatus(FlywheelRunStepEntity.STATUS_PENDING);
        s.setSubAgentSessionId(childSessionId);
        s.setStepInputJson("{}");
        s.setStepKind(FlywheelRunStepEntity.STEP_KIND_SUBAGENT_DISPATCH);
        return s;
    }
}
