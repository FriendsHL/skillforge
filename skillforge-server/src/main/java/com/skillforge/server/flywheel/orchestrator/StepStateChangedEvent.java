package com.skillforge.server.flywheel.orchestrator;

/**
 * OPT-LOOP-FRAMEWORK Sprint 2: Spring event fired by
 * {@code FlywheelRunService.transitionStepStatus} after a step row's status
 * column commits.
 *
 * <p>Consumed by {@link FlywheelStepWsBroadcaster}
 * ({@code @TransactionalEventListener(AFTER_COMMIT, fallbackExecution=true)
 * @Async}) which fans out a {@code flywheel_step_state_changed} WS payload to
 * every connected dashboard client. The two-stage publish/broadcast keeps WS
 * I/O off the @Transactional method's hot path (Plan §2 Design 5 Rule 5;
 * also fixes Sprint 1 W5 backlog where the run-level WS broadcast was
 * synchronous inside the tx).
 *
 * @param stepRunId    {@code FlywheelRunStepEntity#getId()}
 * @param runId        parent {@code FlywheelRunEntity#getId()}
 * @param oldStatus    status the row had before the transition
 * @param newStatus    status the row has after the commit
 * @param errorReason  populated when {@code newStatus=error}; null otherwise
 */
public record StepStateChangedEvent(
        String stepRunId,
        String runId,
        String oldStatus,
        String newStatus,
        String errorReason
) {
}
