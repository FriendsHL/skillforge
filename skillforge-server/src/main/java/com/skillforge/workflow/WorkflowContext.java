package com.skillforge.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.flywheel.run.FlywheelRunService;
import com.skillforge.workflow.journal.JournalCache;
import com.skillforge.workflow.sandbox.BudgetTracker;
import com.skillforge.workflow.ws.WorkflowWsBroadcaster;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-run workflow execution context. Lives for the duration of one
 * {@code evaluateString} call and is shared between the host bindings
 * ({@code HostAgent} / {@code HostParallel} / {@code HostPhase} / {@code HostLog}).
 *
 * <p>Threading: mutated only on the single workflow thread (Rhino is bound to
 * one thread). {@code nextStepIndex} is an {@link AtomicInteger} both for
 * defensive safety and because it is the deterministic step-ordering primitive
 * for Sprint-2 journal-replay (plan §3.2): the index is assigned <em>at the
 * moment {@code agent()} is invoked</em> (single-threaded, in program order),
 * NOT when the offloaded {@code engine.run} completes.
 */
public final class WorkflowContext {

    private final String runId;
    private final Map<String, Object> args;
    private final BudgetTracker budget;

    private final AtomicInteger nextStepIndex = new AtomicInteger(0);

    /**
     * True while {@code parallel()/pipeline()} is evaluating its thunks: in this
     * mode {@code agent()} offloads {@code engine.run} to the sub-agent executor
     * and returns a placeholder instead of blocking (plan §2.1). Set/read only
     * on the workflow thread; {@code volatile} for visibility hygiene.
     */
    private volatile boolean inParallelCollect = false;

    // Observability captured for assertions / WS broadcast (spike: in-memory).
    private final List<String> phases = Collections.synchronizedList(new ArrayList<>());
    private final List<String> logs = Collections.synchronizedList(new ArrayList<>());
    private final List<String> invokeThreadNames = Collections.synchronizedList(new ArrayList<>());

    /**
     * Optional WS broadcaster for {@code phase()}/{@code log()} events. Null in
     * pure unit tests (spike) — then events are only recorded in-memory. Set by
     * {@code WorkflowRunnerService} for real runs.
     */
    private WorkflowWsBroadcaster broadcaster;

    /**
     * Sprint 2: host services the {@code humanApprove()} / {@code ctx} bindings
     * reach through the context (so {@code WorkflowEvaluator.evaluate}'s 4-arg
     * signature stays stable for the spike tests). Null in pure unit tests that
     * never exercise those bindings; set by {@code WorkflowRunnerService} for
     * real runs.
     */
    private FlywheelRunService flywheelRunService;
    private ObjectMapper objectMapper;

    /**
     * Sprint 2 (chunk 2): the journal cache the {@code agent()} /
     * {@code humanApprove()} resume short-circuits read first-run results from.
     * Null in pure unit tests that never resume; set by
     * {@code WorkflowRunnerService} for real runs.
     */
    private JournalCache journalCache;

    // ── Chunk 2 journal-replay state ──
    // First run: isResuming=false → replayComplete=true → phase()/log() broadcast
    // normally; humanApprove() takes the first-pause path. Resume: isResuming=true,
    // resumeFrontierIndex=K (the approved gate's stepIndex), replayComplete=false
    // until humanApprove() reaches the frontier and flips it true — that boundary
    // gates WS replay suppression (recordPhase/recordLog below).
    private boolean resuming = false;
    private int resumeFrontierIndex = -1;
    // Workflow-thread-only today (Rhino is single-threaded), but marked volatile —
    // aligned with inParallelCollect — so a future cross-thread reader (e.g. a
    // broadcast helper invoked off the workflow thread) can never observe a stale
    // value of the WS replay-suppression gate. (r1 code-W3.)
    private volatile boolean replayComplete = true;

    public WorkflowContext(String runId, Map<String, Object> args, BudgetTracker budget) {
        this.runId = runId;
        this.args = args != null ? args : Collections.emptyMap();
        this.budget = budget;
    }

    public void setBroadcaster(WorkflowWsBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    public WorkflowWsBroadcaster getBroadcaster() {
        return broadcaster;
    }

    public void setFlywheelRunService(FlywheelRunService flywheelRunService) {
        this.flywheelRunService = flywheelRunService;
    }

    public FlywheelRunService getFlywheelRunService() {
        return flywheelRunService;
    }

    public void setObjectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    public void setJournalCache(JournalCache journalCache) {
        this.journalCache = journalCache;
    }

    public JournalCache getJournalCache() {
        return journalCache;
    }

    // ── Chunk 2 seam accessors ──
    public boolean isResuming() {
        return resuming;
    }

    public void setResuming(boolean resuming) {
        this.resuming = resuming;
    }

    public int getResumeFrontierIndex() {
        return resumeFrontierIndex;
    }

    public void setResumeFrontierIndex(int resumeFrontierIndex) {
        this.resumeFrontierIndex = resumeFrontierIndex;
    }

    public boolean isReplayComplete() {
        return replayComplete;
    }

    public void setReplayComplete(boolean replayComplete) {
        this.replayComplete = replayComplete;
    }

    public String getRunId() {
        return runId;
    }

    public Map<String, Object> getArgs() {
        return args;
    }

    public BudgetTracker getBudget() {
        return budget;
    }

    /** Assigns the next deterministic step index (invoke-order). */
    public int nextStepIndex() {
        return nextStepIndex.getAndIncrement();
    }

    public boolean isInParallelCollect() {
        return inParallelCollect;
    }

    public void setInParallelCollect(boolean v) {
        this.inParallelCollect = v;
    }

    public void recordPhase(String title) {
        phases.add(title);
        // Replay suppression (plan §2.7): while re-running JS up to the resume
        // frontier (replayComplete=false) we must NOT re-broadcast phase events —
        // the dashboard already saw them on the first run. After the frontier
        // humanApprove() flips replayComplete=true, live phases broadcast again.
        if (broadcaster != null && replayComplete) {
            broadcaster.phaseStarted(runId, title);
        }
    }

    public void recordLog(String message) {
        logs.add(message);
        if (broadcaster != null && replayComplete) {
            broadcaster.logged(runId, message);
        }
    }

    public void recordInvokeThread(String threadName) {
        invokeThreadNames.add(threadName);
    }

    public List<String> getPhases() {
        return phases;
    }

    public List<String> getLogs() {
        return logs;
    }

    public List<String> getInvokeThreadNames() {
        return invokeThreadNames;
    }
}
