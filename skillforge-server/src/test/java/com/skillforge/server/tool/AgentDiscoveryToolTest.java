package com.skillforge.server.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.service.AgentTargetResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentDiscoveryToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void execute_returnsVisibleAgentsAsJson() throws Exception {
        AgentTargetResolver resolver = mock(AgentTargetResolver.class);
        AgentEntity agent = new AgentEntity();
        agent.setId(7L);
        agent.setName("Session Analyzer");
        agent.setDescription("Analyzes traces");
        agent.setPublic(true);
        agent.setSkillIds("[\"GetTrace\"]");
        agent.setToolIds("[\"GetSessionMessages\"]");
        when(resolver.listVisibleTargets("s1", "trace")).thenReturn(List.of(agent));

        AgentDiscoveryTool tool = new AgentDiscoveryTool(resolver, objectMapper);
        SkillResult result = tool.execute(Map.of("query", "trace"), new SkillContext(null, "s1", 1L));

        assertThat(result.isSuccess()).isTrue();
        JsonNode root = objectMapper.readTree(result.getOutput());
        assertThat(root.path("count").asInt()).isEqualTo(1);
        assertThat(root.path("agents").get(0).path("name").asText()).isEqualTo("Session Analyzer");
        assertThat(root.path("agents").get(0).path("visibility").asText()).isEqualTo("public");
        assertThat(root.path("agents").get(0).path("skills").get(0).asText()).isEqualTo("GetTrace");
        verify(resolver).listVisibleTargets("s1", "trace");
    }

    @Test
    void execute_requiresSessionContext() {
        AgentDiscoveryTool tool = new AgentDiscoveryTool(mock(AgentTargetResolver.class), objectMapper);

        SkillResult result = tool.execute(Map.of(), new SkillContext());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("No session");
    }
}
