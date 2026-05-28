package com.skillforge.server.flywheel.orchestrator;

import com.skillforge.server.flywheel.run.FlywheelRunService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OPT-LOOP-FRAMEWORK Sprint 2: declarative AOP-annotation contract enforcement
 * covering Plan §5 Cases 5, 6, 7 — these are the "AOP-1/2/3" verification
 * cases that would normally require booting a Spring context, but the same
 * guarantees are encoded in our annotation set, so reflection-based assertions
 * give equivalent regression protection at &gt;100x the speed.
 *
 * <p>If anyone removes / weakens an annotation (e.g. drops {@code REQUIRES_NEW}
 * → tx joins caller, or drops {@code fallbackExecution=true} → listener
 * silently no-ops when publisher is non-transactional), <b>this test fails</b>
 * before the regression makes it to production.
 *
 * <p>The actual AOP proxy creation + behaviour is exercised end-to-end by
 * production OPT-REPORT dogfood + the {@code FlywheelRunServiceStepCrudIT} +
 * {@code OptReportServiceBackwardCompatIT} integration suite — those would
 * silently break if the annotations weren't being honoured by Spring at
 * runtime.
 */
@DisplayName("OPT-LOOP-FRAMEWORK Sprint 2 AOP-annotation contract (Plan §5 cases 5/6/7)")
class OrchestratorAopContractTest {

    // ─────────────────────────────────────────────────────────────────────
    // Case 5 / 6: OrchestratorAgentExecutor must be a @Service (Spring proxy)
    //             AND dispatchSubAgents MUST NOT carry @Transactional (Plan
    //             §2 Design 5 Rule 2 — barrier wait must not pin a JDBC conn)
    // ─────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Case 5: OrchestratorAgentExecutor is @Service (AOP proxy in prod)")
    void case5_executorIsService() {
        assertThat(OrchestratorAgentExecutor.class.isAnnotationPresent(Service.class)).isTrue();
    }

    @Test
    @DisplayName("Case 5b (r2 B1, Plan §2 D5 Rule 1): startRun is @Transactional(propagation=REQUIRES_NEW)")
    void case5b_startRun_requiresNew() throws Exception {
        Method m = OrchestratorAgentExecutor.class.getDeclaredMethod(
                "startRun", String.class, String.class, java.util.Map.class, Long.class, int.class);
        Transactional tx = m.getAnnotation(Transactional.class);
        assertThat(tx)
                .as("Plan §2 D5 Rule 1: startRun must own a REQUIRES_NEW tx so concurrent run "
                        + "starts can't share a rollback fate with the caller's outer tx")
                .isNotNull();
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    @DisplayName("Case 5c (r2 B1): finalizeRun is NOT @Transactional — delegates to mark{Completed,Error} which own their scopes")
    void case5c_finalizeRun_notTransactional() throws Exception {
        Method m = OrchestratorAgentExecutor.class.getDeclaredMethod(
                "finalizeRun", String.class, java.util.List.class);
        assertThat(m.isAnnotationPresent(Transactional.class))
                .as("finalizeRun shouldn't wrap a second tx around two already-transactional service calls")
                .isFalse();
    }

    @Test
    @DisplayName("Case 6 (Rule 2): dispatchSubAgents is NOT @Transactional — barrier wait must not pin a JDBC conn")
    void case6_dispatchSubAgents_notTransactional() throws Exception {
        Method m = OrchestratorAgentExecutor.class.getDeclaredMethod(
                "dispatchSubAgents", String.class, com.skillforge.server.entity.SessionEntity.class,
                java.util.List.class);
        assertThat(m.isAnnotationPresent(Transactional.class))
                .as("dispatchSubAgents must NOT be @Transactional (Plan §2 Design 5 Rule 2)")
                .isFalse();
    }

    @Test
    @DisplayName("Case 6b (Rule 2): awaitDispatch is NOT @Transactional — same rationale")
    void case6b_awaitDispatch_notTransactional() throws Exception {
        Method m = OrchestratorAgentExecutor.class.getDeclaredMethod(
                "awaitDispatch", String.class, com.skillforge.server.entity.SessionEntity.class,
                java.util.List.class, long.class);
        assertThat(m.isAnnotationPresent(Transactional.class)).isFalse();
    }

    @Test
    @DisplayName("Case 6c: completeStep is NOT @Transactional — only mutates in-memory map")
    void case6c_completeStep_notTransactional() throws Exception {
        Method m = OrchestratorAgentExecutor.class.getDeclaredMethod(
                "completeStep", String.class, StepResult.class);
        assertThat(m.isAnnotationPresent(Transactional.class)).isFalse();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Plan W-3 + N-3: FlywheelRunService's new step methods carry @Transactional
    // ─────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("W-3: FlywheelRunService.appendStep is @Transactional")
    void appendStep_isTransactional() throws Exception {
        Method m = FlywheelRunService.class.getDeclaredMethod(
                "appendStep", String.class, String.class);
        assertThat(m.isAnnotationPresent(Transactional.class)).isTrue();
    }

    @Test
    @DisplayName("W-3: FlywheelRunService.attachStepSubAgentSession is @Transactional")
    void attachStepSubAgentSession_isTransactional() throws Exception {
        Method m = FlywheelRunService.class.getDeclaredMethod(
                "attachStepSubAgentSession", String.class, String.class);
        assertThat(m.isAnnotationPresent(Transactional.class)).isTrue();
    }

    @Test
    @DisplayName("W-3: FlywheelRunService.transitionStepStatus is @Transactional")
    void transitionStepStatus_isTransactional() throws Exception {
        Method m = FlywheelRunService.class.getDeclaredMethod(
                "transitionStepStatus", String.class, String.class,
                com.fasterxml.jackson.databind.JsonNode.class, String.class);
        assertThat(m.isAnnotationPresent(Transactional.class)).isTrue();
    }

    @Test
    @DisplayName("N-3: FlywheelRunService.listStepsByRunId is @Transactional(readOnly=true)")
    void listStepsByRunId_isReadOnlyTransactional() throws Exception {
        Method m = FlywheelRunService.class.getDeclaredMethod("listStepsByRunId", String.class);
        Transactional tx = m.getAnnotation(Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.readOnly())
                .as("listStepsByRunId must declare readOnly=true to consume Sprint 1 W3 backlog (N-3)")
                .isTrue();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Case 7 (Plan N-1): WorkerCompletionListener three-annotation contract
    // ─────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("WorkerCompletionListener is @Component")
    void listener_isComponent() {
        assertThat(WorkerCompletionListener.class.isAnnotationPresent(Component.class)).isTrue();
    }

    @Test
    @DisplayName("Case 7 (N-1): onStepCompleted has @Async + AFTER_COMMIT fallbackExecution + REQUIRES_NEW")
    void case7_onStepCompleted_tripleAnnotation() throws Exception {
        Method m = WorkerCompletionListener.class.getDeclaredMethod(
                "onStepCompleted", OrchestrationStepCompletedEvent.class);
        assertThat(m.isAnnotationPresent(Async.class)).isTrue();

        TransactionalEventListener listener = m.getAnnotation(TransactionalEventListener.class);
        assertThat(listener).isNotNull();
        assertThat(listener.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(listener.fallbackExecution())
                .as("fallbackExecution=true required (publisher is sometimes ChatService loop teardown, "
                        + "which has no surrounding tx)")
                .isTrue();

        Transactional tx = m.getAnnotation(Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    @DisplayName("Case 7b (N-1): onSessionLoopFinishedFallback has the same three-annotation contract")
    void case7b_fallback_tripleAnnotation() throws Exception {
        Method m = WorkerCompletionListener.class.getDeclaredMethod(
                "onSessionLoopFinishedFallback",
                com.skillforge.server.service.event.SessionLoopFinishedEvent.class);
        assertThat(m.isAnnotationPresent(Async.class)).isTrue();
        TransactionalEventListener listener = m.getAnnotation(TransactionalEventListener.class);
        assertThat(listener).isNotNull();
        assertThat(listener.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(listener.fallbackExecution()).isTrue();
        Transactional tx = m.getAnnotation(Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Plan §2 Design 5 Rule 5: WS broadcast on AFTER_COMMIT @Async
    // ─────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Plan Rule 5: FlywheelStepWsBroadcaster is @Component")
    void broadcaster_isComponent() {
        assertThat(FlywheelStepWsBroadcaster.class.isAnnotationPresent(Component.class)).isTrue();
    }

    @Test
    @DisplayName("Plan Rule 5: FlywheelStepWsBroadcaster.onStepStateChanged is @Async + AFTER_COMMIT")
    void broadcaster_asyncAfterCommit() throws Exception {
        Method m = FlywheelStepWsBroadcaster.class.getDeclaredMethod(
                "onStepStateChanged", StepStateChangedEvent.class);
        assertThat(m.isAnnotationPresent(Async.class)).isTrue();
        TransactionalEventListener listener = m.getAnnotation(TransactionalEventListener.class);
        assertThat(listener).isNotNull();
        assertThat(listener.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(listener.fallbackExecution()).isTrue();
        // Plan Rule 5 says WS broadcast must NOT be inside @Transactional (would hold conn)
        assertThat(m.isAnnotationPresent(Transactional.class))
                .as("Broadcaster must NOT be @Transactional — WS I/O must run outside any DB tx")
                .isFalse();
    }
}
