package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.context.BehaviorRuleRegistry;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.repository.AgentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SYSTEM-AGENT-TYPING Phase 2.1: contract for the new {@code listAgents(Long, String)}
 * overload (PRD F2 / F5). Covers the {@code ownerId × agentType} dispatch matrix +
 * the {@code normaliseAgentType} fallback for unknown / blank / "all" values.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentService.listAgents(ownerId, agentType)")
class AgentServiceListAgentTypeTest {

    @Mock
    private AgentRepository agentRepository;

    @Mock
    private BehaviorRuleRegistry behaviorRuleRegistry;

    private AgentService agentService;

    @BeforeEach
    void setUp() {
        agentService = new AgentService(agentRepository, new ObjectMapper(), behaviorRuleRegistry);
    }

    @Test
    @DisplayName("listAgents(null, 'user') → findByAgentType('user')")
    void noOwner_userType_callsFindByAgentType() {
        AgentEntity a = userAgent(1L);
        when(agentRepository.findByAgentType("user")).thenReturn(List.of(a));

        List<AgentEntity> result = agentService.listAgents(null, "user");

        assertThat(result).containsExactly(a);
        verify(agentRepository).findByAgentType("user");
        verify(agentRepository, never()).findAll();
    }

    @Test
    @DisplayName("listAgents(null, 'system') → findByAgentType('system')")
    void noOwner_systemType_callsFindByAgentType() {
        AgentEntity s = systemAgent(7L);
        when(agentRepository.findByAgentType("system")).thenReturn(List.of(s));

        List<AgentEntity> result = agentService.listAgents(null, "system");

        assertThat(result).containsExactly(s);
        verify(agentRepository).findByAgentType("system");
    }

    @Test
    @DisplayName("listAgents(null, 'all') → findAll() (no type filter)")
    void noOwner_allType_callsFindAll() {
        AgentEntity u = userAgent(1L);
        AgentEntity s = systemAgent(7L);
        when(agentRepository.findAll()).thenReturn(List.of(u, s));

        List<AgentEntity> result = agentService.listAgents(null, "all");

        assertThat(result).containsExactly(u, s);
        verify(agentRepository).findAll();
        verify(agentRepository, never()).findByAgentType("all");
    }

    @Test
    @DisplayName("listAgents(null, null) preserves Phase-1 'no filter' semantics → findAll()")
    void noOwner_nullType_callsFindAll() {
        when(agentRepository.findAll()).thenReturn(List.of());

        agentService.listAgents(null, null);

        verify(agentRepository).findAll();
    }

    @Test
    @DisplayName("1-arg listAgents(ownerId) delegates to 2-arg with agentType=null (backward-compat)")
    void singleArgOverload_delegatesToTwoArgWithNullType() {
        AgentEntity owned = userAgent(1L);
        when(agentRepository.findByOwnerId(42L)).thenReturn(List.of(owned));

        List<AgentEntity> result = agentService.listAgents(42L);

        assertThat(result).containsExactly(owned);
        verify(agentRepository).findByOwnerId(42L);
        verify(agentRepository, never()).findByAgentType(any());
    }

    @Test
    @DisplayName("listAgents(ownerId, 'user') intersects: ownerId → findByOwnerId → filter user")
    void ownerAndType_intersectsClientSide() {
        AgentEntity user1 = userAgent(1L);
        AgentEntity sys2 = systemAgent(7L);
        sys2.setOwnerId(42L);  // hypothetical co-ownership
        user1.setOwnerId(42L);
        when(agentRepository.findByOwnerId(42L)).thenReturn(List.of(user1, sys2));

        List<AgentEntity> result = agentService.listAgents(42L, "user");

        assertThat(result)
                .as("owner=42 narrowed to agentType=user should drop sys2")
                .containsExactly(user1);
        verify(agentRepository).findByOwnerId(42L);
        verify(agentRepository, never()).findByAgentType(any());
    }

    @Test
    @DisplayName("listAgents with unrecognised agentType defensively falls back to 'user'")
    void unrecognisedAgentType_fallsBackToUser() {
        AgentEntity u = userAgent(1L);
        when(agentRepository.findByAgentType("user")).thenReturn(List.of(u));

        List<AgentEntity> result = agentService.listAgents(null, "bogus");

        assertThat(result).containsExactly(u);
        verify(agentRepository).findByAgentType("user");
    }

    @Test
    @DisplayName("listAgents accepts case-insensitive 'SYSTEM' → coerces to lower-case 'system'")
    void caseInsensitiveAgentType() {
        AgentEntity s = systemAgent(7L);
        when(agentRepository.findByAgentType("system")).thenReturn(List.of(s));

        List<AgentEntity> result = agentService.listAgents(null, "SYSTEM");

        assertThat(result).containsExactly(s);
        verify(agentRepository).findByAgentType("system");
    }

    private static AgentEntity userAgent(long id) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setName("user-" + id);
        a.setAgentType("user");
        return a;
    }

    private static AgentEntity systemAgent(long id) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setName("system-" + id);
        a.setAgentType("system");
        return a;
    }

    @SuppressWarnings("unused")
    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
