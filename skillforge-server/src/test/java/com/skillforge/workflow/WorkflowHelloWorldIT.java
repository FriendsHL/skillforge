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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task H — end-to-end DB integration on real PostgreSQL (AC-1/AC-3).
 *
 * <p>Drives {@link WorkflowRunnerService#startRun} for the classpath
 * {@code hello-world.workflow.js} and asserts the run + step rows land in
 * {@code t_flywheel_run} (loop_kind=workflow — exercises the V126 CHECK
 * extension) and {@code t_flywheel_run_step}.
 *
 * <p><b>Wiring notes (@DataJpaTest constraints):</b>
 * <ul>
 *   <li>{@code FlywheelRunService} is constructed manually from autowired repos
 *       (the project's established IT pattern — see {@code
 *       FlywheelRunServiceStepCrudIT}). Manual construction ⇒ no Spring proxy ⇒
 *       its {@code @Transactional} methods are no-ops, so all writes join the
 *       test's ambient transaction and are visible to the assertions.</li>
 *   <li>The workflow body runs on an <b>inline</b> executor so it executes on the
 *       test thread inside that same transaction (a real worker thread would not
 *       see the uncommitted run row).</li>
 *   <li>The {@code agent()} call is stubbed via the invoker factory to persist a
 *       step row through the real {@code FlywheelRunService} — the real
 *       engine.run path is covered by {@code DefaultWorkflowAgentInvokerTest} and
 *       {@code WorkflowSpikeHelloWorldTest}.</li>
 * </ul>
 */
@DisplayName("Workflow hello-world end-to-end persistence (real Postgres)")
class WorkflowHelloWorldIT extends AbstractPostgresIT {

    @Autowired private FlywheelRunRepository runRepository;
    @Autowired private FlywheelRunStepRepository stepRepository;
    @Autowired private AgentRepository agentRepository;

    private ObjectMapper objectMapper;
    private FlywheelRunService flywheelRunService;
    private WorkflowDefinitionRegistry registry;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        flywheelRunService = new FlywheelRunService(runRepository, stepRepository,
                mock(UserWebSocketHandler.class), objectMapper, Clock.systemUTC());
        registry = new WorkflowDefinitionRegistry();
        registry.reloadAll(); // scan classpath:workflows/*.workflow.js
    }

    @Test
    @DisplayName("startRun(hello-world) → run completed + loop_kind=workflow + step row persisted")
    void helloWorldEndToEnd() {
        // hello-world.workflow.js loaded from the classpath
        assertThat(registry.findByName("hello-world")).isPresent();
        // 'session-annotator' is a real seeded agent (V95) used as the run anchor;
        // WorkflowRunnerService resolves it internally.
        assertThat(agentRepository.findFirstByName("session-annotator")).isPresent();

        // Mock session service: anchor is depth-0; the stub invoker ignores it.
        SessionService sessionService = mock(SessionService.class);
        SessionEntity anchor = new SessionEntity();
        anchor.setId("anchor-sess-it");
        when(sessionService.createSession(anyLong(), anyLong())).thenReturn(anchor);

        // Stub invoker factory: each agent() persists a step row through the real
        // FlywheelRunService and returns a canned greeting.
        WorkflowAgentInvokerFactory invokerFactory = mock(WorkflowAgentInvokerFactory.class);
        when(invokerFactory.create(anyString(), any(), any())).thenAnswer(inv -> {
            String runId = inv.getArgument(0);
            return (WorkflowAgentInvoker) (prompt, opts, stepIndex) -> {
                String slug = String.valueOf(opts.get("agentSlug"));
                String stepRunId = flywheelRunService.appendStep(runId,
                        "{\"agentSlug\":\"" + slug + "\",\"stepIndex\":" + stepIndex + "}");
                ObjectNode out = objectMapper.createObjectNode();
                out.put("finalResponse", "hi-from-" + slug);
                flywheelRunService.transitionStepStatus(
                        stepRunId, FlywheelRunStepEntity.STATUS_COMPLETED, out, null);
                return "hi-from-" + slug;
            };
        });

        ExecutorService inline = new InlineExecutorService();
        WorkflowRunnerService runner = new WorkflowRunnerService(
                registry, flywheelRunService, sessionService, agentRepository, invokerFactory,
                new ConsolidationLock(), mock(WorkflowWsBroadcaster.class), objectMapper,
                new com.skillforge.workflow.journal.JournalCacheService(stepRepository, objectMapper),
                Clock.systemUTC(), inline, inline, "session-annotator");

        String runId = runner.startRun("hello-world", Map.of(), 1L);

        // Run row: completed + loop_kind=workflow (V126 allow-list).
        FlywheelRunEntity run = runRepository.findById(runId).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(FlywheelRunEntity.STATUS_COMPLETED);
        assertThat(run.getLoopKind()).isEqualTo("workflow");

        // Step row persisted with the agent slug.
        List<FlywheelRunStepEntity> steps = flywheelRunService.listStepsByRunId(runId);
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).getStatus()).isEqualTo(FlywheelRunStepEntity.STATUS_COMPLETED);
        assertThat(steps.get(0).getStepInputJson()).contains("session-annotator");
        assertThat(steps.get(0).getStepOutputJson()).contains("hi-from-session-annotator");
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
