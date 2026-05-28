package com.skillforge.server.flywheel.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.flywheel.run.FlywheelRunService;
import com.skillforge.server.flywheel.run.FlywheelRunStepEntity;
import com.skillforge.server.flywheel.run.FlywheelRunStepRepository;
import com.skillforge.server.service.event.SessionLoopFinishedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OPT-LOOP-FRAMEWORK Sprint 2: bridge between Spring events and the
 * {@link OrchestratorAgentExecutor} pending-future map.
 *
 * <h2>Two completion paths</h2>
 * <ol>
 *   <li><b>main path</b> — worker SubAgent calls
 *       {@link com.skillforge.server.tool.flywheel.RecordOrchestrationStepResult}
 *       Tool. The tool transitions the step row + publishes
 *       {@link OrchestrationStepCompletedEvent}. {@link #onStepCompleted}
 *       handles it.</li>
 *   <li><b>fallback path</b> — worker session loop terminates without the
 *       Tool ever firing (worker crashed / didn't follow prompt / LLM hung).
 *       {@code ChatService.runLoop} teardown publishes
 *       {@link SessionLoopFinishedEvent}. {@link #onSessionLoopFinishedFallback}
 *       looks up the matching pending step by sub_agent_session_id and
 *       synthesizes a {@code StepResult} from the worker's finalMessage.</li>
 * </ol>
 *
 * <h2>Three-annotation pattern (Plan N-1)</h2>
 * <pre>
 *   @Async                                  ← off the publisher thread
 *   @TransactionalEventListener(            ← runs AFTER_COMMIT
 *       phase=AFTER_COMMIT,
 *       fallbackExecution=true)             ← fire even when no tx (publisher
 *                                              is sometimes ChatService loop
 *                                              teardown which is non-tx)
 *   @Transactional(REQUIRES_NEW)            ← listener own tx scope
 * </pre>
 * Direct lift from {@code SkillCreatorEvalCoordinator.onSessionLoopFinished}
 * (Phase 1.6 r5 hotfix) which proved this is the only working combination
 * for {@code SessionLoopFinishedEvent} consumers.
 *
 * <h2>Idempotent guard</h2>
 * Both paths funnel through
 * {@link OrchestratorAgentExecutor#completeStep(String, StepResult)}; the
 * executor's {@code pendingSteps.remove(stepRunId)} returning null is the
 * race-winner gate. Whichever path arrives second silently no-ops.
 */
@Component
public class WorkerCompletionListener {

    private static final Logger log = LoggerFactory.getLogger(WorkerCompletionListener.class);

    private final OrchestratorAgentExecutor executor;
    private final FlywheelRunService flywheelRunService;
    private final FlywheelRunStepRepository stepRepository;
    private final ObjectMapper objectMapper;

    public WorkerCompletionListener(OrchestratorAgentExecutor executor,
                                    FlywheelRunService flywheelRunService,
                                    FlywheelRunStepRepository stepRepository,
                                    ObjectMapper objectMapper) {
        this.executor = executor;
        this.flywheelRunService = flywheelRunService;
        this.stepRepository = stepRepository;
        this.objectMapper = objectMapper;
    }

    // ─────────────────────────────────────────────────────────────────────
    // MAIN PATH — RecordOrchestrationStepResult Tool published this event
    // ─────────────────────────────────────────────────────────────────────

    @Async
    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onStepCompleted(OrchestrationStepCompletedEvent event) {
        if (event == null || event.stepRunId() == null) return;
        try {
            StepResult result = new StepResult(
                    event.stepRunId(), event.status(),
                    event.outputJson(), event.errorReason());
            executor.completeStep(event.stepRunId(), result);
            log.debug("WorkerCompletionListener.onStepCompleted: stepRunId={} status={} trigger={}",
                    event.stepRunId(), event.status(), event.triggerSource());
        } catch (RuntimeException ex) {
            // Defense-in-depth: never bubble back into the publishing tx
            // (which is already committed at this point).
            log.error("WorkerCompletionListener.onStepCompleted: failed for stepRunId={}",
                    event.stepRunId(), ex);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // FALLBACK PATH — worker session terminated without calling the Tool
    // ─────────────────────────────────────────────────────────────────────

    @Async
    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSessionLoopFinishedFallback(SessionLoopFinishedEvent event) {
        if (event == null || event.sessionId() == null) return;
        // Skip waiting_user — that's not a terminal step state, it just means
        // the worker is paused for input. The matching step row should stay
        // pending so the user-resume → loop-finish path can fire this listener
        // again later.
        if ("waiting_user".equals(event.finalStatus())) return;

        try {
            stepRepository.findBySubAgentSessionIdAndStatus(
                    event.sessionId(), FlywheelRunStepEntity.STATUS_PENDING)
                    .ifPresent(step -> handleFallback(step, event));
        } catch (RuntimeException ex) {
            log.error("WorkerCompletionListener.onSessionLoopFinishedFallback: failed for sessionId={}",
                    event.sessionId(), ex);
        }
    }

    private void handleFallback(FlywheelRunStepEntity step, SessionLoopFinishedEvent event) {
        // Map ChatService loop status → step status
        String fallbackStatus = "completed".equals(event.finalStatus())
                ? FlywheelRunStepEntity.STATUS_COMPLETED
                : FlywheelRunStepEntity.STATUS_ERROR;
        String errorReason = fallbackStatus.equals(FlywheelRunStepEntity.STATUS_ERROR)
                ? "fallback: worker terminated with status=" + event.finalStatus()
                : null;

        // Build a synthesized outputJson that captures the worker's
        // finalMessage + a triggerSource marker so dashboards / operators
        // can tell this didn't come from the Tool path.
        Map<String, Object> outputPayload = new LinkedHashMap<>();
        outputPayload.put("triggerSource", OrchestrationStepCompletedEvent.TRIGGER_FALLBACK);
        outputPayload.put("finalMessage", event.finalMessage());
        outputPayload.put("finalStatus", event.finalStatus());
        JsonNode outputJson = objectMapper.valueToTree(outputPayload);

        try {
            flywheelRunService.transitionStepStatus(
                    step.getId(), fallbackStatus, outputJson, errorReason);
        } catch (IllegalStateException raceErr) {
            // Idempotent guard: step might have flipped to completed via the
            // Tool path between our query + this transition. transitionStepStatus
            // throws on disallowed transition (e.g. completed → error). Treat
            // as a no-op — the Tool path already drove the executor future.
            log.debug("WorkerCompletionListener.handleFallback: stepRunId={} already terminal, ignoring",
                    step.getId());
            return;
        }
        StepResult result = new StepResult(
                step.getId(), fallbackStatus, outputJson, errorReason);
        executor.completeStep(step.getId(), result);
        log.warn("WorkerCompletionListener.handleFallback: stepRunId={} sessionId={} finalStatus={} "
                + "— worker didn't call RecordOrchestrationStepResult; used finalMessage",
                step.getId(), event.sessionId(), event.finalStatus());
    }
}
