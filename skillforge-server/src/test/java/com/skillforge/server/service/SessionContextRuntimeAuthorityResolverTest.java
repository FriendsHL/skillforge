package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.ToolCatalog;
import com.skillforge.core.context.runtime.ContextRuntimeAuthority;
import com.skillforge.core.engine.AgentLoopEngine;
import com.skillforge.core.engine.LoopContext;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.skill.view.SessionSkillView;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.CollabRunRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionContextRuntimeAuthorityResolverTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ObjectProvider<AgentLoopEngine> engineProvider = mock(ObjectProvider.class);
    private final AgentRepository agentRepository = mock(AgentRepository.class);
    private final AgentService agentService = mock(AgentService.class);
    private final CollabRunRepository collabRunRepository = mock(CollabRunRepository.class);
    private final AgentLoopEngine engine = mock(AgentLoopEngine.class);
    private final SessionContextRuntimeAuthorityResolver resolver =
            new SessionContextRuntimeAuthorityResolver(
                    engineProvider, agentRepository, agentService,
                    collabRunRepository, objectMapper);

    @Test
    void resolvesThroughLiveEngineWithCurrentSessionOverridesAndAllowlist() {
        SessionEntity session = session();
        session.setExecutionMode("auto");
        session.setSkillOverridesJson("[\"session-skill\"]");
        AgentEntity agent = agent();
        agent.setMcpServerIds("server-a,server-b");
        AgentDefinition definition = new AgentDefinition();
        definition.setConfig(Map.of("tool_ids", List.of("Read")));
        ContextRuntimeAuthority expected = new ContextRuntimeAuthority(
                ToolCatalog.fromAuthorizedSchemas(List.of(), objectMapper), SessionSkillView.EMPTY);
        when(engineProvider.getIfAvailable()).thenReturn(engine);
        when(agentRepository.findById(7L)).thenReturn(Optional.of(agent));
        when(agentService.toAgentDefinition(agent)).thenReturn(definition);
        when(engine.resolveContextRuntimeAuthority(
                org.mockito.ArgumentMatchers.same(definition),
                org.mockito.ArgumentMatchers.any(LoopContext.class)))
                .thenReturn(expected);

        assertThat(resolver.resolve(session)).isSameAs(expected);

        ArgumentCaptor<LoopContext> context = ArgumentCaptor.forClass(LoopContext.class);
        verify(engine).resolveContextRuntimeAuthority(
                org.mockito.ArgumentMatchers.same(definition), context.capture());
        assertThat(definition.getSkillIds()).containsExactly("session-skill");
        assertThat(context.getValue().getSessionId()).isEqualTo("session-1");
        assertThat(context.getValue().getExecutionMode()).isEqualTo("auto");
        assertThat(context.getValue().getAllowedToolNames()).containsExactly("Read");
        assertThat(context.getValue().getAllowedMcpServerNames())
                .containsExactlyInAnyOrder("server-a", "server-b");
    }

    @Test
    void malformedToolAllowlistFailsClosedInsteadOfExpandingAuthority() {
        SessionEntity session = session();
        AgentEntity agent = agent();
        AgentDefinition definition = new AgentDefinition();
        definition.setConfig(Map.of("tool_ids", List.of(123)));
        when(engineProvider.getIfAvailable()).thenReturn(engine);
        when(agentRepository.findById(7L)).thenReturn(Optional.of(agent));
        when(agentService.toAgentDefinition(agent)).thenReturn(definition);

        ContextRuntimeAuthority authority = resolver.resolve(session);

        assertThat(authority.toolCatalog().descriptors()).isEmpty();
        assertThat(authority.skillView()).isSameAs(SessionSkillView.EMPTY);
        verify(engine, never()).resolveContextRuntimeAuthority(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private static SessionEntity session() {
        SessionEntity session = new SessionEntity();
        session.setId("session-1");
        session.setAgentId(7L);
        session.setDepth(0);
        return session;
    }

    private static AgentEntity agent() {
        AgentEntity agent = new AgentEntity();
        agent.setId(7L);
        agent.setExecutionMode("ask");
        agent.setMcpServerIds("");
        return agent;
    }
}
