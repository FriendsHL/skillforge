package com.skillforge.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.flywheel.run.FlywheelRunEntity;
import com.skillforge.server.flywheel.run.FlywheelRunService;
import com.skillforge.server.flywheel.run.FlywheelRunStepEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.service.SessionService;
import com.skillforge.workflow.exception.WorkflowDefinitionChangedException;
import com.skillforge.workflow.exception.WorkflowNotFoundException;
import com.skillforge.workflow.exception.WorkflowNotPausedException;
import com.skillforge.workflow.exception.WorkflowAlreadyRunningException;
import com.skillforge.workflow.exception.WorkflowPausedException;
import com.skillforge.workflow.journal.JournalCache;
import com.skillforge.workflow.sandbox.BudgetTracker;
import com.skillforge.workflow.sandbox.L1SandboxFactory;
import com.skillforge.workflow.ws.WorkflowWsBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Task G: the workflow run orchestrator. Public {@link #startRun} acquires the
 * per-name lock, opens a {@code t_flywheel_run} row (loop_kind=workflow) + a
 * depth-0 anchor session, then dispatches the JS body to the dedicated workflow
 * executor and returns the run id immediately (async).
 *
 * <p>The async body: build a {@link WorkflowContext} (budget + args) → create the
 * per-run {@link WorkflowAgentInvoker} → {@link WorkflowEvaluator#evaluate} (await
 * stripping + IIFE wrap + L1 sandbox) → {@code markCompleted} / {@code markError}
 * → always {@code release} the lock.
 *
 * <p>Pool separation (plan §6): the JS body runs on {@code workflowExecutor}; the
 * blocking {@code agent()} {@code engine.run} calls offloaded by
 * {@code HostParallel} run on {@code workflowSubAgentExecutor}. Separate pools
 * avoid nested-pool deadlock (the workflow thread barrier-joins on sub-agent
 * futures).
 */
@Service
public class WorkflowRunnerService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunnerService.class);

    /** loop_kind for workflow runs (V126 allow-listed it on t_flywheel_run). */
    static final String LOOP_KIND_WORKFLOW = "workflow";

    private final WorkflowDefinitionRegistry registry;
    private final FlywheelRunService flywheelRunService;
    private final SessionService sessionService;
    private final AgentRepository agentRepository;
    private final WorkflowAgentInvokerFactory invokerFactory;
    private final WorkflowToolInvokerFactory toolInvokerFactory;
    private final ConsolidationLock consolidationLock;
    private final WorkflowWsBroadcaster wsBroadcaster;
    private final ObjectMapper objectMapper;
    private final JournalCache journalCache;
    private final Clock clock;
    private final ExecutorService workflowExecutor;
    private final ExecutorService workflowSubAgentExecutor;
    /**
     * AUTOEVOLVE-CLOSE-LOOP P1 — wall-clock budget for {@code loop_kind=evolve}
     * runs. The generic {@link BudgetTracker#DEFAULT_TIMEOUT_NANOS} (30 min) is
     * too tight for a multi-iteration evolve loop (~10-19 min/iteration with
     * blocking A/B polls), so evolve runs get a much larger budget. Other workflow
     * kinds keep the 30-min default.
     */
    private final long evolveTimeoutNanos;

    private final L1SandboxFactory sandboxFactory = new L1SandboxFactory();
    private final WorkflowEvaluator evaluator = new WorkflowEvaluator(sandboxFactory);

    /**
     * Name of the agent the workflow run + anchor session are attributed to.
     * Sprint-1: a seeded system agent (FK to t_agent is required by
     * t_flywheel_run.agent_id). Configurable so deployments / tests can override.
     */
    private final String anchorAgentName;

    public WorkflowRunnerService(WorkflowDefinitionRegistry registry,
                                 FlywheelRunService flywheelRunService,
                                 SessionService sessionService,
                                 AgentRepository agentRepository,
                                 WorkflowAgentInvokerFactory invokerFactory,
                                 WorkflowToolInvokerFactory toolInvokerFactory,
                                 ConsolidationLock consolidationLock,
                                 WorkflowWsBroadcaster wsBroadcaster,
                                 ObjectMapper objectMapper,
                                 JournalCache journalCache,
                                 Clock clock,
                                 @Qualifier("workflowExecutor") ExecutorService workflowExecutor,
                                 @Qualifier("workflowSubAgentExecutor") ExecutorService workflowSubAgentExecutor,
                                 @Value("${skillforge.workflow.anchor-agent-name:session-annotator}")
                                 String anchorAgentName,
                                 @Value("${skillforge.evolve.workflow-timeout-minutes:360}")
                                 long evolveTimeoutMinutes) {
        this.registry = registry;
        this.flywheelRunService = flywheelRunService;
        this.sessionService = sessionService;
        this.agentRepository = agentRepository;
        this.invokerFactory = invokerFactory;
        this.toolInvokerFactory = toolInvokerFactory;
        this.consolidationLock = consolidationLock;
        this.wsBroadcaster = wsBroadcaster;
        this.objectMapper = objectMapper;
        this.journalCache = journalCache;
        this.clock = clock;
        this.workflowExecutor = workflowExecutor;
        this.workflowSubAgentExecutor = workflowSubAgentExecutor;
        this.anchorAgentName = anchorAgentName;
        this.evolveTimeoutNanos = java.util.concurrent.TimeUnit.MINUTES.toNanos(
                evolveTimeoutMinutes > 0 ? evolveTimeoutMinutes : 360L);
    }

    /**
     * Starts a workflow run.
     *
     * @return the {@code t_flywheel_run} id
     * @throws WorkflowNotFoundException       no definition registered under the name
     * @throws WorkflowAlreadyRunningException a run for this name is already in flight
     */
    public String startRun(String workflowName, Map<String, Object> args, Long userId) {
        return startRun(workflowName, args, userId, LOOP_KIND_WORKFLOW);
    }

    /**
     * AUTOEVOLVE-CLOSE-LOOP P1 — start a workflow run under an explicit
     * {@code loopKind}. The default {@link #startRun(String, Map, Long)} uses
     * {@code workflow}; the evolve-loop passes {@code evolve} so the run row is a
     * {@code loop_kind=evolve} {@code FlywheelRun} (and thus participates in
     * {@code RecordIteration} / {@code EvolveReadService} / {@code hasActiveEvolveRun}
     * exactly like the legacy orchestrator run — R1, spike-verified). The workflow
     * engine itself (resume / journal / step record) is keyed on {@code runId}, not
     * {@code loop_kind}, so the engine works identically for either kind.
     */
    public String startRun(String workflowName, Map<String, Object> args, Long userId, String loopKind) {
        WorkflowDefinition def = registry.findByName(workflowName)
                .orElseThrow(() -> new WorkflowNotFoundException(workflowName));
        return startRun(def, args, userId, loopKind);
    }

    /**
     * Starts a workflow run from an already-parsed {@link WorkflowDefinition},
     * bypassing the registry name lookup. Used by the agent-facing
     * {@code RunWorkflow} tool's inline mode, where the JS source (with its
     * {@code export const meta = {...}} block) is parsed on the fly via
     * {@link WorkflowDefinitionRegistry#parseInline(String)} rather than loaded
     * from the classpath.
     *
     * <p>Locking: keyed on {@code def.name()} — the SAME per-name lock the
     * registered-name path uses. An inline definition whose {@code meta.name}
     * collides with a registered workflow (or another in-flight inline run of the
     * same name) is correctly serialized: a collision throws
     * {@link WorkflowAlreadyRunningException}, never runs two bodies under one
     * name concurrently. The inline source still runs through
     * {@link WorkflowEvaluator#evaluate} → the same L1 sandbox as named runs.
     *
     * @return the {@code t_flywheel_run} id
     * @throws WorkflowAlreadyRunningException a run for {@code def.name()} is already in flight
     */
    /**
     * Best-effort extraction of {@code args.agentId} (String "5" or Number 5) so a
     * workflow run can be attributed to the agent it is about. Returns null when
     * absent / unparseable / non-positive — caller falls back to the anchor agent.
     */
    static Long extractAgentId(Map<String, Object> args) {
        if (args == null) {
            return null;
        }
        Object raw = args.get("agentId");
        if (raw == null) {
            return null;
        }
        try {
            long id = raw instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(raw).trim());
            return id > 0 ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public String startRun(WorkflowDefinition def, Map<String, Object> args, Long userId) {
        return startRun(def, args, userId, LOOP_KIND_WORKFLOW);
    }

    /**
     * AUTOEVOLVE-CLOSE-LOOP P1 — {@code loopKind}-parameterised variant of
     * {@link #startRun(WorkflowDefinition, Map, Long)}. See
     * {@link #startRun(String, Map, Long, String)} for why the loop kind is just a
     * row attribute the workflow engine never branches on.
     */
    public String startRun(WorkflowDefinition def, Map<String, Object> args, Long userId, String loopKind) {
        String workflowName = def.name();
        String effectiveLoopKind = (loopKind == null || loopKind.isBlank()) ? LOOP_KIND_WORKFLOW : loopKind;

        if (!consolidationLock.tryAcquire(workflowName)) {
            throw new WorkflowAlreadyRunningException(workflowName);
        }

        // runId tracked outside the try so the catch can mark a created-but-never-
        // dispatched run as errored (e.g. the executor rejects the body).
        String runId = null;
        try {
            AgentEntity anchorAgent = agentRepository.findFirstByName(anchorAgentName)
                    .orElseThrow(() -> new IllegalStateException(
                            "Workflow anchor agent not found: " + anchorAgentName));

            Map<String, Object> inputJson = new LinkedHashMap<>();
            inputJson.put("workflow_name", workflowName);
            inputJson.put("workflow_args", args == null ? Map.of() : args);
            inputJson.put("sourceHash", def.sourceHash());

            // Attribute the run to the agent it is ABOUT when the args name one
            // (e.g. opt-report's args.agentId = the target agent), falling back to
            // the workflow anchor agent. Without this, an opt-report run produced by
            // RunWorkflow carries the anchor agent's id, so downstream readers
            // (GetOptReport's expectedAgentId pin, OptReportToEventBridge's
            // event.agentId) would mis-attribute the report to the anchor agent.
            Long argAgentId = extractAgentId(args);
            Long runAgentId = argAgentId != null ? argAgentId : anchorAgent.getId();

            FlywheelRunEntity run = flywheelRunService.startRun(
                    effectiveLoopKind,
                    FlywheelRunEntity.TRIGGER_SOURCE_USER_MANUAL,
                    inputJson,
                    runAgentId,
                    1);
            runId = run.getId();

            // Depth-0 anchor session: parent for every agent() worker sub-session.
            SessionEntity anchor = sessionService.createSession(userId, anchorAgent.getId());
            flywheelRunService.attachGeneratorSession(runId, anchor.getId());

            // execute() is inside the try: a RejectedExecutionException (queue full /
            // pool shut down) must release the lock and mark the run errored,
            // otherwise the lock leaks and the run is stuck 'running' forever.
            final String fRunId = runId;
            final SessionEntity fAnchor = anchor;
            final Map<String, Object> fArgs = args;
            final String fLoopKind = effectiveLoopKind;
            workflowExecutor.execute(() ->
                    runWorkflowBody(fRunId, def, fArgs, userId, fAnchor, workflowName, fLoopKind));
            return runId;
        } catch (RuntimeException e) {
            consolidationLock.release(workflowName);
            if (runId != null) {
                try {
                    flywheelRunService.markError(runId, "workflow dispatch failed: " + e.getMessage());
                } catch (RuntimeException markEx) {
                    log.error("WorkflowRunnerService: failed to mark run {} errored after dispatch failure: {}",
                            runId, markEx.getMessage());
                }
            }
            throw e;
        }
    }

    /**
     * The async workflow body (first-run entry). Package-private so an IT can
     * drive it synchronously without the executor when asserting DB state.
     */
    void runWorkflowBody(String runId, WorkflowDefinition def, Map<String, Object> args,
                         Long userId, SessionEntity anchor, String workflowName) {
        runWorkflowBody(runId, def, args, userId, anchor, workflowName, LOOP_KIND_WORKFLOW, false, -1);
    }

    void runWorkflowBody(String runId, WorkflowDefinition def, Map<String, Object> args,
                         Long userId, SessionEntity anchor, String workflowName, String loopKind) {
        runWorkflowBody(runId, def, args, userId, anchor, workflowName, loopKind, false, -1);
    }

    /**
     * The async workflow body. On {@code isResuming=true} the JS is re-run from
     * the top with the journal cache wired in: {@code agent()} calls before
     * {@code frontier} short-circuit to cached results and the gate(s) at/before
     * the frontier return recorded decisions, so execution rejoins live work
     * right after the just-approved gate (plan §2.5/§2.6).
     */
    void runWorkflowBody(String runId, WorkflowDefinition def, Map<String, Object> args,
                         Long userId, SessionEntity anchor, String workflowName, String loopKind,
                         boolean isResuming, int frontier) {
        try {
            long timeoutNanos = FlywheelRunEntity.LOOP_KIND_EVOLVE.equals(loopKind)
                    ? evolveTimeoutNanos
                    : BudgetTracker.DEFAULT_TIMEOUT_NANOS;
            BudgetTracker budget = new BudgetTracker(
                    BudgetTracker.DEFAULT_INSTRUCTION_CAP,
                    BudgetTracker.DEFAULT_AGENT_CALL_CAP,
                    System.nanoTime(),
                    timeoutNanos);
            WorkflowContext ctx = new WorkflowContext(runId, args, budget);
            ctx.setBroadcaster(wsBroadcaster);
            // Sprint 2: humanApprove() / ctx bindings reach these through the context.
            ctx.setFlywheelRunService(flywheelRunService);
            ctx.setObjectMapper(objectMapper);
            ctx.setJournalCache(journalCache);
            if (isResuming) {
                // replayComplete starts false → phase()/log() suppressed until the
                // frontier gate flips it true (plan §2.7).
                ctx.setResuming(true);
                ctx.setResumeFrontierIndex(frontier);
                ctx.setReplayComplete(false);
            }
            WorkflowAgentInvoker invoker = invokerFactory.create(runId, anchor, userId);
            // AUTOEVOLVE-CLOSE-LOOP P1: per-run tool() invoker for the deterministic
            // tool nodes. Created here (per run) like the agent invoker.
            WorkflowToolInvoker toolInvoker = toolInvokerFactory.create(runId, userId);

            Object result = evaluator.evaluate(
                    def.jsSource(), ctx, invoker, toolInvoker, workflowSubAgentExecutor);

            String summaryJson = serializeResult(result);
            flywheelRunService.markCompleted(runId, null, summaryJson);
            log.info("WorkflowRunnerService: run {} ('{}') completed{}",
                    runId, workflowName, isResuming ? " (resumed)" : "");
        } catch (WorkflowPausedException pause) {
            // NOT an error: humanApprove() already transitioned the run to
            // status=paused and persisted the pending gate step. Leave the run
            // parked (do NOT markError) — the finally releases the per-name lock
            // so a later approve can re-acquire it and resume (chunk 2).
            log.info("WorkflowRunnerService: run {} ('{}') paused for human approval (stepIndex={})",
                    runId, workflowName, pause.getStepIndex());
        } catch (Exception e) {
            log.warn("WorkflowRunnerService: run {} ('{}') errored: {}", runId, workflowName, e.getMessage(), e);
            try {
                flywheelRunService.markError(runId, e.getMessage());
            } catch (RuntimeException markEx) {
                log.error("WorkflowRunnerService: failed to mark run {} errored: {}", runId, markEx.getMessage());
            }
        } finally {
            consolidationLock.release(workflowName);
        }
    }

    /**
     * Resumes a workflow run parked on a {@code humanApprove()} gate (Task F,
     * plan §4.2/§4.3). Records the operator's decision on the frontier gate step,
     * transitions the run {@code paused → running}, re-acquires the per-name lock,
     * and re-dispatches the JS body with journal-replay enabled.
     *
     * <p>Validation (all → caller maps to 409/404):
     * <ul>
     *   <li>run must exist ({@link WorkflowNotFoundException} → 404);</li>
     *   <li>run must be {@code paused} ({@link WorkflowNotPausedException} → 409);</li>
     *   <li>the run's {@code sourceHash} must still match the live definition
     *       ({@link WorkflowDefinitionChangedException} → 409 — replaying a changed
     *       script would break step-index alignment);</li>
     *   <li>another run of the same name must not hold the lock
     *       ({@link WorkflowAlreadyRunningException} → 409).</li>
     * </ul>
     *
     * @param approved   the operator's decision (true=approved, false=rejected)
     * @param reason     optional free-text reason
     * @param reviewerId who approved (free-text; single-tenant dogfood — no role gating)
     */
    public void resume(String runId, boolean approved, String reason, String reviewerId) {
        FlywheelRunEntity run = flywheelRunService.findById(runId)
                .orElseThrow(() -> new WorkflowNotFoundException("workflow run not found: " + runId));
        if (!FlywheelRunEntity.STATUS_PAUSED.equals(run.getStatus())) {
            throw new WorkflowNotPausedException(runId, run.getStatus());
        }

        JsonNode input = parseInput(run.getInputJson(), runId);
        String workflowName = input.path("workflow_name").asText(null);
        if (workflowName == null || workflowName.isBlank()) {
            throw new IllegalStateException("paused run " + runId + " has no workflow_name in input_json");
        }
        WorkflowDefinition def = registry.findByName(workflowName)
                .orElseThrow(() -> new WorkflowNotFoundException(workflowName));

        String runSourceHash = input.path("sourceHash").asText(null);
        if (runSourceHash != null && !runSourceHash.isBlank()
                && !runSourceHash.equals(def.sourceHash())) {
            throw new WorkflowDefinitionChangedException(runId);
        }

        Map<String, Object> args = extractArgs(input);

        FlywheelRunStepEntity gate = flywheelRunService.findPendingApproveStep(runId)
                .orElseThrow(() -> new IllegalStateException(
                        "paused run " + runId + " has no pending human_approve gate step"));
        int frontier = gate.getStepIndex() == null ? -1 : gate.getStepIndex();

        SessionEntity anchor = sessionService.getSession(run.getGeneratorSessionId());
        Long userId = anchor.getUserId();

        // Re-acquire the per-name lock the pause released. A concurrent run of the
        // same name → 409 (do NOT write the decision / transition yet).
        if (!consolidationLock.tryAcquire(workflowName)) {
            throw new WorkflowAlreadyRunningException(workflowName);
        }
        try {
            // 1+2) Record the decision on the gate step AND transition paused →
            //    running ATOMICALLY (r1 java-W2). A single transaction so a partial
            //    failure can't wedge the run (gate completed but run still paused →
            //    findPendingApproveStep empty → un-retryable). The gate must be
            //    completed BEFORE replay so the frontier humanApprove() finds the
            //    recorded decision in the journal; the running transition fires the
            //    status WS event the dashboard reacts to.
            ObjectNode decisionJson = buildDecision(approved, reason, reviewerId);
            flywheelRunService.recordApproveDecisionAndResume(gate.getId(), runId, decisionJson);
            // 3) Re-dispatch with journal-replay enabled.
            final Long fUserId = userId;
            final SessionEntity fAnchor = anchor;
            final Map<String, Object> fArgs = args;
            final int fFrontier = frontier;
            final String fLoopKind = run.getLoopKind();
            workflowExecutor.execute(() -> runWorkflowBody(
                    runId, def, fArgs, fUserId, fAnchor, workflowName, fLoopKind, true, fFrontier));
            log.info("WorkflowRunnerService: resume dispatched for run {} ('{}') approved={} frontier={}",
                    runId, workflowName, approved, frontier);
        } catch (RuntimeException e) {
            consolidationLock.release(workflowName);
            try {
                flywheelRunService.markError(runId, "workflow resume dispatch failed: " + e.getMessage());
            } catch (RuntimeException markEx) {
                log.error("WorkflowRunnerService: failed to mark run {} errored after resume failure: {}",
                        runId, markEx.getMessage());
            }
            throw e;
        }
    }

    private JsonNode parseInput(String inputJson, String runId) {
        try {
            return objectMapper.readTree(inputJson == null || inputJson.isBlank() ? "{}" : inputJson);
        } catch (Exception e) {
            throw new IllegalStateException("paused run " + runId + " has unparseable input_json", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractArgs(JsonNode input) {
        JsonNode argsNode = input.get("workflow_args");
        if (argsNode == null || argsNode.isNull() || !argsNode.isObject()) {
            return Map.of();
        }
        return objectMapper.convertValue(argsNode, Map.class);
    }

    private ObjectNode buildDecision(boolean approved, String reason, String reviewerId) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("approved", approved);
        node.put("reviewerId", reviewerId == null ? "" : reviewerId);
        if (reason != null) {
            node.put("reason", reason);
        }
        node.put("decisionAt", clock.instant().toString());
        return node;
    }

    /**
     * Serialize a workflow's JS return value into {@code summary_json} (Sprint 3
     * soft-point ②). A structured return ({@code Map}/{@code List} — the common
     * case, e.g. OPT-REPORT's {@code return { summary, ... }}) is written as
     * real structured JSON via the Spring-managed {@link ObjectMapper}
     * (JavaTimeModule configured — java.md footgun #1), so downstream consumers
     * (AC-8 {@code topIssues} query / FE) can read nested fields. A scalar /
     * String / null return keeps the legacy {@code {"result": "..."}} wrapper for
     * back-compat. Pre-Sprint-3 this method String.valueOf'd everything, turning
     * a Map into a {@code {"result":"{summary={...}}"}} junk string.
     *
     * @throws IllegalStateException if serialization fails — the caller
     *         ({@link #runWorkflowBody}) must let this propagate to its
     *         {@code catch(Exception)} → {@code markError}, so a serialization
     *         failure transitions the run to {@code error} rather than silently
     *         marking it {@code completed} with an empty {@code "{}"} summary.
     */
    String serializeResult(Object result) {
        try {
            if (result instanceof Map<?, ?> || result instanceof Iterable<?>) {
                return objectMapper.writeValueAsString(result);
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("result", result == null ? null : String.valueOf(result));
            return objectMapper.writeValueAsString(summary);
        } catch (Exception e) {
            // Do NOT swallow to "{}" — that marks the run completed with an empty
            // summary (AC-8 topIssues query returns empty, FE shows a blank report
            // with no error). Re-throw so runWorkflowBody's catch(Exception) hits
            // markError and the run transitions to 'error' instead of falsely
            // 'completed' (Sprint 3 r1 code-W1 / java-W2).
            log.warn("WorkflowRunnerService.serializeResult: failed to serialize result type {}: {}",
                    result == null ? "null" : result.getClass().getName(), e.getMessage());
            throw new IllegalStateException(
                    "serializeResult failed for type "
                            + (result == null ? "null" : result.getClass().getName())
                            + ": " + e.getMessage(), e);
        }
    }
}
