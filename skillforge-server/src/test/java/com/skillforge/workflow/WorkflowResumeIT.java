package com.skillforge.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.server.AbstractPostgresIT;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.flywheel.run.FlywheelRunEntity;
import com.skillforge.server.flywheel.run.FlywheelRunRepository;
import com.skillforge.server.flywheel.run.FlywheelRunService;
import com.skillforge.server.flywheel.run.FlywheelRunStepEntity;
import com.skillforge.server.flywheel.run.FlywheelRunStepRepository;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.service.SessionService;
import com.skillforge.server.websocket.UserWebSocketHandler;
import com.skillforge.workflow.journal.JournalCacheService;
import com.skillforge.workflow.ws.WorkflowWsBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task F — journal-replay resume end-to-end on real PostgreSQL. Proves the full
 * pause → approve → resume cycle with the REAL {@link JournalCacheService} reading
 * REAL {@code t_flywheel_run_step} rows (i.e. all resume state lives in the DB,
 * not in first-run memory — the "restart-safe" guarantee, plan §8 #3):
 *
 * <ul>
 *   <li>first run parks at the gate ({@code status=paused}, gate step pending);</li>
 *   <li>resume short-circuits the pre-gate {@code agent()} from the DB journal —
 *       the invoker is NOT re-run for it (asserted via an invocation counter) and
 *       no duplicate {@code (run_id, step_index)} row is written;</li>
 *   <li>the run completes after the post-gate {@code agent()} runs live.</li>
 * </ul>
 *
 * <p>Wiring mirrors {@code WorkflowHelloWorldIT}: manual {@code FlywheelRunService}
 * construction (no Spring proxy → @Transactional no-ops → writes join the test's
 * ambient tx) + an inline executor so the body runs on the test thread.
 */
@DisplayName("Workflow humanApprove resume (real Postgres)")
class WorkflowResumeIT extends AbstractPostgresIT {

    @Autowired private FlywheelRunRepository runRepository;
    @Autowired private FlywheelRunStepRepository stepRepository;
    @Autowired private AgentRepository agentRepository;

    private ObjectMapper objectMapper;
    private FlywheelRunService flywheelRunService;
    private JournalCacheService journalCache;
    private WorkflowDefinitionRegistry registry;
    private final List<Integer> invokerCalls = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        flywheelRunService = new FlywheelRunService(runRepository, stepRepository,
                mock(UserWebSocketHandler.class), objectMapper, Clock.systemUTC());
        journalCache = new JournalCacheService(stepRepository, objectMapper);
        registry = new WorkflowDefinitionRegistry();
        registry.reloadAll();
        invokerCalls.clear();
    }

    @Test
    @DisplayName("pause → approve → resume: pre-gate agent cache-hit (not re-invoked), run completes")
    void pauseApproveResume() {
        assertThat(registry.findByName("approve-single")).isPresent();
        assertThat(agentRepository.findFirstByName("session-annotator")).isPresent();

        SessionService sessionService = mock(SessionService.class);
        SessionEntity anchor = new SessionEntity();
        anchor.setId("anchor-resume-it");
        anchor.setUserId(1L);
        when(sessionService.createSession(anyLong(), anyLong())).thenReturn(anchor);
        // resume() reloads the anchor by generator_session_id.
        when(sessionService.getSession(anyString())).thenReturn(anchor);

        // Stub invoker: persists a real step row (so the journal cache can find it)
        // and counts invocations by stepIndex.
        WorkflowAgentInvokerFactory invokerFactory = mock(WorkflowAgentInvokerFactory.class);
        when(invokerFactory.create(anyString(), any(), any())).thenAnswer(inv -> {
            String runId = inv.getArgument(0);
            return (WorkflowAgentInvoker) (prompt, opts, stepIndex) -> {
                invokerCalls.add(stepIndex);
                String stepRunId = flywheelRunService.appendStep(runId,
                        "{\"agentSlug\":\"session-annotator\",\"stepIndex\":" + stepIndex + "}",
                        FlywheelRunStepEntity.STEP_KIND_SUBAGENT_DISPATCH, stepIndex);
                ObjectNode out = objectMapper.createObjectNode();
                out.put("finalResponse", "resp-" + stepIndex);
                flywheelRunService.transitionStepStatus(
                        stepRunId, FlywheelRunStepEntity.STATUS_COMPLETED, out, null);
                return "resp-" + stepIndex;
            };
        });

        ExecutorService inline = new InlineExecutorService();
        WorkflowRunnerService runner = new WorkflowRunnerService(
                registry, flywheelRunService, sessionService, agentRepository, invokerFactory,
                mock(WorkflowToolInvokerFactory.class),
                new ConsolidationLock(), mock(WorkflowWsBroadcaster.class), objectMapper,
                journalCache, Clock.systemUTC(), inline, inline, "session-annotator", 360L);

        // ── First run → parks at the gate ──
        String runId = runner.startRun("approve-single", Map.of(), 1L);

        FlywheelRunEntity paused = runRepository.findById(runId).orElseThrow();
        assertThat(paused.getStatus()).isEqualTo(FlywheelRunEntity.STATUS_PAUSED);
        assertThat(invokerCalls).containsExactly(0); // only the pre-gate agent ran

        List<FlywheelRunStepEntity> afterPause = flywheelRunService.listStepsByRunId(runId);
        assertThat(afterPause).hasSize(2);
        FlywheelRunStepEntity gate = afterPause.stream()
                .filter(s -> FlywheelRunStepEntity.STEP_KIND_HUMAN_APPROVE.equals(s.getStepKind()))
                .findFirst().orElseThrow();
        assertThat(gate.getStatus()).isEqualTo(FlywheelRunStepEntity.STATUS_PENDING);
        assertThat(gate.getStepIndex()).isEqualTo(1);

        // ── Approve → resume ──
        runner.resume(runId, true, "looks good", "alice");

        FlywheelRunEntity done = runRepository.findById(runId).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(FlywheelRunEntity.STATUS_COMPLETED);

        // Pre-gate agent(0) was served from the DB journal — NOT re-invoked. Only
        // the post-gate agent(2) ran live on resume.
        assertThat(invokerCalls).containsExactly(0, 2);

        // Gate decided; post-gate agent step persisted at step_index 2; no duplicate
        // (run_id, step_index) — the cache-hit path never re-appended step 0.
        List<FlywheelRunStepEntity> finalSteps = flywheelRunService.listStepsByRunId(runId);
        assertThat(finalSteps).hasSize(3);
        assertThat(finalSteps.stream().map(FlywheelRunStepEntity::getStepIndex).sorted().toList())
                .containsExactly(0, 1, 2);
        FlywheelRunStepEntity decidedGate = finalSteps.stream()
                .filter(s -> FlywheelRunStepEntity.STEP_KIND_HUMAN_APPROVE.equals(s.getStepKind()))
                .findFirst().orElseThrow();
        assertThat(decidedGate.getStatus()).isEqualTo(FlywheelRunStepEntity.STATUS_COMPLETED);
        assertThat(decidedGate.getStepOutputJson()).contains("\"approved\":true");
    }

    /** Runs submitted tasks synchronously on the calling (test) thread. */
    private static final class InlineExecutorService extends AbstractExecutorService {
        private volatile boolean shutdown = false;
        @Override public void execute(Runnable command) { command.run(); }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { shutdown = true; return Collections.emptyList(); }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }
}
