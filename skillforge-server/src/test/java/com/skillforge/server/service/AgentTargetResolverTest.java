package com.skillforge.server.service;

import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentTargetResolverTest {

    @Mock
    private AgentRepository agentRepository;

    @Mock
    private SessionRepository sessionRepository;

    private AgentTargetResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new AgentTargetResolver(agentRepository, sessionRepository);
    }

    @Test
    void listVisibleTargets_filtersByVisibilityAndQuery() {
        AgentEntity author = agent(1L, "Main", 10L, true, "active");
        AgentEntity sameOwner = agent(2L, "Code Reviewer", 10L, false, "active");
        AgentEntity publicOther = agent(3L, "Researcher", 99L, true, "active");
        AgentEntity privateOther = agent(4L, "Secret Reviewer", 99L, false, "active");
        SessionEntity session = session("s1", 1L);

        when(sessionRepository.findById("s1")).thenReturn(Optional.of(session));
        when(agentRepository.findById(1L)).thenReturn(Optional.of(author));
        when(agentRepository.findAll()).thenReturn(List.of(author, sameOwner, publicOther, privateOther));

        List<AgentEntity> visible = resolver.listVisibleTargets("s1", "review");

        assertThat(visible).extracting(AgentEntity::getId).containsExactly(2L);
    }

    @Test
    void resolveVisibleTarget_acceptsUniqueFuzzyName() {
        AgentEntity author = agent(1L, "Main", 10L, true, "active");
        AgentEntity target = agent(2L, "Session Analyzer", 10L, false, "active");
        SessionEntity session = session("s1", 1L);

        when(sessionRepository.findById("s1")).thenReturn(Optional.of(session));
        when(agentRepository.findById(1L)).thenReturn(Optional.of(author));
        when(agentRepository.findAll()).thenReturn(List.of(author, target));

        AgentEntity resolved = resolver.resolveVisibleTarget("s1", null, "analyzer");

        assertThat(resolved.getId()).isEqualTo(2L);
    }

    @Test
    void resolveVisibleTarget_rejectsInvisibleId() {
        AgentEntity author = agent(1L, "Main", 10L, true, "active");
        AgentEntity target = agent(4L, "Private Other", 99L, false, "active");
        SessionEntity session = session("s1", 1L);

        when(sessionRepository.findById("s1")).thenReturn(Optional.of(session));
        when(agentRepository.findById(1L)).thenReturn(Optional.of(author));
        when(agentRepository.findById(4L)).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> resolver.resolveVisibleTarget("s1", 4L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not visible");
    }

    private static SessionEntity session(String id, Long agentId) {
        SessionEntity session = new SessionEntity();
        session.setId(id);
        session.setAgentId(agentId);
        return session;
    }

    private static AgentEntity agent(Long id, String name, Long ownerId, boolean isPublic, String status) {
        AgentEntity agent = new AgentEntity();
        agent.setId(id);
        agent.setName(name);
        agent.setDescription(name + " description");
        agent.setOwnerId(ownerId);
        agent.setPublic(isPublic);
        agent.setStatus(status);
        return agent;
    }
}
