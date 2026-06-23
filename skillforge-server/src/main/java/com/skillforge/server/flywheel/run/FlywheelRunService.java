package com.skillforge.server.flywheel.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.websocket.UserWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
     * FR-C7 (rolling-window fix): default budget window in hours (7 days) used
     * when the configured window is out of range. See {@link #normalizeWindowHours}.
     */
    public static final int DEFAULT_AB_BUDGET_WINDOW_HOURS = 168;

    /**
     * FR-C7 (r1 hardening): inclusive upper bound for the budget window (1 year).
     * A window larger than this effectively re-creates the lifetime-cumulative
     * freeze the fix removed, so it is rejected (warn + fallback). Valid configured
     * range is {@code [1, 8760]}.
     */
    public static final int MAX_AB_BUDGET_WINDOW_HOURS = 8760;

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
                    FlywheelRunEntity.STATUS_ERROR,
                    // AUTOEVOLVING V1 Sprint 2: park on a humanApprove() gate.
                    FlywheelRunEntity.STATUS_PAUSED),
            // paused → running is the resume path (chunk 2); paused →
            // completed/error lets the run finish/fail directly from a gate.
            FlywheelRunEntity.STATUS_PAUSED, Set.of(
                    FlywheelRunEntity.STATUS_RUNNING,
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

    public FlywheelRunService(FlywheelRunRepository runRepository,
                              FlywheelRunStepRepository stepRepository,
                              UserWebSocketHandler userWebSocketHandler,
                              ObjectMapper objectMapper,
                              Clock clock) {
        this.runRepository = runRepository;
        this.stepRepository = stepRepository;
        this.userWebSocketHandler = userWebSocketHandler;
        this.objectMapper = objectMapper;
        this.clock = clock;
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
        return doTransitionStatus(runId, newStatus, errorReason);
    }

    /**
     * Core run-status transition logic, factored out so callers that already hold
     * an open transaction (e.g. {@link #recordApproveDecisionAndResume}) can reuse
     * it WITHOUT a {@code this.}-self-invocation through the {@code @Transactional}
     * proxy (which would silently bypass the annotation — java.md footgun #2).
     * Always runs in the caller's transaction; never opens its own.
     */
    private FlywheelRunEntity doTransitionStatus(String runId, String newStatus, String errorReason) {
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

    /**
     * AUTOEVOLVING V1 Sprint 2 (Task F r1 java-W2): atomically record an approve
     * decision on the frontier {@code human_approve} gate step AND transition the
     * run {@code paused → running}, in a single transaction.
     *
     * <p>Why one transaction: {@code WorkflowRunnerService.resume()} previously
     * issued these as two independent {@code @Transactional} calls. A partial
     * failure (gate committed {@code completed}, then the run transition throws)
     * left the run wedged — {@code findPendingApproveStep} returns empty so a retry
     * {@code approve} fails with {@code IllegalStateException} and the run can never
     * resume. Co-locating both writes here makes them all-or-nothing.
     *
     * <p>Implementation note (java.md footgun #2): the two writes call the
     * package-private {@code doTransitionStepStatus}/{@code doTransitionStatus}
     * helpers directly — NOT the public {@code @Transactional} overloads via
     * {@code this.} — so there is no self-invocation that would bypass the proxy.
     * Both helpers join this method's transaction (the only one), so they commit
     * or roll back together.
     *
     * @param gateStepId   the pending {@code human_approve} step's id
     * @param runId        the paused run's id
     * @param decisionJson the operator's decision payload (written to the gate's
     *                     {@code step_output_json})
     */
    @Transactional
    public void recordApproveDecisionAndResume(String gateStepId, String runId, JsonNode decisionJson) {
        doTransitionStepStatus(
                gateStepId, FlywheelRunStepEntity.STATUS_COMPLETED, decisionJson, null);
        doTransitionStatus(runId, FlywheelRunEntity.STATUS_RUNNING, null);
    }

    /**
     * AUTOEVOLVING V1 Sprint 2: park a {@code running} workflow run on a
     * {@code humanApprove()} gate ({@code running → paused}). Delegates to
     * {@link #transitionStatus} so the state-machine guard + the
     * {@code flywheel_run_status_changed} WS broadcast both fire. {@code reason}
     * is logged for operator traceability (the column itself carries no reason —
     * {@code error_reason} is reserved for failures).
     *
     * @throws IllegalStateException if the run is not currently {@code running}
     */
    @Transactional
    public FlywheelRunEntity pauseRun(String runId, String reason) {
        log.info("FlywheelRunService.pauseRun: runId={} reason={}", runId, reason);
        return transitionStatus(runId, FlywheelRunEntity.STATUS_PAUSED, null);
    }

    public Optional<FlywheelRunEntity> findById(String runId) {
        return runRepository.findById(runId);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Step CRUD + state machine
    //
    // OPT-REPORT-V1's RecordBatchAnnotationsTool keeps writing
    // t_flywheel_run_step rows directly via the Repository (UPSERT semantics
    // are particular to the V99 OPT-REPORT batch flow). These methods are
    // the step-level API the future workflow runtime (V2 DSL) will call;
    // dashboard / IT reads land on the shared @Transactional(readOnly=true) path.
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Insert a {@code pending} step row.
     *
     * <p>{@code subAgentSessionId} is left {@code null} here; the caller is
     * expected to follow up with {@link #attachStepSubAgentSession} once it
     * has spawned the worker session.
     *
     * @return the new step's id (UUID)
     */
    @Transactional
    public String appendStep(String runId, String stepInputJson) {
        return appendStep(runId, stepInputJson,
                FlywheelRunStepEntity.STEP_KIND_SUBAGENT_DISPATCH, null);
    }

    /**
     * AUTOEVOLVING V1 Sprint 2: insert a {@code pending} step row carrying an
     * explicit {@code stepKind} ({@code subagent_dispatch} / {@code human_approve})
     * and the deterministic {@code stepIndex} (V127 {@code step_index} column —
     * the journal-replay lookup key). The legacy 2-arg overload delegates here
     * with {@code stepKind=subagent_dispatch, stepIndex=null}, so OPT-REPORT /
     * orchestrator callers are unaffected (their rows keep {@code step_index=null}).
     *
     * @param stepIndex deterministic invoke-order index, or {@code null} for
     *                  non-workflow callers
     * @return the new step's id (UUID)
     */
    @Transactional
    public String appendStep(String runId, String stepInputJson, String stepKind, Integer stepIndex) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId is required");
        }
        FlywheelRunStepEntity step = new FlywheelRunStepEntity();
        step.setId(UUID.randomUUID().toString());
        step.setRunId(runId);
        step.setStatus(FlywheelRunStepEntity.STATUS_PENDING);
        step.setStepInputJson(stepInputJson == null || stepInputJson.isBlank() ? "{}" : stepInputJson);
        step.setStepKind(stepKind == null || stepKind.isBlank()
                ? FlywheelRunStepEntity.STEP_KIND_SUBAGENT_DISPATCH : stepKind);
        step.setStepIndex(stepIndex);
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
     * Backfill the worker session id once a child loop has been spawned.
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
     * Transition a pending step to a terminal state. Persists the worker's
     * output JSON (free-schema) + error reason.
     *
     * <p>Idempotent: if the row is already in the requested terminal state
     * the call is a no-op and returns {@code null}.
     *
     * @return the updated entity, or {@code null} when the call was a no-op
     */
    @Transactional
    public FlywheelRunStepEntity transitionStepStatus(String stepRunId,
                                                      String newStatus,
                                                      JsonNode outputJson,
                                                      String errorReason) {
        return doTransitionStepStatus(stepRunId, newStatus, outputJson, errorReason);
    }

    /**
     * Core step-status transition logic, factored out so
     * {@link #recordApproveDecisionAndResume} can reuse it within its own
     * transaction without a {@code this.}-self-invocation (java.md footgun #2).
     * Always runs in the caller's transaction; never opens its own.
     */
    private FlywheelRunStepEntity doTransitionStepStatus(String stepRunId,
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

    /**
     * AUTOEVOLVING V1 Sprint 2 (Task F — resume): the parked {@code human_approve}
     * gate step a paused workflow run is waiting on. A correctly-paused run has
     * exactly one {@code pending} {@code human_approve} step; more than one means
     * a state-machine bug, so the caller throws rather than picks arbitrarily.
     *
     * @return the unique pending gate step, or empty if none is pending
     * @throws IllegalStateException if more than one pending gate step exists
     */
    @Transactional(readOnly = true)
    public Optional<FlywheelRunStepEntity> findPendingApproveStep(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId is required");
        }
        List<FlywheelRunStepEntity> gates = stepRepository.findByRunIdAndStepKindAndStatus(
                runId, FlywheelRunStepEntity.STEP_KIND_HUMAN_APPROVE,
                FlywheelRunStepEntity.STATUS_PENDING);
        if (gates.size() > 1) {
            throw new IllegalStateException(
                    "Run " + runId + " has " + gates.size()
                            + " pending human_approve steps; expected at most one");
        }
        return gates.isEmpty() ? Optional.empty() : Optional.of(gates.get(0));
    }

    /**
     * AUTOEVOLVE-AGENT-FLYWHEEL Module C (RecordIteration): append a completed
     * {@code evolve_iteration} ledger step to an {@code evolve} run in one shot.
     * The orchestrator's {@code RecordIteration} tool calls this each loop turn;
     * {@code stepOutputJson} carries the iteration / surface / changeDesc /
     * candidateId / baselineScore / candidateScore / delta / kept (+ optional
     * abRunId). Unlike the OPT-REPORT batch path there is no separate worker
     * session, so the row is created and immediately marked {@code completed}.
     *
     * <p>Two writes (append pending + transition to completed) run in this
     * method's single transaction. Reuses {@link #appendStep(String, String,
     * String, Integer)} + the package-private {@link #doTransitionStepStatus}
     * (NOT the public {@code @Transactional} overload via {@code this.} — that
     * would self-invoke past the proxy; java.md footgun #2).
     *
     * @param runId        the evolve run id
     * @param iteration    1-based iteration index (used as the deterministic
     *                     {@code step_index})
     * @param outputJson   free-schema iteration payload (serialized to JSONB)
     * @return the new step's id (UUID)
     */
    @Transactional
    public String appendEvolveIterationStep(String runId, int iteration, JsonNode outputJson) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId is required");
        }
        if (iteration < 1) {
            throw new IllegalArgumentException("iteration must be >= 1");
        }
        String stepId = appendStep(runId, "{}",
                FlywheelRunStepEntity.STEP_KIND_EVOLVE_ITERATION, iteration);
        doTransitionStepStatus(
                stepId, FlywheelRunStepEntity.STATUS_COMPLETED, outputJson, null);
        log.info("FlywheelRunService.appendEvolveIterationStep: runId={} iteration={} stepId={}",
                runId, iteration, stepId);
        return stepId;
    }

    /**
     * AUTOEVOLVE-AGENT-FLYWHEEL Module C (FR-C7): per-run A/B trigger count —
     * number of {@code evolve_iteration} steps recorded for a specific run.
     * Used by {@code TriggerAbEval} when {@code evolveRunId} is provided (precise
     * per-run count, may trail in-flight A/Bs).
     */
    @Transactional(readOnly = true)
    public long countEvolveAbTriggers(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId is required");
        }
        return stepRepository.countByRunIdAndStepKind(
                runId, FlywheelRunStepEntity.STEP_KIND_EVOLVE_ITERATION);
    }

    /**
     * AUTOEVOLVE-AGENT-FLYWHEEL Module C (FR-C7 CRIT-1 fix; FR-C7 rolling-window
     * fix 2026-06-24): per-agent A/B trigger count — {@code evolve_iteration} steps
     * across ALL evolve runs for the given agent <b>within a rolling time window</b>
     * (steps created in the last {@code windowHours} hours). Used as the
     * always-enforced cap counter in {@code TriggerAbEval}: since
     * {@code targetAgentId} is always-required in that tool, the cap fires
     * unconditionally (an LLM that omits {@code evolveRunId} cannot bypass it by
     * switching to this path).
     *
     * <p><b>Window vs lifetime (security trade-off).</b> The count was previously
     * lifetime-cumulative, which permanently froze any repeatedly-evolved agent
     * once it crossed the cap. Switching to a rolling window resets the budget each
     * window so iteration can continue, WITHOUT weakening the CRIT-1 cross-run
     * defence: within the window the count still aggregates every evolve run for the
     * agent (the join is unchanged), so opening multiple runs inside one window does
     * not bypass the cap. The only relaxation is cross-window: budget that aged out
     * of the window no longer counts — which is the intended behaviour.
     *
     * @param agentId     the agent being evolved (positive)
     * @param windowHours rolling window size in hours; the count includes only
     *                    steps with {@code created_at >= now - windowHours}. An
     *                    out-of-range value (outside {@code [1, }
     *                    {@value #MAX_AB_BUDGET_WINDOW_HOURS}{@code ]}) is normalized
     *                    to {@value #DEFAULT_AB_BUDGET_WINDOW_HOURS} via
     *                    {@link #normalizeWindowHours(int)} (warn + fallback, NOT a
     *                    throw) so a bad config can never crash the call nor
     *                    re-create the lifetime freeze. {@code since} is derived from
     *                    the service's injected {@link Clock} so the window is
     *                    test-deterministic.
     */
    @Transactional(readOnly = true)
    public long countEvolveAbTriggersForAgent(Long agentId, int windowHours) {
        if (agentId == null || agentId <= 0L) {
            throw new IllegalArgumentException("agentId must be a positive long");
        }
        int effectiveWindowHours = normalizeWindowHours(windowHours);
        Instant since = clock.instant().minus(effectiveWindowHours, ChronoUnit.HOURS);
        return stepRepository.countEvolveIterationStepsByAgentIdSince(agentId, since);
    }

    /**
     * FR-C7 (r1 hardening): clamp/validate the configured budget window to the
     * valid range {@code [1, }{@value #MAX_AB_BUDGET_WINDOW_HOURS}{@code ]}. An
     * out-of-range value (≤ 0 or {@code > }{@value #MAX_AB_BUDGET_WINDOW_HOURS}) is
     * NOT honoured: it logs a warning and falls back to
     * {@value #DEFAULT_AB_BUDGET_WINDOW_HOURS} rather than throwing (a bad config
     * must not crash startup / the tool call) or silently accepting a window so
     * large it re-creates the lifetime-cumulative freeze bug. Shared by
     * {@code TriggerAbEvalTool} (constructor normalization) and this service so the
     * two layers agree exactly.
     */
    public static int normalizeWindowHours(int windowHours) {
        if (windowHours < 1 || windowHours > MAX_AB_BUDGET_WINDOW_HOURS) {
            log.warn("FR-C7: ab-budget-window-hours={} out of range [1, {}] — falling back to "
                    + "default {}h", windowHours, MAX_AB_BUDGET_WINDOW_HOURS,
                    DEFAULT_AB_BUDGET_WINDOW_HOURS);
            return DEFAULT_AB_BUDGET_WINDOW_HOURS;
        }
        return windowHours;
    }

    /**
     * AUTOEVOLVE-AGENT-FLYWHEEL Module C (HIGH-1 fix): check whether this agent
     * already has an active ({@code running} or {@code pending}) evolve run.
     * {@code EvolveController} calls this before creating a new run to reject the
     * request with 409, preventing concurrent overlapping evolve loops on the same
     * agent (mirrors {@code ImprovementConflictException} guards in the improver
     * services).
     */
    @Transactional(readOnly = true)
    public boolean hasActiveEvolveRun(Long agentId) {
        if (agentId == null || agentId <= 0L) {
            throw new IllegalArgumentException("agentId must be a positive long");
        }
        return runRepository.countByAgentIdAndLoopKindAndStatusIn(
                agentId,
                FlywheelRunEntity.LOOP_KIND_EVOLVE,
                List.of(FlywheelRunEntity.STATUS_PENDING, FlywheelRunEntity.STATUS_RUNNING)) > 0;
    }

    private FlywheelRunEntity requireWritableRun(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId is required");
        }
        FlywheelRunEntity run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("FlywheelRun not found: " + runId));
        String status = run.getStatus();
        if (!FlywheelRunEntity.STATUS_PENDING.equals(status)
                && !FlywheelRunEntity.STATUS_RUNNING.equals(status)
                // Sprint 2: a paused (human-approve) run may still be marked
                // completed/error directly (e.g. a reject that aborts the run).
                && !FlywheelRunEntity.STATUS_PAUSED.equals(status)) {
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
