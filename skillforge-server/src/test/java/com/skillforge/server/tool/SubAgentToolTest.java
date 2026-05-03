package com.skillforge.server.tool;

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

    private SubAgentTool tool;

    @BeforeEach
    void setUp() {
        tool = new SubAgentTool(targetResolver, sessionService, chatService, registry);
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
