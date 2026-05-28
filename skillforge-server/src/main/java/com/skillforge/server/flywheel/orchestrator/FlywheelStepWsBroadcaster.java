package com.skillforge.server.flywheel.orchestrator;

import com.skillforge.server.websocket.UserWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OPT-LOOP-FRAMEWORK Sprint 2: WS push for per-step state changes.
 *
 * <p>Separated from {@code FlywheelRunService} on purpose (Plan §2 Design 5
 * Rule 5): a synchronous broadcast inside the @Transactional service method
 * would block the JDBC connection while WS I/O happens, exactly the W5
 * footgun Sprint 1's run-level broadcast left open. By going through
 * {@link StepStateChangedEvent} + AFTER_COMMIT @Async this listener:
 * <ul>
 *   <li>fires only after the row mutation has actually committed to disk,
 *       so a transaction rollback won't leak a "completed" notification for a
 *       row that didn't make it,</li>
 *   <li>runs on Spring's @Async pool, so a slow WS broadcast can't extend the
 *       transactional method's wall-clock.</li>
 * </ul>
 *
 * <p>{@code fallbackExecution=true} matches the pattern used for the
 * matching step-completion listener — the publisher
 * (FlywheelRunService.transitionStepStatus) wraps the publish in a try/catch
 * but the listener path itself must still tolerate being invoked outside a
 * surrounding tx (e.g. when called from non-transactional helpers).
 */
@Component
public class FlywheelStepWsBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(FlywheelStepWsBroadcaster.class);

    private final UserWebSocketHandler userWebSocketHandler;

    public FlywheelStepWsBroadcaster(UserWebSocketHandler userWebSocketHandler) {
        this.userWebSocketHandler = userWebSocketHandler;
    }

    @Async
    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true)
    public void onStepStateChanged(StepStateChangedEvent event) {
        if (event == null || event.stepRunId() == null) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "flywheel_step_state_changed");
        payload.put("stepRunId", event.stepRunId());
        payload.put("runId", event.runId());
        payload.put("oldStatus", event.oldStatus());
        payload.put("newStatus", event.newStatus());
        if (event.errorReason() != null) {
            payload.put("errorReason", event.errorReason());
        }
        try {
            userWebSocketHandler.broadcastAll(payload);
        } catch (RuntimeException e) {
            log.warn("FlywheelStepWsBroadcaster.onStepStateChanged: WS broadcast failed for stepRunId={} ({}→{}): {}",
                    event.stepRunId(), event.oldStatus(), event.newStatus(), e.getMessage());
        }
    }
}
