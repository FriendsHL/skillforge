package com.skillforge.server.flywheel.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.flywheel.run.FlywheelRunEntity;
import com.skillforge.server.flywheel.run.FlywheelRunService;
import com.skillforge.server.flywheel.run.FlywheelRunStepEntity;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.service.SessionService;
import com.skillforge.server.subagent.SubAgentRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OPT-LOOP-FRAMEWORK Sprint 2: pure-Java unit tests for
 * {@link OrchestratorAgentExecutor} covering Plan §5 IT cases 1, 2, 10, 13.
 *
 * <ul>
 *   <li>Case 1 — dispatchSubAgents 2-worker parallel + aggregate barrier</li>
 *   <li>Case 2 — single worker fail → mixed StepResult list</li>
 *   <li>Case 10 — completeStep idempotent guard (second call no-ops)</li>
 *   <li>Case 13 — Path B dispatch fills step.sub_agent_session_id correctly</li>
 * </ul>
 *
 * <p>Spring-aware AOP-proxy + WS-broadcast verification lives in
 * {@link com.skillforge.server.flywheel.run.FlywheelRunServiceStepCrudIT}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrchestratorAgentExecutor")
class OrchestratorAgentExecutorTest {

    @Mock private SubAgentRegistry subAgentRegistry;
    @Mock private SessionService sessionService;
    @Mock private ChatService chatService;
    @Mock private FlywheelRunService flywheelRunService;

    private OrchestratorAgentExecutor executor;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        // r1 W2 fix (java.md footgun #1).
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // r2 fix W-2: constructor no longer takes Clock / ApplicationEventPublisher
        // (both were dead — event publish lives in FlywheelRunService and
        // RecordOrchestrationStepResult Tool, not in the executor).
        executor = new OrchestratorAgentExecutor(
                subAgentRegistry, sessionService, chatService, flywheelRunService, objectMapper);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Case 1: dispatchSubAgents fans out N workers + barrier completes
    //         once each per-step future is satisfied via completeStep().
    // ─────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Case 1: dispatchSubAgents 2 workers — barrier completes when both completeStep()s land")
    void case1_dispatchSubAgents_twoWorkers_barrierCompletes() throws Exception {
        SessionEntity parent = parentSession();

        // Two distinct step UUIDs, returned in call order.
        String stepA = "step-A-" + UUID.randomUUID();
        String stepB = "step-B-" + UUID.randomUUID();
        when(flywheelRunService.appendStep(eq("run-1"), anyString())).thenReturn(stepA, stepB);

        // Path B happy path — registry + sessionService + chatService all succeed.
        SubAgentRegistry.SubAgentRun runA = makeRun("runA");
        SubAgentRegistry.SubAgentRun runB = makeRun("runB");
        when(subAgentRegistry.registerRun(eq(parent), eq(11L), anyString(), anyString()))
                .thenReturn(runA);
        when(subAgentRegistry.registerRun(eq(parent), eq(22L), anyString(), anyString()))
                .thenReturn(runB);
        SessionEntity childA = childSession("child-A");
        SessionEntity childB = childSession("child-B");
        when(sessionService.createSubSession(parent, 11L, runA.runId)).thenReturn(childA);
        when(sessionService.createSubSession(parent, 22L, runB.runId)).thenReturn(childB);

        OrchestratorWorkerSpec specA = new OrchestratorWorkerSpec(11L, "agentA", "task-A", "{\"k\":1}", 60);
        OrchestratorWorkerSpec specB = new OrchestratorWorkerSpec(22L, "agentB", "task-B", "{\"k\":2}", 60);

        CompletableFuture<List<StepResult>> agg = executor.dispatchSubAgents(
                "run-1", parent, List.of(specA, specB));

        // Barrier not done until both completeStep() arrive.
        assertThat(agg.isDone()).isFalse();
        assertThat(executor.pendingStepCount()).isEqualTo(2);

        // Step.sub_agent_session_id was attached for each worker (case 13).
        verify(flywheelRunService).attachStepSubAgentSession(stepA, "child-A");
        verify(flywheelRunService).attachStepSubAgentSession(stepB, "child-B");

        // Drive the futures via completeStep() — simulates WorkerCompletionListener.
        executor.completeStep(stepA, new StepResult(stepA, "completed", null, null));
        executor.completeStep(stepB, new StepResult(stepB, "completed", null, null));

        List<StepResult> results = agg.get(2, TimeUnit.SECONDS);
        assertThat(results).hasSize(2);
        assertThat(results).extracting(StepResult::stepRunId).containsExactly(stepA, stepB);
        assertThat(results).allMatch(StepResult::succeeded);
        assertThat(executor.pendingStepCount()).isZero();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Case 2: one worker errors → mixed result list, barrier still completes
    // ─────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Case 2: one worker fails — mixed StepResult list, barrier doesn't hang")
    void case2_oneWorkerFails_aggregateStillCompletes() throws Exception {
        SessionEntity parent = parentSession();
        String stepA = "step-A";
        String stepB = "step-B";
        when(flywheelRunService.appendStep(eq("run-2"), anyString())).thenReturn(stepA, stepB);
        when(subAgentRegistry.registerRun(any(), anyLong(), anyString(), anyString()))
                .thenReturn(makeRun("rA"), makeRun("rB"));
        when(sessionService.createSubSession(any(), anyLong(), anyString()))
                .thenReturn(childSession("cA"), childSession("cB"));

        OrchestratorWorkerSpec specA = new OrchestratorWorkerSpec(11L, "a", "task", "{}", 60);
        OrchestratorWorkerSpec specB = new OrchestratorWorkerSpec(22L, "b", "task", "{}", 60);
        CompletableFuture<List<StepResult>> agg = executor.dispatchSubAgents(
                "run-2", parent, List.of(specA, specB));

        executor.completeStep(stepA, new StepResult(stepA, "completed", null, null));
        executor.completeStep(stepB, new StepResult(stepB, "error", null, "boom"));

        List<StepResult> results = agg.get(2, TimeUnit.SECONDS);
        assertThat(results).hasSize(2);
        assertThat(results.get(0).succeeded()).isTrue();
        assertThat(results.get(1).succeeded()).isFalse();
        assertThat(results.get(1).errorReason()).isEqualTo("boom");
    }

    // ─────────────────────────────────────────────────────────────────────
    // Case 10: completeStep idempotent — race between Tool-path + fallback-path
    //          listeners can both fire; second call must no-op.
    // ─────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Case 10: completeStep idempotent — duplicate call after future done is silent no-op")
    void case10_completeStep_idempotent() throws Exception {
        SessionEntity parent = parentSession();
        when(flywheelRunService.appendStep(eq("run-10"), anyString())).thenReturn("step-id");
        when(subAgentRegistry.registerRun(any(), anyLong(), anyString(), anyString()))
                .thenReturn(makeRun("r"));
        when(sessionService.createSubSession(any(), anyLong(), anyString()))
                .thenReturn(childSession("c"));

        CompletableFuture<List<StepResult>> agg = executor.dispatchSubAgents(
                "run-10", parent,
                List.of(new OrchestratorWorkerSpec(11L, "a", "t", "{}", 60)));

        // First completion drives the future.
        executor.completeStep("step-id", new StepResult("step-id", "completed", null, null));
        List<StepResult> results = agg.get(2, TimeUnit.SECONDS);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).status()).isEqualTo("completed");

        // Second completion is silently dropped — pendingSteps map already empty.
        executor.completeStep("step-id", new StepResult("step-id", "error", null, "late"));
        // Future not re-completed (still has the first value)
        assertThat(agg.get().get(0).status()).isEqualTo("completed");
        assertThat(executor.pendingStepCount()).isZero();

        // Unknown stepRunId — also silent no-op
        executor.completeStep("unknown-step", new StepResult("unknown-step", "completed", null, null));
        // Won't throw, won't log error
    }

    // ─────────────────────────────────────────────────────────────────────
    // Case 13: Path B dispatch fills step.sub_agent_session_id correctly
    //         (Plan W-1 — verifies the framework doesn't depend on parsing
    //         SubAgentTool text output).
    // ─────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Case 13 (W-1): Path B dispatch backfills step.sub_agent_session_id with the createSubSession id")
    void case13_pathB_backfillsStepSubAgentSessionId() throws Exception {
        SessionEntity parent = parentSession();
        parent.setActiveRootTraceId("trace-root-abc");

        when(flywheelRunService.appendStep(eq("run-13"), anyString())).thenReturn("step-13");
        SubAgentRegistry.SubAgentRun run = makeRun("path-b-run");
        when(subAgentRegistry.registerRun(eq(parent), eq(11L), eq("agent"), eq("task")))
                .thenReturn(run);
        SessionEntity child = childSession("path-b-child");
        when(sessionService.createSubSession(parent, 11L, "path-b-run")).thenReturn(child);

        CompletableFuture<List<StepResult>> agg = executor.dispatchSubAgents(
                "run-13", parent,
                List.of(new OrchestratorWorkerSpec(11L, "agent", "task", "{}", 60)));

        // Activate root trace propagation onto the child.
        ArgumentCaptor<SessionEntity> savedChildCaptor = ArgumentCaptor.forClass(SessionEntity.class);
        verify(sessionService).saveSession(savedChildCaptor.capture());
        assertThat(savedChildCaptor.getValue().getActiveRootTraceId()).isEqualTo("trace-root-abc");

        // Reverse-link on registry — required by SubAgentRegistry semantics
        verify(subAgentRegistry).attachChildSession("path-b-run", "path-b-child");

        // Step row backfill — the W-1 critical fix
        verify(flywheelRunService).attachStepSubAgentSession("step-13", "path-b-child");

        // chatAsync was kicked with preserveActiveRoot=true (matches SubAgentTool.handleDispatch)
        verify(chatService).chatAsync(eq("path-b-child"), eq("task"), eq(parent.getUserId()), eq(true));

        // Cleanup — complete the future so the test doesn't dangle
        executor.completeStep("step-13", new StepResult("step-13", "completed", null, null));
        agg.get(1, TimeUnit.SECONDS);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Bonus: dispatch failure mid-fanout → step row marked error + future done
    // ─────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Bonus: dispatchOneWorker failure → step error written + future completes (depth limit case)")
    void bonus_dispatchFailure_marksStepError() throws Exception {
        SessionEntity parent = parentSession();
        when(flywheelRunService.appendStep(eq("run-x"), anyString())).thenReturn("step-x");
        when(subAgentRegistry.registerRun(any(), anyLong(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("depth limit reached"));

        CompletableFuture<List<StepResult>> agg = executor.dispatchSubAgents(
                "run-x", parent,
                List.of(new OrchestratorWorkerSpec(11L, "a", "t", "{}", 60)));

        List<StepResult> results = agg.get(2, TimeUnit.SECONDS);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).status()).isEqualTo("error");
        assertThat(results.get(0).errorReason()).contains("dispatch_failed");

        // The row write was attempted via transitionStepStatus
        verify(flywheelRunService).transitionStepStatus(
                eq("step-x"), eq("error"), any(), anyString());

        // chatAsync never fired — registry failed first
        verify(chatService, never()).chatAsync(anyString(), anyString(), anyLong(), eq(true));
    }

    // ─────────────────────────────────────────────────────────────────────
    // Case 8: awaitDispatch timeout → force-complete pending steps as error
    // ─────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Case 8: awaitDispatch timeout → pending step force-completed with status=error, errorReason='timeout'")
    void case8_timeout_forceErrorsPending() {
        SessionEntity parent = parentSession();
        when(flywheelRunService.appendStep(eq("run-8"), anyString())).thenReturn("step-8");
        when(subAgentRegistry.registerRun(any(), anyLong(), anyString(), anyString()))
                .thenReturn(makeRun("r8"));
        when(sessionService.createSubSession(any(), anyLong(), anyString()))
                .thenReturn(childSession("c8"));

        // listStepsByRunId returns the pending step so forceTimeoutPending finds it
        FlywheelRunStepEntity pendingStep = new FlywheelRunStepEntity();
        pendingStep.setId("step-8");
        pendingStep.setRunId("run-8");
        pendingStep.setStatus(FlywheelRunStepEntity.STATUS_PENDING);
        when(flywheelRunService.listStepsByRunId("run-8")).thenReturn(List.of(pendingStep));

        // Worker never calls back — awaitDispatch must hit timeout
        // (use 1 second to keep the test fast)
        List<StepResult> results = executor.awaitDispatch(
                "run-8", parent,
                List.of(new OrchestratorWorkerSpec(11L, "a", "t", "{}", 60)), 1L);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).status()).isEqualTo("error");
        assertThat(results.get(0).errorReason()).isEqualTo("timeout");

        // DB write was attempted
        verify(flywheelRunService).transitionStepStatus(
                eq("step-8"), eq(FlywheelRunStepEntity.STATUS_ERROR), eq(null), eq("timeout"));
    }

    // ─────────────────────────────────────────────────────────────────────
    // r2 B1 fix: run-level lifecycle — startRun delegates to FlywheelRunService
    // and finalizeRun maps the aggregate StepResult outcome to markCompleted/markError.
    // ─────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("B1: startRun delegates verbatim to FlywheelRunService.startRun (REQUIRES_NEW boundary)")
    void b1_startRun_delegatesToFlywheelRunService() {
        java.util.Map<String, Object> input = java.util.Map.of("agentId", 7L, "windowDays", 7);
        FlywheelRunEntity expected = new FlywheelRunEntity();
        expected.setId("run-startRun-1");
        when(flywheelRunService.startRun("opt_report", "user_manual", input, 7L, 7))
                .thenReturn(expected);

        FlywheelRunEntity actual = executor.startRun("opt_report", "user_manual", input, 7L, 7);

        assertThat(actual).isSameAs(expected);
        verify(flywheelRunService).startRun("opt_report", "user_manual", input, 7L, 7);
    }

    @Test
    @DisplayName("B1: finalizeRun — all StepResults succeeded → flywheelRunService.markCompleted called")
    void b1_finalizeRun_allSucceeded_marksCompleted() {
        List<StepResult> results = List.of(
                new StepResult("s1", "completed", null, null),
                new StepResult("s2", "completed", null, null));
        FlywheelRunEntity stubbed = new FlywheelRunEntity();
        stubbed.setId("run-final-ok");
        when(flywheelRunService.markCompleted("run-final-ok", null, null)).thenReturn(stubbed);

        FlywheelRunEntity res = executor.finalizeRun("run-final-ok", results);

        assertThat(res).isSameAs(stubbed);
        verify(flywheelRunService).markCompleted("run-final-ok", null, null);
        verify(flywheelRunService, never()).markError(any(), any());
    }

    @Test
    @DisplayName("B1: finalizeRun — any StepResult failed → flywheelRunService.markError with summarized reason")
    void b1_finalizeRun_oneFailed_marksError() {
        List<StepResult> results = List.of(
                new StepResult("s1", "completed", null, null),
                new StepResult("s2", "error", null, "downstream timeout"));
        FlywheelRunEntity stubbed = new FlywheelRunEntity();
        stubbed.setId("run-final-fail");
        when(flywheelRunService.markError(eq("run-final-fail"), anyString())).thenReturn(stubbed);

        FlywheelRunEntity res = executor.finalizeRun("run-final-fail", results);

        assertThat(res).isSameAs(stubbed);
        org.mockito.ArgumentCaptor<String> reasonCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(flywheelRunService).markError(eq("run-final-fail"), reasonCaptor.capture());
        assertThat(reasonCaptor.getValue()).contains("1/2 workers failed");
        assertThat(reasonCaptor.getValue()).contains("downstream timeout");
        verify(flywheelRunService, never()).markCompleted(any(), any(), any());
    }

    @Test
    @DisplayName("B1: finalizeRun — empty results list treated as failure (defensive)")
    void b1_finalizeRun_emptyResults_marksError() {
        FlywheelRunEntity stubbed = new FlywheelRunEntity();
        when(flywheelRunService.markError(eq("run-empty"), anyString())).thenReturn(stubbed);

        executor.finalizeRun("run-empty", List.of());

        verify(flywheelRunService).markError(eq("run-empty"), anyString());
    }

    @Test
    @DisplayName("Bonus: dispatchSubAgents validation — empty workers list throws")
    void bonus_validation_emptyWorkers() {
        SessionEntity parent = parentSession();
        assertThatThrownBy(() -> executor.dispatchSubAgents("run-v", parent, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workers");
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    private SessionEntity parentSession() {
        SessionEntity s = new SessionEntity();
        s.setId("parent-sess");
        s.setUserId(7L);
        s.setAgentId(100L);
        s.setDepth(0);
        return s;
    }

    private SessionEntity childSession(String id) {
        SessionEntity s = new SessionEntity();
        s.setId(id);
        s.setUserId(7L);
        s.setAgentId(11L);
        s.setDepth(1);
        return s;
    }

    private SubAgentRegistry.SubAgentRun makeRun(String runId) {
        SubAgentRegistry.SubAgentRun r = new SubAgentRegistry.SubAgentRun();
        r.runId = runId;
        return r;
    }
}
