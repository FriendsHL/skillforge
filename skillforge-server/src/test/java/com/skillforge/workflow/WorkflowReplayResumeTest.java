package com.skillforge.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.flywheel.run.FlywheelRunService;
import com.skillforge.workflow.bindings.JsConversions;
import com.skillforge.workflow.exception.WorkflowPausedException;
import com.skillforge.workflow.journal.JournalCache;
import com.skillforge.workflow.sandbox.BudgetTracker;
import com.skillforge.workflow.sandbox.L1SandboxFactory;
import com.skillforge.workflow.ws.WorkflowWsBroadcaster;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task F — journal-replay resume correctness, driven purely through
 * {@link WorkflowEvaluator} with an in-memory {@link JournalCache} + a counting
 * invoker (no DB / Spring). Proves the determinism the plan (§2.2) relies on:
 *
 * <ul>
 *   <li>on resume, {@code agent()} calls before the frontier short-circuit to the
 *       journal — the invoker is never re-run for them (no token spend);</li>
 *   <li>the frontier {@code humanApprove()} returns its recorded decision and
 *       flips {@code replayComplete}, so live work resumes right after it;</li>
 *   <li>multi-gate: each resume advances the frontier, and steps written on a
 *       prior resume are themselves cache-hit on the next;</li>
 *   <li>WS suppression: {@code phase()/log()} replayed before the frontier do NOT
 *       re-broadcast; those after it (live) do.</li>
 * </ul>
 */
@DisplayName("Workflow journal-replay resume")
class WorkflowReplayResumeTest {

    private WorkflowEvaluator evaluator;
    private ExecutorService exec;
    private final ObjectMapper om = new ObjectMapper();

    private FlywheelRunService runService;
    private FakeJournal journal;
    private final List<Integer> invokerCalls = new ArrayList<>();
    private WorkflowAgentInvoker invoker;

    @BeforeEach
    void setUp() {
        evaluator = new WorkflowEvaluator(new L1SandboxFactory());
        exec = Executors.newSingleThreadExecutor();
        runService = mock(FlywheelRunService.class);
        // humanApprove() first-pause path: appendStep returns a gate step id,
        // pauseRun is a no-op (state is asserted via the thrown exception).
        lenient().when(runService.appendStep(anyString(), anyString(), anyString(), anyInt()))
                .thenAnswer(inv -> "gate-" + inv.getArgument(3));
        journal = new FakeJournal();
        invokerCalls.clear();
        // Counting invoker: records the stepIndex it ran and "persists" the result
        // into the journal (mimicking DefaultWorkflowAgentInvoker's step write).
        invoker = (prompt, opts, stepIndex) -> {
            invokerCalls.add(stepIndex);
            String resp = "resp-" + stepIndex;
            journal.agent.put(stepIndex, resp);
            return resp;
        };
    }

    @AfterEach
    void tearDown() {
        exec.shutdownNow();
    }

    /** Result-or-pause carrier for one evaluation. */
    private record RunOutcome(Object result, Integer pausedAtStepIndex) {
        boolean paused() {
            return pausedAtStepIndex != null;
        }
    }

    private RunOutcome run(String body, boolean resuming, int frontier, WorkflowWsBroadcaster bc) {
        BudgetTracker budget = new BudgetTracker(
                BudgetTracker.DEFAULT_INSTRUCTION_CAP,
                BudgetTracker.DEFAULT_AGENT_CALL_CAP,
                System.nanoTime(),
                BudgetTracker.DEFAULT_TIMEOUT_NANOS);
        WorkflowContext ctx = new WorkflowContext("run-1", Map.of(), budget);
        ctx.setFlywheelRunService(runService);
        ctx.setObjectMapper(om);
        ctx.setJournalCache(journal);
        ctx.setBroadcaster(bc);
        if (resuming) {
            ctx.setResuming(true);
            ctx.setResumeFrontierIndex(frontier);
            ctx.setReplayComplete(false);
        }
        try {
            Object result = evaluator.evaluate(body, ctx, invoker, exec);
            return new RunOutcome(result, null);
        } catch (WorkflowPausedException pause) {
            return new RunOutcome(null, pause.getStepIndex());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object jsResult) {
        return (Map<String, Object>) JsConversions.jsToJava(jsResult);
    }

    private void approve(int stepIndex, boolean approved) {
        journal.decisions.put(stepIndex, om.createObjectNode().put("approved", approved));
    }

    @Test
    @DisplayName("single gate: cache-hit before frontier (no re-invoke), live after; WS suppressed in replay")
    void singleGateReplay() {
        String body = """
                phase('P');
                log('start');
                const a = agent('p0', { agentSlug: 'x' });
                const d = humanApprove({ ask: 'ok?' });
                phase('Q');
                log('after-approve');
                const b = agent('p2', { agentSlug: 'x' });
                return ({ a: a, approved: d.approved, b: b });
                """;

        // First run → pause at the gate (stepIndex 1). agent(0) ran live.
        WorkflowWsBroadcaster bc1 = mock(WorkflowWsBroadcaster.class);
        RunOutcome first = run(body, false, -1, bc1);
        assertThat(first.paused()).isTrue();
        assertThat(first.pausedAtStepIndex()).isEqualTo(1);
        assertThat(invokerCalls).containsExactly(0);
        // First run broadcasts normally (replayComplete=true).
        verify(bc1).phaseStarted("run-1", "P");
        verify(bc1).logged("run-1", "start");

        // Operator approves the gate.
        approve(1, true);

        // Resume (frontier=1).
        WorkflowWsBroadcaster bc2 = mock(WorkflowWsBroadcaster.class);
        RunOutcome second = run(body, true, 1, bc2);

        assertThat(second.paused()).isFalse();
        Map<String, Object> result = asMap(second.result());
        assertThat(result.get("a")).isEqualTo("resp-0");   // from cache
        assertThat(result.get("approved")).isEqualTo(Boolean.TRUE);
        assertThat(result.get("b")).isEqualTo("resp-2");   // live

        // agent(0) was NOT re-invoked on resume; only the post-frontier agent(2) ran.
        assertThat(invokerCalls).containsExactly(0, 2);

        // WS suppression: pre-frontier phase('P')/log('start') NOT re-broadcast;
        // post-frontier phase('Q')/log('after-approve') ARE.
        verify(bc2, never()).phaseStarted("run-1", "P");
        verify(bc2, never()).logged("run-1", "start");
        verify(bc2).phaseStarted("run-1", "Q");
        verify(bc2).logged("run-1", "after-approve");
    }

    @Test
    @DisplayName("two serial gates: frontier advances; steps written on a prior resume are cache-hit")
    void multiGateReplay() {
        String body = """
                const a = agent('p0', { agentSlug: 'x' });
                const d1 = humanApprove({ g: 1 });
                const c = agent('p2', { agentSlug: 'x' });
                const d2 = humanApprove({ g: 2 });
                const e = agent('p4', { agentSlug: 'x' });
                return ({ a: a, c: c, e: e, g1: d1.approved, g2: d2.approved });
                """;

        // First run → pause at gate1 (stepIndex 1). agent(0) live.
        RunOutcome first = run(body, false, -1, mock(WorkflowWsBroadcaster.class));
        assertThat(first.pausedAtStepIndex()).isEqualTo(1);
        assertThat(invokerCalls).containsExactly(0);

        // Approve gate1, resume (frontier=1) → cache 0, decide 1, live 2, pause gate2 (stepIndex 3).
        approve(1, true);
        RunOutcome second = run(body, true, 1, mock(WorkflowWsBroadcaster.class));
        assertThat(second.pausedAtStepIndex()).isEqualTo(3);
        assertThat(invokerCalls).containsExactly(0, 2); // agent(2) ran live this resume

        // Approve gate2, resume (frontier=3) → cache 0, decide 1, cache 2 (written in resume1!),
        // decide 3, live 4, complete.
        approve(3, false);
        RunOutcome third = run(body, true, 3, mock(WorkflowWsBroadcaster.class));
        assertThat(third.paused()).isFalse();
        assertThat(invokerCalls).containsExactly(0, 2, 4); // each agent ran exactly once, ever

        Map<String, Object> result = asMap(third.result());
        assertThat(result.get("a")).isEqualTo("resp-0");
        assertThat(result.get("c")).isEqualTo("resp-2");
        assertThat(result.get("e")).isEqualTo("resp-4");
        assertThat(result.get("g1")).isEqualTo(Boolean.TRUE);
        assertThat(result.get("g2")).isEqualTo(Boolean.FALSE);
    }

    @Test
    @DisplayName("parallel() agents before a gate: stepIndices align on replay; both cache-hit, none re-invoked")
    void parallelInsideReplay() {
        String body = """
                const xs = parallel([
                    function(){ return agent('p0a', { agentSlug: 'x' }); },
                    function(){ return agent('p1b', { agentSlug: 'x' }); }
                ]);
                const d = humanApprove({ ask: 'ok?' });
                const y = agent('p3', { agentSlug: 'x' });
                return ({ x0: xs[0], x1: xs[1], approved: d.approved, y: y });
                """;

        // First run → the two parallel agents (stepIndex 0,1) run live, then pause
        // at the gate (stepIndex 2). Step indices are assigned on the workflow thread
        // in program order even though the engine.run is offloaded.
        RunOutcome first = run(body, false, -1, mock(WorkflowWsBroadcaster.class));
        assertThat(first.paused()).isTrue();
        assertThat(first.pausedAtStepIndex()).isEqualTo(2);
        assertThat(invokerCalls).containsExactlyInAnyOrder(0, 1);

        // Approve the gate, resume (frontier=2).
        approve(2, true);
        RunOutcome second = run(body, true, 2, mock(WorkflowWsBroadcaster.class));

        assertThat(second.paused()).isFalse();
        // The two parallel agents (stepIndex 0,1 < frontier 2) were NOT re-invoked —
        // only the post-frontier agent(3) ran live this resume.
        assertThat(invokerCalls).containsExactly(0, 1, 3);

        Map<String, Object> result = asMap(second.result());
        assertThat(result.get("x0")).isEqualTo("resp-0");   // from cache
        assertThat(result.get("x1")).isEqualTo("resp-1");   // from cache
        assertThat(result.get("approved")).isEqualTo(Boolean.TRUE);
        assertThat(result.get("y")).isEqualTo("resp-3");    // live
    }

    @Test
    @DisplayName("two gates: phase()/log() between/before the frontier are suppressed on each resume, live ones broadcast")
    void multiGateReplayWsSuppression() {
        String body = """
                phase('A');
                const a = agent('p0', { agentSlug: 'x' });
                const d1 = humanApprove({ g: 1 });
                phase('B');
                log('between-gates');
                const c = agent('p3', { agentSlug: 'x' });
                const d2 = humanApprove({ g: 2 });
                phase('C');
                log('after-gate2');
                const e = agent('p6', { agentSlug: 'x' });
                return ({ done: true });
                """;

        // First run (replayComplete=true): phase('A') broadcasts; pause at gate1 (index 1).
        WorkflowWsBroadcaster bc1 = mock(WorkflowWsBroadcaster.class);
        RunOutcome first = run(body, false, -1, bc1);
        assertThat(first.pausedAtStepIndex()).isEqualTo(1);
        verify(bc1).phaseStarted("run-1", "A");

        // Resume 1 (frontier=1): phase('A') replayed-and-suppressed; after the gate
        // flips replayComplete, phase('B')/log run live; pause at gate2 (index 3).
        approve(1, true);
        WorkflowWsBroadcaster bc2 = mock(WorkflowWsBroadcaster.class);
        RunOutcome second = run(body, true, 1, bc2);
        assertThat(second.pausedAtStepIndex()).isEqualTo(3);
        assertThat(invokerCalls).containsExactly(0, 2);   // agent(2) ran live this resume
        verify(bc2, never()).phaseStarted("run-1", "A");
        verify(bc2).phaseStarted("run-1", "B");
        verify(bc2).logged("run-1", "between-gates");
        verify(bc2, never()).phaseStarted("run-1", "C");

        // Resume 2 (frontier=3): everything up to gate2 is replayed-and-suppressed
        // (including phase('B')/log, which were live on resume 1); only the
        // post-frontier phase('C')/log broadcast.
        approve(3, true);
        WorkflowWsBroadcaster bc3 = mock(WorkflowWsBroadcaster.class);
        RunOutcome third = run(body, true, 3, bc3);
        assertThat(third.paused()).isFalse();
        assertThat(invokerCalls).containsExactly(0, 2, 4);   // each agent ran exactly once, ever
        verify(bc3, never()).phaseStarted("run-1", "A");
        verify(bc3, never()).phaseStarted("run-1", "B");
        verify(bc3, never()).logged("run-1", "between-gates");
        verify(bc3).phaseStarted("run-1", "C");
        verify(bc3).logged("run-1", "after-gate2");
    }

    /** In-memory journal: agent results + recorded decisions keyed by stepIndex. */
    private static final class FakeJournal implements JournalCache {
        final Map<Integer, String> agent = new HashMap<>();
        final Map<Integer, JsonNode> decisions = new HashMap<>();
        final Map<Integer, JsonNode> toolResults = new HashMap<>();

        @Override
        public Optional<String> getCachedAgentFinalResponse(String runId, int stepIndex) {
            return Optional.ofNullable(agent.get(stepIndex));
        }

        @Override
        public Optional<JsonNode> getApproveDecision(String runId, int stepIndex) {
            return Optional.ofNullable(decisions.get(stepIndex));
        }

        @Override
        public Optional<JsonNode> getCachedToolResult(String runId, int stepIndex) {
            return Optional.ofNullable(toolResults.get(stepIndex));
        }
    }
}
