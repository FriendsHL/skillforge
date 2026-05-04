package com.skillforge.server.tool;

import com.skillforge.core.engine.CancellationRegistry;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.service.AgentTargetResolver;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.service.SessionService;
import com.skillforge.server.subagent.SubAgentRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubAgentToolTest {

    @Mock
    private AgentTargetResolver targetResolver;
    @Mock
    private SessionService sessionService;
    @Mock
    private ChatService chatService;
    @Mock
    private SubAgentRegistry registry;
    @Mock
    private CancellationRegistry cancellationRegistry;

    private SubAgentTool tool;

    @BeforeEach
    void setUp() {
        tool = new SubAgentTool(targetResolver, sessionService, chatService, registry, cancellationRegistry);
    }

    @Test
    void dispatch_acceptsAgentName() {
        SessionEntity parent = session("parent", 1L, null, 0);
        AgentEntity target = agent(2L, "Reviewer");
        SubAgentRegistry.SubAgentRun run = new SubAgentRegistry.SubAgentRun();
        run.runId = "12345678-1234-1234-1234-123456789012";
        SessionEntity child = session("child", 2L, "parent", 1);

        when(sessionService.getSession("parent")).thenReturn(parent);
        when(targetResolver.resolveVisibleTarget("parent", null, "Reviewer")).thenReturn(target);
        when(registry.registerRun(parent, 2L, "Reviewer", "review this")).thenReturn(run);
        when(sessionService.createSubSession(parent, 2L, run.runId)).thenReturn(child);

        SkillResult result = tool.execute(Map.of(
                "action", "dispatch",
                "agentName", "Reviewer",
                "task", "review this"
        ), new SkillContext(null, "parent", 10L));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("Reviewer").contains("childSessionId: child");
        // OBS-4 §2.1: SubAgentTool now uses 4-arg chatAsync(preserveActiveRoot=true) so
        // child inherits parent's active_root_trace_id (INV-4).
        verify(chatService).chatAsync("child", "review this", 10L, true);
    }

    @Test
    void dispatch_rejectsRecursiveAgentLineage() {
        SessionEntity parent = session("parent", 1L, null, 0);
        AgentEntity target = agent(1L, "Main");

        when(sessionService.getSession("parent")).thenReturn(parent);
        when(targetResolver.resolveVisibleTarget("parent", 1L, null)).thenReturn(target);

        SkillResult result = tool.execute(Map.of(
                "action", "dispatch",
                "agentId", 1L,
                "task", "call myself"
        ), new SkillContext(null, "parent", 10L));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("recursive agent dispatch");
        verify(registry, never()).registerRun(any(), any(), any(), any());
    }

    @Test
    void dispatch_requiresAgentIdOrName() {
        SkillResult result = tool.execute(Map.of(
                "action", "dispatch",
                "task", "missing target"
        ), new SkillContext(null, "parent", 10L));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("agentId or agentName");
    }

    // ============ continue ============

    @Test
    void continue_idleChild_succeeds() {
        SubAgentRegistry.SubAgentRun run = run("run-1", "parent", "child", "Reviewer");
        SessionEntity parent = session("parent", 1L, null, 0);
        SessionEntity child = session("child", 2L, "parent", 1);
        child.setRuntimeStatus("idle");

        when(registry.getRun("run-1")).thenReturn(run);
        when(sessionService.getSession("parent")).thenReturn(parent);
        when(sessionService.getSession("child")).thenReturn(child);

        SkillResult result = tool.execute(Map.of(
                "action", "continue",
                "runId", "run-1",
                "task", "follow-up question"
        ), new SkillContext(null, "parent", 10L));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("Continued subagent run").contains("childSessionId: child");
        verify(registry).markRunResumed("run-1");
        // OBS-4 §M1 INV-3/INV-4: continue must use preserveActiveRoot=true so the continued loop
        // keeps the same active_root_trace_id as prior dispatches on this child session.
        // userId is sourced from parent.getUserId() (matches handleDispatch:194 for symmetry).
        verify(chatService).chatAsync("child", "follow-up question", 10L, true);
    }

    @Test
    void continue_terminatedRun_returnsError() {
        // Even if child.runtime_status has reverted to "idle" after termination teardown,
        // the run-level TERMINATED guard must reject continue.
        SubAgentRegistry.SubAgentRun run = run("run-1", "parent", "child", "Reviewer");
        run.status = "TERMINATED";
        when(registry.getRun("run-1")).thenReturn(run);

        SkillResult result = tool.execute(Map.of(
                "action", "continue",
                "runId", "run-1",
                "task", "anything"
        ), new SkillContext(null, "parent", 10L));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError())
                .contains("no longer reusable")
                .contains("TERMINATED")
                .contains("dispatch");
        verify(registry, never()).markRunResumed(any());
        verify(chatService, never()).chatAsync(any(), any(), any(), any(Boolean.class));
    }

    @Test
    void continue_runningChild_returnsErrorWithGuidance() {
        SubAgentRegistry.SubAgentRun run = run("run-1", "parent", "child", "Reviewer");
        SessionEntity child = session("child", 2L, "parent", 1);
        child.setRuntimeStatus("running");

        when(registry.getRun("run-1")).thenReturn(run);
        when(sessionService.getSession("child")).thenReturn(child);

        SkillResult result = tool.execute(Map.of(
                "action", "continue",
                "runId", "run-1",
                "task", "anything"
        ), new SkillContext(null, "parent", 10L));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError())
                .contains("still running")
                .contains("terminate")
                .contains("dispatch");
        verify(registry, never()).markRunResumed(any());
        verify(chatService, never()).chatAsync(any(), any(), any(), any(Boolean.class));
    }

    @Test
    void continue_waitingUserChild_returnsError() {
        SubAgentRegistry.SubAgentRun run = run("run-1", "parent", "child", "Reviewer");
        SessionEntity child = session("child", 2L, "parent", 1);
        child.setRuntimeStatus("waiting_user");

        when(registry.getRun("run-1")).thenReturn(run);
        when(sessionService.getSession("child")).thenReturn(child);

        SkillResult result = tool.execute(Map.of(
                "action", "continue",
                "runId", "run-1",
                "task", "anything"
        ), new SkillContext(null, "parent", 10L));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("waiting for user approval");
    }

    @Test
    void continue_errorChild_returnsError() {
        SubAgentRegistry.SubAgentRun run = run("run-1", "parent", "child", "Reviewer");
        SessionEntity child = session("child", 2L, "parent", 1);
        child.setRuntimeStatus("error");

        when(registry.getRun("run-1")).thenReturn(run);
        when(sessionService.getSession("child")).thenReturn(child);

        SkillResult result = tool.execute(Map.of(
                "action", "continue",
                "runId", "run-1",
                "task", "anything"
        ), new SkillContext(null, "parent", 10L));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("error state");
    }

    @Test
    void continue_unknownRunId_returnsError() {
        when(registry.getRun("nope")).thenReturn(null);

        SkillResult result = tool.execute(Map.of(
                "action", "continue",
                "runId", "nope",
                "task", "anything"
        ), new SkillContext(null, "parent", 10L));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("Unknown subagent runId");
    }

    @Test
    void continue_crossParentRunId_returnsError() {
        // run belongs to parent "other-parent", but caller is "parent" — must reject.
        SubAgentRegistry.SubAgentRun run = run("run-1", "other-parent", "child", "Reviewer");
        when(registry.getRun("run-1")).thenReturn(run);

        SkillResult result = tool.execute(Map.of(
                "action", "continue",
                "runId", "run-1",
                "task", "anything"
        ), new SkillContext(null, "parent", 10L));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("different parent session");
        verify(registry, never()).markRunResumed(any());
        verify(chatService, never()).chatAsync(any(), any(), any(), any(Boolean.class));
    }

    @Test
    void continue_blankRunId_returnsError() {
        SkillResult result = tool.execute(Map.of(
                "action", "continue",
                "runId", "",
                "task", "anything"
        ), new SkillContext(null, "parent", 10L));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("runId is required");
    }

    @Test
    void continue_blankTask_returnsError() {
        SkillResult result = tool.execute(Map.of(
                "action", "continue",
                "runId", "run-1",
                "task", ""
        ), new SkillContext(null, "parent", 10L));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("task is required");
    }

    @Test
    void continue_runWithoutChildSession_returnsError() {
        SubAgentRegistry.SubAgentRun run = run("run-1", "parent", null, "Reviewer");
        when(registry.getRun("run-1")).thenReturn(run);

        SkillResult result = tool.execute(Map.of(
                "action", "continue",
                "runId", "run-1",
                "task", "anything"
        ), new SkillContext(null, "parent", 10L));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("no attached child session");
    }

    // ============ terminate ============

    @Test
    void terminate_runningChild_marksTerminated() {
        SubAgentRegistry.SubAgentRun run = run("run-1", "parent", "child", "Reviewer");
        run.status = "RUNNING";
        SessionEntity child = session("child", 2L, "parent", 1);
        child.setRuntimeStatus("running");

        when(registry.getRun("run-1")).thenReturn(run);
        when(sessionService.getSession("child")).thenReturn(child);
        when(cancellationRegistry.cancel("child")).thenReturn(true);

        SkillResult result = tool.execute(Map.of(
                "action", "terminate",
                "runId", "run-1"
        ), new SkillContext(null, "parent", 10L));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("Terminated subagent run").contains("status: TERMINATED");
        verify(cancellationRegistry).cancel("child");
        verify(registry).markRunTerminated("run-1");
        // child runtime_status should be flipped to "terminated" and saved.
        assertThat(child.getRuntimeStatus()).isEqualTo("terminated");
        verify(sessionService).saveSession(child);
    }

    @Test
    void terminate_completedChild_isIdempotent() {
        SubAgentRegistry.SubAgentRun run = run("run-1", "parent", "child", "Reviewer");
        run.status = "COMPLETED";
        when(registry.getRun("run-1")).thenReturn(run);

        SkillResult result = tool.execute(Map.of(
                "action", "terminate",
                "runId", "run-1"
        ), new SkillContext(null, "parent", 10L));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("already in terminal state").contains("COMPLETED");
        verify(cancellationRegistry, never()).cancel(any());
        verify(registry, never()).markRunTerminated(any());
    }

    @Test
    void terminate_unknownRunId_returnsError() {
        when(registry.getRun("nope")).thenReturn(null);

        SkillResult result = tool.execute(Map.of(
                "action", "terminate",
                "runId", "nope"
        ), new SkillContext(null, "parent", 10L));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("Unknown subagent runId");
        verify(registry, never()).markRunTerminated(any());
    }

    @Test
    void terminate_crossParentRunId_returnsError() {
        SubAgentRegistry.SubAgentRun run = run("run-1", "other-parent", "child", "Reviewer");
        when(registry.getRun("run-1")).thenReturn(run);

        SkillResult result = tool.execute(Map.of(
                "action", "terminate",
                "runId", "run-1"
        ), new SkillContext(null, "parent", 10L));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("different parent session");
        verify(cancellationRegistry, never()).cancel(any());
        verify(registry, never()).markRunTerminated(any());
    }

    @Test
    void terminate_blankRunId_returnsError() {
        SkillResult result = tool.execute(Map.of(
                "action", "terminate",
                "runId", ""
        ), new SkillContext(null, "parent", 10L));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("runId is required");
    }

    private static SubAgentRegistry.SubAgentRun run(String runId, String parentSessionId,
                                                     String childSessionId, String agentName) {
        SubAgentRegistry.SubAgentRun r = new SubAgentRegistry.SubAgentRun();
        r.runId = runId;
        r.parentSessionId = parentSessionId;
        r.childSessionId = childSessionId;
        r.childAgentName = agentName;
        r.status = "RUNNING";
        return r;
    }

    private static AgentEntity agent(Long id, String name) {
        AgentEntity agent = new AgentEntity();
        agent.setId(id);
        agent.setName(name);
        agent.setStatus("active");
        return agent;
    }

    private static SessionEntity session(String id, Long agentId, String parentSessionId, int depth) {
        SessionEntity session = new SessionEntity();
        session.setId(id);
        session.setAgentId(agentId);
        session.setParentSessionId(parentSessionId);
        session.setDepth(depth);
        session.setUserId(10L);
        return session;
    }
}
