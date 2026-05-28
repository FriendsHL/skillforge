package com.skillforge.server.flywheel.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.flywheel.run.FlywheelRunEntity;
import com.skillforge.server.flywheel.run.FlywheelRunService;
import com.skillforge.server.flywheel.run.FlywheelRunStepEntity;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.service.SessionService;
import com.skillforge.server.subagent.SubAgentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * OPT-LOOP-FRAMEWORK Sprint 2: shared fan-out engine for orchestrator-style
 * flywheel agents (memory-curation / attribution-batch / metrics-collector /
 * future loops). The Sprint 1 {@code FlywheelRunService} owns per-run state;
 * this class owns the per-step <i>dispatch + await + aggregate</i> mechanics.
 *
 * <h2>How it fits</h2>
 * Parent orchestrator agent's prompt drives the LLM to call
 * {@link com.skillforge.server.tool.flywheel.DispatchOrchestrationStep} Tool
 * with a list of {@link OrchestratorWorkerSpec}; the Tool calls
 * {@link #dispatchSubAgents} on this executor, which:
 * <ol>
 *   <li>writes one {@code FlywheelRunStepEntity} row per worker (status=pending),</li>
 *   <li>spawns one async SubAgent loop per worker via the same primitives
 *       {@code SubAgentTool} uses internally (Path B in Plan §2 Design 2 —
 *       <b>not</b> via the Tool itself, which returns a textual payload that
 *       would force the framework to parse log strings),</li>
 *   <li>holds one {@link CompletableFuture} per pending step in
 *       {@link #pendingSteps},</li>
 *   <li>returns a barrier future that completes when all per-step futures
 *       complete (either via the {@code RecordOrchestrationStepResult} Tool
 *       path or the {@code SessionLoopFinishedEvent} fallback path).</li>
 * </ol>
 *
 * <p>The two completion paths converge through
 * {@link WorkerCompletionListener} which calls {@link #completeStep} —
 * idempotent guard inside {@code pendingSteps.remove} means the late path
 * is a no-op.
 *
 * <h2>AOP contract (Plan §2 Design 5)</h2>
 * <ul>
 *   <li>{@link #dispatchSubAgents} is <b>not</b> {@code @Transactional} —
 *       per-step tx is owned by {@code FlywheelRunService.appendStep /
 *       transitionStepStatus}.</li>
 *   <li>{@link #completeStep} is <b>not</b> {@code @Transactional} —
 *       it only mutates the in-memory map; DB writes happen in the listener's
 *       own {@code REQUIRES_NEW} tx.</li>
 *   <li>The aggregate {@code CompletableFuture.get(timeout)} call must never
 *       be inside a caller's {@code @Transactional} — that would hold the JDBC
 *       connection idle for minutes.</li>
 * </ul>
 *
 * <h2>Process restart</h2>
 * {@link #pendingSteps} is in-memory only. JVM restart loses all in-flight
 * futures; the matching step rows are left in {@code status=pending} on disk.
 * MVP recovery story: a startup sweeper (R6 in Plan §8) marks pending steps
 * older than a threshold as {@code error} — not implemented in Sprint 2; the
 * Sprint 1 {@code SubAgentRunSweeper} pattern is the template when this gets
 * built in Sprint 3.
 */
@Service
public class OrchestratorAgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorAgentExecutor.class);

    private final SubAgentRegistry subAgentRegistry;
    private final SessionService sessionService;
    private final ChatService chatService;
    private final FlywheelRunService flywheelRunService;
    private final ObjectMapper objectMapper;

    /**
     * Per-step pending {@code CompletableFuture}. Listener completes via
     * {@link #completeStep}; the executor reads / removes inline. Private —
     * see Plan §2 Design 3 W-4 (listener never touches the map directly).
     */
    private final ConcurrentHashMap<String, CompletableFuture<StepResult>> pendingSteps =
            new ConcurrentHashMap<>();

    public OrchestratorAgentExecutor(SubAgentRegistry subAgentRegistry,
                                     SessionService sessionService,
                                     ChatService chatService,
                                     FlywheelRunService flywheelRunService,
                                     ObjectMapper objectMapper) {
        // r2 fix W-2 (3 reviewer overlap): removed dead Clock + ApplicationEventPublisher.
        // Event publishing for step state changes lives entirely in
        // FlywheelRunService.transitionStepStatus (already publishes
        // StepStateChangedEvent) and RecordOrchestrationStepResult Tool
        // (publishes OrchestrationStepCompletedEvent). The executor itself has
        // no events to publish — it only mutates the in-memory pendingSteps map.
        // Clock was unused (no current timestamp arithmetic in the dispatch
        // path); kept absent until a startup sweeper (R6 Sprint 3) needs it.
        // ObjectMapper added (r2 fix W-3) so forceTimeoutPending's best-effort
        // fallback can parse step_output_json from already-committed rows.
        this.subAgentRegistry = subAgentRegistry;
        this.sessionService = sessionService;
        this.chatService = chatService;
        this.flywheelRunService = flywheelRunService;
        this.objectMapper = objectMapper;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Public API — run-level lifecycle (Plan §6 Step D + §2 Design 5 Rule 1)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Sprint 2 (r2 B1 fix): run-level entry point for orchestrator agents that
     * need a {@code REQUIRES_NEW} tx boundary around the parent row insert.
     *
     * <p>Plan §2 Design 5 Rule 1: the wrapping {@code REQUIRES_NEW} is
     * deliberate — when a caller is already inside an outer @Transactional
     * (e.g. a scheduler that fires several runs and wants per-run rollback
     * isolation), joining the outer tx would let one failed startRun roll back
     * the others. {@code REQUIRES_NEW} suspends the caller tx so each run
     * commits independently. Mirrors the OPT-REPORT pattern (Plan §1.5).
     *
     * <p>Signature mirrors {@link FlywheelRunService#startRun} (loopKind /
     * triggerSource / inputJson / agentId / windowDays) — wrapping rather than
     * forwarding new arg shapes keeps the executor's surface honest about
     * what FlywheelRunService actually accepts.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FlywheelRunEntity startRun(String loopKind, String triggerSource,
                                       Map<String, Object> inputJson, Long agentId,
                                       int windowDays) {
        return flywheelRunService.startRun(loopKind, triggerSource, inputJson, agentId, windowDays);
    }

    /**
     * Sprint 2 (r2 B1 fix): drives the parent run to a terminal state once
     * {@link #dispatchSubAgents} / {@link #awaitDispatch} have settled. The
     * orchestrator agent is expected to call this <i>after</i> it has
     * consumed the {@link StepResult} list (so it can also persist a
     * loop-specific summary if applicable).
     *
     * <p>Not {@code @Transactional} — delegates to
     * {@code FlywheelRunService.markCompleted / markError}, both of which
     * already own their @Transactional scopes. Wrapping here would just
     * extend the tx span across two service calls without gaining anything.
     */
    public FlywheelRunEntity finalizeRun(String parentRunId, List<StepResult> results) {
        boolean allSucceeded = results != null && !results.isEmpty()
                && results.stream().allMatch(r -> r != null && r.succeeded());
        if (allSucceeded) {
            return flywheelRunService.markCompleted(parentRunId, null, null);
        }
        String reason = summarizeFailures(results);
        return flywheelRunService.markError(parentRunId, reason);
    }

    private String summarizeFailures(List<StepResult> results) {
        if (results == null || results.isEmpty()) {
            return "no step results returned (worker pool may have crashed before any reported back)";
        }
        long failed = results.stream().filter(r -> r != null && !r.succeeded()).count();
        StringBuilder sb = new StringBuilder();
        sb.append(failed).append('/').append(results.size()).append(" workers failed");
        // Best-effort: include the first reason for operator triage.
        for (StepResult r : results) {
            if (r != null && !r.succeeded() && r.errorReason() != null) {
                sb.append(" (first: ").append(r.errorReason()).append(')');
                break;
            }
        }
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Public API — fan-out
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Fan out N workers in parallel and return a barrier future that
     * completes when every per-step future completes. The Tool layer calls
     * {@code aggregate.get(maxTimeout, SECONDS)} synchronously to block the
     * parent orchestrator's tool-use slot until results arrive.
     *
     * <p>Each worker:
     * <ol>
     *   <li>appendStep(runId, inputJson) → stepRunId,</li>
     *   <li>register a pending future in {@link #pendingSteps},</li>
     *   <li>dispatchOneWorker (Path B) — spawns a child SubAgent loop and
     *       backfills step.sub_agent_session_id.</li>
     * </ol>
     *
     * @param parentRunId  {@code FlywheelRunEntity#getId()} of the parent
     *                     orchestrator's run row
     * @param parentSession parent orchestrator session (used by the Path B
     *                     dispatch to inherit user id / depth / active root
     *                     trace id)
     * @param workers      list of worker specs; must be non-empty
     * @return aggregate barrier future
     */
    public CompletableFuture<List<StepResult>> dispatchSubAgents(
            String parentRunId, SessionEntity parentSession,
            List<OrchestratorWorkerSpec> workers) {
        if (parentRunId == null || parentRunId.isBlank()) {
            throw new IllegalArgumentException("parentRunId is required");
        }
        if (parentSession == null) {
            throw new IllegalArgumentException("parentSession is required");
        }
        if (workers == null || workers.isEmpty()) {
            throw new IllegalArgumentException("workers list must be non-empty");
        }

        List<String> stepRunIds = new ArrayList<>(workers.size());
        List<CompletableFuture<StepResult>> futures = new ArrayList<>(workers.size());
        for (OrchestratorWorkerSpec worker : workers) {
            String stepRunId = flywheelRunService.appendStep(parentRunId, worker.inputJson());
            CompletableFuture<StepResult> future = new CompletableFuture<>();
            pendingSteps.put(stepRunId, future);
            stepRunIds.add(stepRunId);
            futures.add(future);
            try {
                dispatchOneWorker(worker, stepRunId, parentSession);
            } catch (RuntimeException dispatchErr) {
                // Failure during the synchronous dispatch — complete the
                // future immediately with an error so the aggregate doesn't
                // hang. Listener path won't fire because no session was
                // spawned, and the step row sits at pending — promote it to
                // error here so the row state matches reality.
                log.error("OrchestratorAgentExecutor.dispatchSubAgents: dispatchOneWorker failed for "
                        + "stepRunId={} agentId={}", stepRunId, worker.agentId(), dispatchErr);
                try {
                    flywheelRunService.transitionStepStatus(stepRunId,
                            FlywheelRunStepEntity.STATUS_ERROR,
                            null, "dispatch_failed: " + dispatchErr.getMessage());
                } catch (RuntimeException txErr) {
                    log.warn("OrchestratorAgentExecutor.dispatchSubAgents: row error-write failed for stepRunId={}",
                            stepRunId, txErr);
                }
                completeStep(stepRunId, new StepResult(stepRunId,
                        FlywheelRunStepEntity.STATUS_ERROR,
                        null, "dispatch_failed: " + dispatchErr.getMessage()));
            }
        }

        log.info("OrchestratorAgentExecutor.dispatchSubAgents: parentRunId={} fanout={} stepRunIds={}",
                parentRunId, workers.size(), stepRunIds);

        @SuppressWarnings("rawtypes")
        CompletableFuture[] arr = futures.toArray(new CompletableFuture[0]);
        return CompletableFuture.allOf(arr).thenApply(v -> {
            List<StepResult> results = new ArrayList<>(futures.size());
            for (CompletableFuture<StepResult> f : futures) {
                results.add(f.getNow(null));
            }
            return results;
        });
    }

    /**
     * Synchronous helper combining {@link #dispatchSubAgents} + barrier wait.
     * The Tool layer calls this so its own {@code execute()} can return a
     * single payload to the LLM.
     *
     * <p>On timeout, force-completes any still-pending steps with
     * {@code status=error, errorReason="timeout"}, writes the DB rows
     * accordingly, and returns whatever results have arrived (still includes
     * the timed-out ones marked as error).
     *
     * @param timeoutSeconds upper bound across all workers; pass the max of
     *                       {@code worker.timeoutSeconds() + 60s buffer}
     */
    public List<StepResult> awaitDispatch(String parentRunId, SessionEntity parentSession,
                                           List<OrchestratorWorkerSpec> workers,
                                           long timeoutSeconds) {
        CompletableFuture<List<StepResult>> aggregate =
                dispatchSubAgents(parentRunId, parentSession, workers);
        try {
            return aggregate.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            log.warn("OrchestratorAgentExecutor.awaitDispatch: parentRunId={} timed out after {}s "
                    + "— force-completing pending steps as error", parentRunId, timeoutSeconds);
            // Find still-pending stepRunIds and force them error
            forceTimeoutPending(parentRunId);
            // Now aggregate should complete — wait briefly more for the
            // listener-driven completes to settle
            try {
                return aggregate.get(10, TimeUnit.SECONDS);
            } catch (Exception finalErr) {
                log.error("OrchestratorAgentExecutor.awaitDispatch: aggregate didn't settle after force-timeout for parentRunId={}",
                        parentRunId, finalErr);
                // Best-effort fallback: collect whatever StepResults landed.
                // r2 fix W-3: parse step_output_json so a worker that *did*
                // write back (Tool path committed) but whose listener didn't
                // get a chance to fire (e.g. async executor backlog) still
                // surfaces its outputJson to the parent orchestrator.
                List<StepResult> partial = new ArrayList<>();
                for (FlywheelRunStepEntity step :
                        flywheelRunService.listStepsByRunId(parentRunId)) {
                    partial.add(new StepResult(step.getId(), step.getStatus(),
                            parseStepOutput(step), step.getErrorReason()));
                }
                return partial;
            }
        } catch (Exception e) {
            log.error("OrchestratorAgentExecutor.awaitDispatch: parentRunId={} failed", parentRunId, e);
            throw new RuntimeException("Orchestrator dispatch failed: " + e.getMessage(), e);
        }
    }

    /**
     * Listener-facing API. Public because {@link WorkerCompletionListener} is
     * in a separate class but the {@link #pendingSteps} map stays private.
     *
     * <p>Idempotent: the second call (race between Tool-path and fallback-path
     * listeners) finds the future already removed and silently no-ops.
     */
    public void completeStep(String stepRunId, StepResult result) {
        if (stepRunId == null) return;
        CompletableFuture<StepResult> future = pendingSteps.remove(stepRunId);
        if (future != null) {
            future.complete(result);
            log.debug("OrchestratorAgentExecutor.completeStep: stepRunId={} status={}",
                    stepRunId, result == null ? "null-result" : result.status());
        }
        // else: late completion (already drained by another path, or timeout
        // already swept the future). Legal no-op.
    }

    /**
     * Visibility helper for tests + sweepers — counts in-flight pending
     * futures for assertion. Not part of the production hot path.
     */
    public int pendingStepCount() {
        return pendingSteps.size();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Path B dispatch — direct 3-service composition, intentionally NOT
    // routed through SubAgentTool (Plan §2 Design 2 W-1 fix).
    //
    // SubAgentTool.handleDispatch returns a textual SkillResult that would
    // force this framework path to parse log strings to extract childSessionId.
    // Worse, childSessionId backfill into step_run wouldn't happen → fallback
    // listener path can never find the matching step. So we replicate the
    // ~40-line dispatch flow here directly. Trade-off documented in Plan §8 R8.
    // ─────────────────────────────────────────────────────────────────────

    private void dispatchOneWorker(OrchestratorWorkerSpec worker, String stepRunId,
                                   SessionEntity parentSession) {
        // 1. Register the SubAgentRun row (depth + concurrency guards). May
        //    throw IllegalStateException — caller catches + writes step error.
        SubAgentRegistry.SubAgentRun run = subAgentRegistry.registerRun(
                parentSession, worker.agentId(), worker.agentName(), worker.task());

        // 2. Create the child session with the same activeRootTraceId as parent
        //    (OBS-4 §2.5 INV-4 — child inherits parent's trace root).
        SessionEntity child = sessionService.createSubSession(
                parentSession, worker.agentId(), run.runId);
        child.setActiveRootTraceId(parentSession.getActiveRootTraceId());
        sessionService.saveSession(child);

        // 3. Reverse-link child sessionId onto the run row (SubAgent registry's
        //    own bookkeeping).
        subAgentRegistry.attachChildSession(run.runId, child.getId());

        // 4. Backfill step_run.sub_agent_session_id — REQUIRED so the
        //    SessionLoopFinishedEvent fallback listener can find this step.
        //    Without this, fallback path silently no-ops (the lookup by
        //    sub_agent_session_id misses) → worker that doesn't call
        //    RecordOrchestrationStepResult would never unblock the aggregator.
        flywheelRunService.attachStepSubAgentSession(stepRunId, child.getId());

        // 5. Kick off child loop. preserveActiveRoot=true mirrors
        //    SubAgentTool.handleDispatch — child's first turn inherits parent's
        //    active_root_trace_id (already set on child in step 2).
        chatService.chatAsync(child.getId(), worker.task(),
                parentSession.getUserId(), true);

        log.info("OrchestratorAgentExecutor.dispatchOneWorker: stepRunId={} childSessionId={} "
                + "agentId={} runId={}", stepRunId, child.getId(), worker.agentId(), run.runId);
    }

    /**
     * Iterate pending steps for the parent run and force-error any still-
     * pending after the aggregate timeout fired. Writes DB rows + completes
     * the in-memory futures (so aggregate's allOf eventually settles).
     */
    private void forceTimeoutPending(String parentRunId) {
        for (FlywheelRunStepEntity step : flywheelRunService.listStepsByRunId(parentRunId)) {
            if (!FlywheelRunStepEntity.STATUS_PENDING.equals(step.getStatus())) {
                continue;
            }
            String stepRunId = step.getId();
            if (!pendingSteps.containsKey(stepRunId)) {
                continue; // already drained by another path
            }
            try {
                flywheelRunService.transitionStepStatus(stepRunId,
                        FlywheelRunStepEntity.STATUS_ERROR, null, "timeout");
            } catch (RuntimeException txErr) {
                log.warn("OrchestratorAgentExecutor.forceTimeoutPending: row write failed for stepRunId={}",
                        stepRunId, txErr);
            }
            completeStep(stepRunId, new StepResult(stepRunId,
                    FlywheelRunStepEntity.STATUS_ERROR, null, "timeout"));
        }
    }

    /**
     * r2 fix W-3: parse a step's {@code step_output_json} column (JSONB
     * stored as String on the Java side) into a {@link JsonNode}. Swallows
     * parse failures with a warn — better to surface a partial StepResult
     * with null outputJson than to fail the whole barrier on one bad row.
     */
    private JsonNode parseStepOutput(FlywheelRunStepEntity step) {
        String raw = step.getStepOutputJson();
        if (raw == null || raw.isBlank()) return null;
        try {
            return objectMapper.readTree(raw);
        } catch (Exception parseErr) {
            log.warn("OrchestratorAgentExecutor.parseStepOutput: invalid JSON in step_output_json "
                    + "for stepRunId={}: {}", step.getId(), parseErr.getMessage());
            return null;
        }
    }
}
