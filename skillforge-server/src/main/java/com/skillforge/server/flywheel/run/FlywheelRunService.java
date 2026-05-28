package com.skillforge.server.flywheel.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.flywheel.orchestrator.StepStateChangedEvent;
import com.skillforge.server.websocket.UserWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * OPT-LOOP-FRAMEWORK Sprint 1: CRUD + simple state machine for
 * {@link FlywheelRunEntity}. OPT-REPORT-V1 keeps its public {@code OptReportService}
 * surface (back-compat) but internally delegates the row writes here, so any
 * future loop orchestrator (memory-curation, attribution, ...) can land on the
 * same persistence + WS-broadcast pipeline.
 *
 * <p>State machine (matches OPT-REPORT-V1 lifecycle):
 * <pre>
 *   pending → running → completed
 *                    └→ error
 * </pre>
 *
 * <p>{@link #transitionStatus} fires a {@code flywheel_run_status_changed}
 * WebSocket event on every state change so the dashboard "All Flywheel Runs"
 * page can update live. OPT-REPORT keeps its own {@code opt_report_completed}
 * broadcast in addition (W6 dual-event back-compat).
 */
@Service
public class FlywheelRunService {

    private static final Logger log = LoggerFactory.getLogger(FlywheelRunService.class);

    /**
     * Status transitions accepted by {@link #transitionStatus}. We rely on
     * Service-layer validation rather than a DB CHECK because the same row
     * can legally go {@code pending → running → completed} or skip running
     * (e.g. when a worker fails before kickoff).
     */
    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
            FlywheelRunEntity.STATUS_PENDING, Set.of(
                    FlywheelRunEntity.STATUS_RUNNING,
                    FlywheelRunEntity.STATUS_COMPLETED,
                    FlywheelRunEntity.STATUS_ERROR),
            FlywheelRunEntity.STATUS_RUNNING, Set.of(
                    FlywheelRunEntity.STATUS_COMPLETED,
                    FlywheelRunEntity.STATUS_ERROR)
    );

    /**
     * Sprint 2: per-step state-machine transitions. Mirrors the run-level
     * machine but tighter — steps can only go {@code pending →
     * completed/error}; idempotent re-transitions that don't change the
     * status are silently accepted (the executor's
     * {@code completeStep} relies on this to handle race-y double-completes).
     */
    private static final Map<String, Set<String>> ALLOWED_STEP_TRANSITIONS = Map.of(
            FlywheelRunStepEntity.STATUS_PENDING, Set.of(
                    FlywheelRunStepEntity.STATUS_COMPLETED,
                    FlywheelRunStepEntity.STATUS_ERROR)
    );

    private final FlywheelRunRepository runRepository;
    private final FlywheelRunStepRepository stepRepository;
    private final UserWebSocketHandler userWebSocketHandler;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ApplicationEventPublisher applicationEventPublisher;

    public FlywheelRunService(FlywheelRunRepository runRepository,
                              FlywheelRunStepRepository stepRepository,
                              UserWebSocketHandler userWebSocketHandler,
                              ObjectMapper objectMapper,
                              Clock clock,
                              ApplicationEventPublisher applicationEventPublisher) {
        this.runRepository = runRepository;
        this.stepRepository = stepRepository;
        this.userWebSocketHandler = userWebSocketHandler;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    /**
     * Insert a {@link FlywheelRunEntity} row in {@code status=pending}. Caller
     * (e.g. {@code OptReportService.startReport}) is responsible for spawning
     * any orchestrator session afterwards and calling
     * {@link #attachGeneratorSession} once the session id is known.
     *
     * <p><b>Not @Transactional</b> by design: each repository {@code save}
     * commits in its own short tx so the row is visible to the async
     * orchestrator runLoop thread before kickoff (mirrors the original
     * OPT-REPORT-V1 pattern documented on {@code OptReportService.startReport}).
     *
     * @param loopKind       one of {@link FlywheelRunEntity}{@code .LOOP_KIND_*}
     * @param triggerSource  one of {@link FlywheelRunEntity}{@code .TRIGGER_SOURCE_*}
     * @param inputJson      free-schema map; serialized to JSONB
     * @param agentId        target agent id
     * @param windowDays     historical OPT-REPORT-V1 window (>= 1); future
     *                       loop_kinds without a time window may pass any
     *                       positive int — only the JSONB carries the canonical
     *                       value
     */
    public FlywheelRunEntity startRun(String loopKind,
                                      String triggerSource,
                                      Map<String, Object> inputJson,
                                      Long agentId,
                                      int windowDays) {
        if (loopKind == null || loopKind.isBlank()) {
            throw new IllegalArgumentException("loopKind is required");
        }
        if (triggerSource == null || triggerSource.isBlank()) {
            throw new IllegalArgumentException("triggerSource is required");
        }
        if (agentId == null || agentId <= 0L) {
            throw new IllegalArgumentException("agentId must be a positive long");
        }
        if (windowDays < 1) {
            throw new IllegalArgumentException("windowDays must be >= 1");
        }

        Instant now = clock.instant();
        Instant windowStart = now.minus(windowDays, ChronoUnit.DAYS);

        FlywheelRunEntity run = new FlywheelRunEntity();
        run.setId(UUID.randomUUID().toString());
        run.setAgentId(agentId);
        run.setWindowStart(windowStart);
        run.setWindowEnd(now);
        run.setStatus(FlywheelRunEntity.STATUS_PENDING);
        run.setLoopKind(loopKind);
        run.setTriggerSource(triggerSource);
        run.setInputJson(serializeInput(inputJson));
        runRepository.save(run);

        log.info("FlywheelRunService.startRun: runId={} loopKind={} triggerSource={} agentId={} windowDays={}",
                run.getId(), loopKind, triggerSource, agentId, windowDays);
        return run;
    }

    /**
     * Attach the spawned orchestrator session id and transition the run to
     * {@code running}. Fires the {@code flywheel_run_status_changed} WS event.
     *
     * <p><b>State machine guard</b>: only allowed when the current status is
     * {@code pending}. Calling on a {@code running} / {@code completed} /
     * {@code error} row throws {@link IllegalStateException} — silently
     * rewinding a terminal state to {@code running} would mask either a
     * double-spawn bug or a race against {@link #markCompleted} /
     * {@link #markError}. (Sprint 1 r1 review W1 fix.)
     */
    @Transactional
    public FlywheelRunEntity attachGeneratorSession(String runId, String generatorSessionId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId is required");
        }
        FlywheelRunEntity run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("FlywheelRun not found: " + runId));
        String oldStatus = run.getStatus();
        if (!FlywheelRunEntity.STATUS_PENDING.equals(oldStatus)) {
            throw new IllegalStateException(
                    "attachGeneratorSession requires status=pending; got '" + oldStatus
                            + "' (runId=" + runId + ")");
        }
        run.setGeneratorSessionId(generatorSessionId);
        run.setStatus(FlywheelRunEntity.STATUS_RUNNING);
        runRepository.save(run);
        broadcastStatusChanged(run, oldStatus, FlywheelRunEntity.STATUS_RUNNING, null);
        return run;
    }

    /**
     * Mark the run as completed with optional summary payload (for the OPT-REPORT
     * caller this is content_md + summary_json). Validates the transition and
     * fires a WS broadcast.
     */
    @Transactional
    public FlywheelRunEntity markCompleted(String runId, String contentMd, String summaryJson) {
        FlywheelRunEntity run = requireWritableRun(runId);
        String oldStatus = run.getStatus();
        if (contentMd != null) run.setContentMd(contentMd);
        if (summaryJson != null) run.setSummaryJson(summaryJson);
        run.setStatus(FlywheelRunEntity.STATUS_COMPLETED);
        runRepository.save(run);
        broadcastStatusChanged(run, oldStatus, FlywheelRunEntity.STATUS_COMPLETED, null);
        return run;
    }

    /**
     * Mark the run as errored with an explicit reason. Validates the transition
     * and fires a WS broadcast.
     */
    @Transactional
    public FlywheelRunEntity markError(String runId, String errorReason) {
        FlywheelRunEntity run = requireWritableRun(runId);
        String oldStatus = run.getStatus();
        run.setErrorReason(errorReason);
        run.setStatus(FlywheelRunEntity.STATUS_ERROR);
        runRepository.save(run);
        broadcastStatusChanged(run, oldStatus, FlywheelRunEntity.STATUS_ERROR, errorReason);
        return run;
    }

    /**
     * Generic state-machine helper exposed for tests / future callers that
     * need to drive arbitrary {@code pending→running→completed/error}
     * transitions without going through {@link #markCompleted} /
     * {@link #markError}.
     */
    @Transactional
    public FlywheelRunEntity transitionStatus(String runId, String newStatus, String errorReason) {
        FlywheelRunEntity run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("FlywheelRun not found: " + runId));
        String oldStatus = run.getStatus();
        if (oldStatus.equals(newStatus)) return run;
        Set<String> allowed = ALLOWED_TRANSITIONS.get(oldStatus);
        if (allowed == null || !allowed.contains(newStatus)) {
            throw new IllegalStateException(
                    "Disallowed transition: " + oldStatus + " → " + newStatus
                            + " (runId=" + runId + ")");
        }
        run.setStatus(newStatus);
        if (FlywheelRunEntity.STATUS_ERROR.equals(newStatus) && errorReason != null) {
            run.setErrorReason(errorReason);
        }
        runRepository.save(run);
        broadcastStatusChanged(run, oldStatus, newStatus, errorReason);
        return run;
    }

    public Optional<FlywheelRunEntity> findById(String runId) {
        return runRepository.findById(runId);
    }

    // ─────────────────────────────────────────────────────────────────────
    // OPT-LOOP-FRAMEWORK Sprint 2 — step CRUD + state machine (W-3 option 2)
    //
    // OPT-REPORT-V1's RecordBatchAnnotationsTool keeps writing
    // t_flywheel_run_step rows directly via the Repository (UPSERT semantics
    // are particular to the V99 OPT-REPORT batch flow). The Sprint 2
    // framework path goes through these methods so step state transitions
    // emit StepStateChangedEvent for the WS broadcaster, and so dashboard /
    // IT reads land on the shared @Transactional(readOnly=true) path.
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Sprint 2: insert a {@code pending} step row for an
     * {@code OrchestratorAgentExecutor.dispatchSubAgents} fan-out worker.
     *
     * <p>{@code subAgentSessionId} is left {@code null} here; the caller is
     * expected to follow up with {@link #attachStepSubAgentSession} once it
     * has spawned the worker session. The split is intentional — the worker
     * session id is only known after {@code sessionService.createSubSession},
     * which itself runs in its own transaction.
     *
     * @return the new step's id (UUID)
     */
    @Transactional
    public String appendStep(String runId, String stepInputJson) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId is required");
        }
        FlywheelRunStepEntity step = new FlywheelRunStepEntity();
        step.setId(UUID.randomUUID().toString());
        step.setRunId(runId);
        step.setStatus(FlywheelRunStepEntity.STATUS_PENDING);
        step.setStepInputJson(stepInputJson == null || stepInputJson.isBlank() ? "{}" : stepInputJson);
        step.setStepKind(FlywheelRunStepEntity.STEP_KIND_SUBAGENT_DISPATCH);
        // r2 fix N-1 (java + database overlap): dropped the redundant runRepository.findById
        // pre-check. The DB FK on t_flywheel_run_step.run_id already enforces the constraint;
        // pre-checking just doubles the round-trip. We translate the
        // DataIntegrityViolationException into the same IllegalArgumentException shape the
        // pre-check used, so callers see no surface change.
        try {
            stepRepository.save(step);
        } catch (org.springframework.dao.DataIntegrityViolationException fk) {
            throw new IllegalArgumentException("FlywheelRun not found: " + runId, fk);
        }
        log.info("FlywheelRunService.appendStep: stepRunId={} runId={}", step.getId(), runId);
        return step.getId();
    }

    /**
     * Sprint 2: backfill the worker session id once the executor has spawned
     * the child loop. Required for the {@code SessionLoopFinishedEvent}
     * fallback listener to find this step row by session id.
     */
    @Transactional
    public void attachStepSubAgentSession(String stepRunId, String subAgentSessionId) {
        if (stepRunId == null || stepRunId.isBlank()) {
            throw new IllegalArgumentException("stepRunId is required");
        }
        FlywheelRunStepEntity step = stepRepository.findById(stepRunId)
                .orElseThrow(() -> new IllegalArgumentException("FlywheelRunStep not found: " + stepRunId));
        step.setSubAgentSessionId(subAgentSessionId);
        stepRepository.save(step);
    }

    /**
     * Sprint 2: transition a pending step to a terminal state. Persists the
     * worker's output JSON (free-schema) + error reason, then publishes a
     * {@link StepStateChangedEvent} (AFTER_COMMIT consumer broadcasts WS).
     *
     * <p>Idempotent: if the row is already in the requested terminal state
     * the call is a no-op and returns {@code null} — this lets the executor
     * harmlessly double-transition when both the Tool path and the fallback
     * listener race.
     *
     * @return the updated entity, or {@code null} when the call was a no-op
     */
    @Transactional
    public FlywheelRunStepEntity transitionStepStatus(String stepRunId,
                                                      String newStatus,
                                                      JsonNode outputJson,
                                                      String errorReason) {
        if (stepRunId == null || stepRunId.isBlank()) {
            throw new IllegalArgumentException("stepRunId is required");
        }
        FlywheelRunStepEntity step = stepRepository.findById(stepRunId)
                .orElseThrow(() -> new IllegalArgumentException("FlywheelRunStep not found: " + stepRunId));
        String oldStatus = step.getStatus();
        if (oldStatus.equals(newStatus)) {
            return null; // idempotent no-op
        }
        Set<String> allowed = ALLOWED_STEP_TRANSITIONS.get(oldStatus);
        if (allowed == null || !allowed.contains(newStatus)) {
            throw new IllegalStateException(
                    "Disallowed step transition: " + oldStatus + " → " + newStatus
                            + " (stepRunId=" + stepRunId + ")");
        }
        step.setStatus(newStatus);
        if (outputJson != null) {
            try {
                step.setStepOutputJson(objectMapper.writeValueAsString(outputJson));
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "stepOutputJson serialize failed: " + e.getMessage(), e);
            }
        }
        if (FlywheelRunStepEntity.STATUS_ERROR.equals(newStatus)) {
            step.setErrorReason(errorReason);
        } else {
            step.setErrorReason(null); // clear stale error on success
        }
        stepRepository.save(step);

        // Publish AFTER the row save — listener is AFTER_COMMIT so even if it
        // gets routed sync (fallbackExecution=true with no tx around), the row
        // is observable. WS broadcast itself lives in
        // FlywheelStepWsBroadcaster, not here (Plan §2 Design 5 Rule 5).
        //
        // r2 fix W-1 (4 reviewer overlap): removed null guard around
        // applicationEventPublisher — constructor-injected Spring Bean is
        // never null in production, and the guard misleads readers into
        // thinking the field is optional. Inner try/catch retained so a
        // misbehaving listener doesn't roll back the row save.
        try {
            applicationEventPublisher.publishEvent(new StepStateChangedEvent(
                    step.getId(), step.getRunId(), oldStatus, newStatus, errorReason));
        } catch (Exception e) {
            log.warn("FlywheelRunService.transitionStepStatus: publish event failed for stepRunId={}: {}",
                    stepRunId, e.getMessage(), e);
        }
        return step;
    }

    /**
     * Sprint 2 (N-3): consumes Sprint 1 W3 backlog by making the new step
     * list query explicitly {@code readOnly=true}. Reads the chronological
     * step rows for a parent run via
     * {@link FlywheelRunStepRepository#findByRunIdOrderByCreatedAtAsc}.
     */
    @Transactional(readOnly = true)
    public List<FlywheelRunStepEntity> listStepsByRunId(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId is required");
        }
        return stepRepository.findByRunIdOrderByCreatedAtAsc(runId);
    }

    private FlywheelRunEntity requireWritableRun(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId is required");
        }
        FlywheelRunEntity run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("FlywheelRun not found: " + runId));
        String status = run.getStatus();
        if (!FlywheelRunEntity.STATUS_PENDING.equals(status)
                && !FlywheelRunEntity.STATUS_RUNNING.equals(status)) {
            throw new IllegalStateException(
                    "FlywheelRun " + runId + " not writable (status=" + status + ")");
        }
        return run;
    }

    private String serializeInput(Map<String, Object> inputJson) {
        if (inputJson == null || inputJson.isEmpty()) return "{}";
        try {
            return objectMapper.writeValueAsString(inputJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("inputJson could not be serialized: " + e.getMessage(), e);
        }
    }

    /**
     * Best-effort WS broadcast. Swallows runtime errors so a dropped connection
     * never masks a successful DB write (operator can still see the row by
     * polling).
     */
    private void broadcastStatusChanged(FlywheelRunEntity run,
                                        String oldStatus,
                                        String newStatus,
                                        String errorReason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "flywheel_run_status_changed");
        payload.put("runId", run.getId());
        payload.put("loopKind", run.getLoopKind());
        payload.put("agentId", run.getAgentId());
        payload.put("oldStatus", oldStatus);
        payload.put("newStatus", newStatus);
        Instant updatedAt = run.getUpdatedAt();
        payload.put("timestamp", updatedAt == null ? clock.instant().toString() : updatedAt.toString());
        if (errorReason != null) {
            payload.put("errorReason", errorReason);
        }
        try {
            userWebSocketHandler.broadcastAll(payload);
        } catch (RuntimeException e) {
            log.warn("FlywheelRunService.broadcastStatusChanged: WS broadcast failed for runId={} ({}→{}): {}",
                    run.getId(), oldStatus, newStatus, e.getMessage());
        }
    }
}
