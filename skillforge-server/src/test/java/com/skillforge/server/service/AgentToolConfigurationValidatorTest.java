package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.repository.AgentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentToolConfigurationValidatorTest {

    private final SkillRegistry registry = new SkillRegistry();
    private final AgentRepository repository = mock(AgentRepository.class);
    private AgentToolConfigurationValidator validator;

    @BeforeEach
    void setUp() {
        register("GetTrace");
        register("SessionAnnotationRead");
        register("SubAgent");
        validator = new AgentToolConfigurationValidator(registry, new ObjectMapper(), repository);
    }

    @Test
    void inspect_restrictedAgentMissingRequiredTool_reportsContractMismatch() {
        AgentEntity agent = agent(
                "[\"GetTrace\"]",
                "{\"required_tool_ids\":[\"GetTrace\",\"SessionAnnotationRead\"]}");

        assertThat(validator.inspect(agent))
                .containsExactly(new AgentToolConfigurationValidator.ConfigurationIssue(
                        "REQUIRED_TOOL_NOT_ALLOWED", List.of("SessionAnnotationRead")));
        assertThatThrownBy(() -> validator.validate(agent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("REQUIRED_TOOL_NOT_ALLOWED")
                .hasMessageContaining("SessionAnnotationRead");
    }

    @Test
    void inspect_configuredUnknownTool_reportsUnavailableRuntimeTool() {
        AgentEntity agent = agent("[\"GetTrace\",\"MissingTool\"]", "{}");

        assertThat(validator.inspect(agent))
                .containsExactly(new AgentToolConfigurationValidator.ConfigurationIssue(
                        "TOOL_NOT_REGISTERED", List.of("MissingTool")));
        assertThatCode(() -> validator.validate(agent)).doesNotThrowAnyException();
    }

    @Test
    void inspect_requiredUnknownTool_reportsUnavailablePromptDependency() {
        AgentEntity agent = agent(
                "[\"GetTrace\"]",
                "{\"required_tool_ids\":[\"NeverRegistered\"]}");

        assertThat(validator.inspect(agent))
                .containsExactly(
                        new AgentToolConfigurationValidator.ConfigurationIssue(
                                "REQUIRED_TOOL_NOT_REGISTERED", List.of("NeverRegistered")),
                        new AgentToolConfigurationValidator.ConfigurationIssue(
                                "REQUIRED_TOOL_NOT_ALLOWED", List.of("NeverRegistered")));
    }

    @Test
    void validate_unrestrictedAgentAllowsRegisteredRequiredTools() {
        AgentEntity agent = agent(
                null,
                "{\"required_tool_ids\":[\"GetTrace\",\"SessionAnnotationRead\"]}");

        assertThatCode(() -> validator.validate(agent)).doesNotThrowAnyException();
    }

    @Test
    void inspect_invalidToolIdsJson_reportsConfigurationShape() {
        AgentEntity agent = agent("not-json", "{}");

        assertThat(validator.inspect(agent))
                .containsExactly(new AgentToolConfigurationValidator.ConfigurationIssue(
                        "TOOL_IDS_INVALID", List.of()));
    }

    private void register(String name) {
        Tool tool = mock(Tool.class);
        when(tool.getName()).thenReturn(name);
        registry.registerTool(tool);
    }

    private static AgentEntity agent(String toolIds, String config) {
        AgentEntity agent = new AgentEntity();
        agent.setId(7L);
        agent.setName("system-agent");
        agent.setToolIds(toolIds);
        agent.setConfig(config);
        return agent;
    }
}
