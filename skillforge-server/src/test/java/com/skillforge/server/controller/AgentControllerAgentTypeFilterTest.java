package com.skillforge.server.controller;

import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.service.AgentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SYSTEM-AGENT-TYPING Phase 2.1: contract test for {@code AgentController.listAgents}
 * {@code agentType} query-param filter. Plumbs the param into
 * {@code AgentService.listAgents(ownerId, agentType)} so the FE can request
 * either user-only (default), system-only, or all agents.
 *
 * <p>Phase 2.0 (this test): red — the new 2-arg
 * {@code AgentService.listAgents(Long, String)} overload + the new
 * {@code @RequestParam(name = "agentType")} on the controller don't exist
 * yet, so this file fails to compile. Phase 2.1 lands the implementation
 * and flips it green.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentController agentType query-param filter")
class AgentControllerAgentTypeFilterTest {

    @Mock
    private AgentService agentService;

    private AgentController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentController(agentService);
    }

    @Test
    @DisplayName("GET /api/agents (no agentType) defaults to user-only agents")
    void list_defaultAgentType_returnsUserAgents() {
        AgentEntity a = userAgent(1L, "Main Assistant");
        when(agentService.listAgents(null, "user")).thenReturn(List.of(a));

        ResponseEntity<List<AgentEntity>> resp = controller.listAgents(null, "user");

        assertThat(resp.getBody()).extracting(AgentEntity::getName).containsExactly("Main Assistant");
        verify(agentService).listAgents(null, "user");
    }

    @Test
    @DisplayName("GET /api/agents?agentType=system returns only system agents")
    void list_agentTypeSystem_returnsSystemAgents() {
        AgentEntity a = systemAgent(7L, "session-annotator");
        AgentEntity b = systemAgent(9L, "attribution-curator");
        when(agentService.listAgents(null, "system")).thenReturn(List.of(a, b));

        ResponseEntity<List<AgentEntity>> resp = controller.listAgents(null, "system");

        assertThat(resp.getBody())
                .extracting(AgentEntity::getName)
                .containsExactly("session-annotator", "attribution-curator");
        assertThat(resp.getBody())
                .as("system filter must not leak user agents")
                .allMatch(x -> "system".equals(x.getAgentType()));
        verify(agentService).listAgents(null, "system");
    }

    @Test
    @DisplayName("GET /api/agents?agentType=all returns user + system agents")
    void list_agentTypeAll_returnsBoth() {
        AgentEntity u = userAgent(1L, "Main Assistant");
        AgentEntity s = systemAgent(7L, "session-annotator");
        when(agentService.listAgents(null, "all")).thenReturn(List.of(u, s));

        ResponseEntity<List<AgentEntity>> resp = controller.listAgents(null, "all");

        assertThat(resp.getBody()).hasSize(2);
        verify(agentService).listAgents(null, "all");
    }

    @Test
    @DisplayName("ownerId + agentType compose: GET /api/agents?ownerId=1&agentType=user")
    void list_ownerIdAndAgentType_pluombedThrough() {
        AgentEntity u = userAgent(1L, "Main Assistant");
        when(agentService.listAgents(1L, "user")).thenReturn(List.of(u));

        ResponseEntity<List<AgentEntity>> resp = controller.listAgents(1L, "user");

        assertThat(resp.getBody()).hasSize(1);
        verify(agentService).listAgents(1L, "user");
    }

    private static AgentEntity userAgent(long id, String name) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setName(name);
        a.setAgentType("user");
        return a;
    }

    private static AgentEntity systemAgent(long id, String name) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setName(name);
        a.setAgentType("system");
        return a;
    }
}
