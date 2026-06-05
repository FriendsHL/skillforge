package com.skillforge.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.flywheel.run.FlywheelRunEntity;
import com.skillforge.server.flywheel.run.FlywheelRunService;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.service.SessionService;
import com.skillforge.workflow.exception.WorkflowAlreadyRunningException;
import com.skillforge.workflow.exception.WorkflowNotFoundException;
import com.skillforge.workflow.ws.WorkflowWsBroadcaster;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task G — {@link WorkflowRunnerService} orchestration: not-found / lock guard /
 * markCompleted-on-success / markError-on-failure / lock-always-released. Uses a
 * single-thread executor so the async body completes deterministically within
 * the test (mockito-lenient because branches exercise different subsets).
 */
@ExtendWith(MockitoExtension.class)
class WorkflowRunnerServiceTest {

    @Mock private WorkflowDefinitionRegistry registry;
    @Mock private FlywheelRunService flywheelRunService;
    @Mock private SessionService sessionService;
    @Mock private AgentRepository agentRepository;
    @Mock private WorkflowAgentInvokerFactory invokerFactory;

    private ConsolidationLock lock;
    private WorkflowWsBroadcaster wsBroadcaster;
    private ExecutorService wfExec;
    private ExecutorService subExec;
    private WorkflowRunnerService service;

    @BeforeEach
    void setUp() {
        lock = new ConsolidationLock();
        wsBroadcaster = mock(WorkflowWsBroadcaster.class);
        wfExec = Executors.newSingleThreadExecutor();
        subExec = Executors.newSingleThreadExecutor();
        service = new WorkflowRunnerService(registry, flywheelRunService, sessionService,
                agentRepository, invokerFactory, mock(WorkflowToolInvokerFactory.class),
                lock, wsBroadcaster, new ObjectMapper(),
                mock(com.skillforge.workflow.journal.JournalCache.class),
                java.time.Clock.systemUTC(), wfExec, subExec, "anchor-agent", 360L);
    }

    @AfterEach
    void tearDown() {
        wfExec.shutdownNow();
        subExec.shutdownNow();
    }

    private void stubStartRunInfra(String jsSource) {
        WorkflowDefinition def = new WorkflowDefinition(
                "wf", "d", List.of(), jsSource, "hash-1");
        when(registry.findByName("wf")).thenReturn(Optional.of(def));

        AgentEntity agent = new AgentEntity();
        agent.setId(3L);
        agent.setName("anchor-agent");
        when(agentRepository.findFirstByName("anchor-agent")).thenReturn(Optional.of(agent));

        FlywheelRunEntity run = new FlywheelRunEntity();
        run.setId("run-1");
        when(flywheelRunService.startRun(eq("workflow"), eq("user_manual"), any(), eq(3L), eq(1)))
                .thenReturn(run);

        SessionEntity anchor = new SessionEntity();
        anchor.setId("anchor-sess");
        when(sessionService.createSession(eq(5L), eq(3L))).thenReturn(anchor);

        // invoker never used by the no-agent scripts in these tests.
        lenient().when(invokerFactory.create(anyString(), any(), any()))
                .thenReturn((p, o, i) -> "unused");
    }

    private void awaitBody() throws InterruptedException {
        wfExec.shutdown();
        assertThat(wfExec.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("unknown workflow name throws WorkflowNotFoundException, lock not held")
    void unknownWorkflowThrows() {
        when(registry.findByName("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.startRun("ghost", Map.of(), 5L))
                .isInstanceOf(WorkflowNotFoundException.class);
        assertThat(lock.isHeld("ghost")).isFalse();
    }

    @Test
    @DisplayName("second concurrent run of same name throws WorkflowAlreadyRunningException")
    void doubleRunRejected() {
        WorkflowDefinition def = new WorkflowDefinition("wf", "d", List.of(), "return 1;", "h");
        when(registry.findByName("wf")).thenReturn(Optional.of(def));
        lock.tryAcquire("wf"); // simulate an in-flight run holding the lock

        assertThatThrownBy(() -> service.startRun("wf", Map.of(), 5L))
                .isInstanceOf(WorkflowAlreadyRunningException.class);
        verify(flywheelRunService, never()).startRun(any(), any(), any(), any(), any(Integer.class));
    }

    @Test
    @DisplayName("happy path: run completes → markCompleted + lock released")
    void happyPathCompletes() throws InterruptedException {
        stubStartRunInfra("phase('P'); log('hi'); return ({ ok: true });");

        String runId = service.startRun("wf", Map.of(), 5L);
        assertThat(runId).isEqualTo("run-1");
        awaitBody();

        verify(flywheelRunService).attachGeneratorSession("run-1", "anchor-sess");
        verify(flywheelRunService).markCompleted(eq("run-1"), eq(null), anyString());
        assertThat(lock.isHeld("wf")).isFalse();
    }

    @Test
    @DisplayName("startRun(...,loopKind=evolve) creates the run row with loop_kind=evolve")
    void startRunPassesEvolveLoopKind() throws InterruptedException {
        WorkflowDefinition def = new WorkflowDefinition("wf", "d", List.of(), "return 1;", "h");
        when(registry.findByName("wf")).thenReturn(Optional.of(def));
        AgentEntity agent = new AgentEntity();
        agent.setId(3L);
        agent.setName("anchor-agent");
        when(agentRepository.findFirstByName("anchor-agent")).thenReturn(Optional.of(agent));
        FlywheelRunEntity run = new FlywheelRunEntity();
        run.setId("run-evolve");
        // Run is attributed to args.agentId (7) and created with loop_kind=evolve.
        when(flywheelRunService.startRun(eq("evolve"), eq("user_manual"), any(), eq(7L), eq(1)))
                .thenReturn(run);
        SessionEntity anchor = new SessionEntity();
        anchor.setId("anchor-sess");
        when(sessionService.createSession(eq(0L), eq(3L))).thenReturn(anchor);
        lenient().when(invokerFactory.create(anyString(), any(), any()))
                .thenReturn((p, o, i) -> "unused");

        String runId = service.startRun("wf", Map.of("agentId", 7L), 0L, "evolve");
        assertThat(runId).isEqualTo("run-evolve");
        awaitBody();

        verify(flywheelRunService).startRun(eq("evolve"), eq("user_manual"), any(), eq(7L), eq(1));
    }

    @Test
    @DisplayName("executor rejects body dispatch → lock released + run markError (no leak)")
    void executorRejectReleasesLockAndMarksError() {
        stubStartRunInfra("return 1;");
        ExecutorService rejecting = mock(ExecutorService.class);
        doThrow(new RejectedExecutionException("queue full")).when(rejecting).execute(any());
        WorkflowRunnerService rejectingService = new WorkflowRunnerService(registry, flywheelRunService,
                sessionService, agentRepository, invokerFactory, mock(WorkflowToolInvokerFactory.class),
                lock, wsBroadcaster, new ObjectMapper(),
                mock(com.skillforge.workflow.journal.JournalCache.class),
                java.time.Clock.systemUTC(), rejecting, subExec, "anchor-agent", 360L);

        assertThatThrownBy(() -> rejectingService.startRun("wf", Map.of(), 5L))
                .isInstanceOf(RejectedExecutionException.class);

        // lock must NOT leak, and the created run must be marked errored.
        assertThat(lock.isHeld("wf")).isFalse();
        verify(flywheelRunService).markError(eq("run-1"), anyString());
    }

    @Test
    @DisplayName("startRun(def,...) runs an inline definition WITHOUT a registry lookup")
    void inlineDefStartsWithoutRegistryLookup() throws InterruptedException {
        // No registry.findByName stub on purpose: the inline overload must not consult it.
        AgentEntity agent = new AgentEntity();
        agent.setId(3L);
        agent.setName("anchor-agent");
        when(agentRepository.findFirstByName("anchor-agent")).thenReturn(Optional.of(agent));

        FlywheelRunEntity run = new FlywheelRunEntity();
        run.setId("run-inline");
        when(flywheelRunService.startRun(eq("workflow"), eq("user_manual"), any(), eq(3L), eq(1)))
                .thenReturn(run);

        SessionEntity anchor = new SessionEntity();
        anchor.setId("anchor-sess");
        when(sessionService.createSession(eq(5L), eq(3L))).thenReturn(anchor);
        lenient().when(invokerFactory.create(anyString(), any(), any()))
                .thenReturn((p, o, i) -> "unused");

        WorkflowDefinition def = new WorkflowDefinition(
                "inline-wf", "d", List.of(), "phase('P'); return ({ ok: true });", "hash-inline");

        String runId = service.startRun(def, Map.of(), 5L);
        assertThat(runId).isEqualTo("run-inline");
        awaitBody();

        verify(registry, never()).findByName(anyString());
        verify(flywheelRunService).markCompleted(eq("run-inline"), eq(null), anyString());
        assertThat(lock.isHeld("inline-wf")).isFalse();
    }

    @Test
    @DisplayName("script runtime error → markError + lock released")
    void errorPathMarksError() throws InterruptedException {
        stubStartRunInfra("phase('P'); undefinedFnXyz();");

        service.startRun("wf", Map.of(), 5L);
        awaitBody();

        verify(flywheelRunService).markError(eq("run-1"), anyString());
        assertThat(lock.isHeld("wf")).isFalse();
    }

    @Test
    @DisplayName("AUTOEVOLVE: extractAgentId parses String / Number agentId, null otherwise")
    void extractAgentId_variants() {
        assertThat(WorkflowRunnerService.extractAgentId(Map.of("agentId", "5"))).isEqualTo(5L);
        assertThat(WorkflowRunnerService.extractAgentId(Map.of("agentId", 7))).isEqualTo(7L);
        assertThat(WorkflowRunnerService.extractAgentId(Map.of("agentId", "0"))).isNull();   // non-positive
        assertThat(WorkflowRunnerService.extractAgentId(Map.of("agentId", "x"))).isNull();   // unparseable
        assertThat(WorkflowRunnerService.extractAgentId(Map.of("windowDays", 7))).isNull();  // absent
        assertThat(WorkflowRunnerService.extractAgentId(null)).isNull();
    }

    @Test
    @DisplayName("AUTOEVOLVE: run is attributed to args.agentId (the target), not the anchor agent")
    void startRun_attributesRunToArgsAgentId() throws InterruptedException {
        WorkflowDefinition def = new WorkflowDefinition("wf", "d", List.of(), "return ({ok:true});", "h");
        when(registry.findByName("wf")).thenReturn(Optional.of(def));
        AgentEntity anchor = new AgentEntity();
        anchor.setId(3L);
        anchor.setName("anchor-agent");
        when(agentRepository.findFirstByName("anchor-agent")).thenReturn(Optional.of(anchor));
        FlywheelRunEntity run = new FlywheelRunEntity();
        run.setId("run-9");
        // expect the run created with agentId=5 (from args), NOT 3 (anchor)
        when(flywheelRunService.startRun(eq("workflow"), eq("user_manual"), any(), eq(5L), eq(1)))
                .thenReturn(run);
        SessionEntity sess = new SessionEntity();
        sess.setId("sess-9");
        when(sessionService.createSession(eq(5L), eq(3L))).thenReturn(sess);
        lenient().when(invokerFactory.create(anyString(), any(), any())).thenReturn((p, o, i) -> "x");

        service.startRun("wf", Map.of("agentId", "5"), 5L);
        awaitBody();

        verify(flywheelRunService).startRun(eq("workflow"), eq("user_manual"), any(), eq(5L), eq(1));
    }
}
