package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.context.BehaviorRuleRegistry;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.repository.AgentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentServiceToolConfigurationTest {

    private final AgentRepository repository = mock(AgentRepository.class);
    private final AgentToolConfigurationValidator validator =
            mock(AgentToolConfigurationValidator.class);
    private AgentService service;

    @BeforeEach
    void setUp() {
        service = new AgentService(
                repository,
                new ObjectMapper(),
                mock(BehaviorRuleRegistry.class),
                validator);
    }

    @Test
    void createAgent_validatesToolContractBeforeSave() {
        AgentEntity agent = new AgentEntity();
        when(repository.save(agent)).thenReturn(agent);

        service.createAgent(agent);

        verify(validator).validate(same(agent));
    }

    @Test
    void updateAgent_validatesMergedToolContractBeforeSave() {
        AgentEntity existing = new AgentEntity();
        existing.setId(7L);
        existing.setToolIds("[\"GetTrace\"]");
        AgentEntity update = new AgentEntity();
        update.setConfig("{\"required_tool_ids\":[\"SessionAnnotationRead\"]}");
        when(repository.findById(7L)).thenReturn(Optional.of(existing));
        when(repository.save(existing)).thenReturn(existing);

        service.updateAgent(7L, update);

        verify(validator).validate(same(existing));
    }

    @Test
    void toAgentDefinition_validatesPersistedToolContractBeforeRuntime() {
        AgentEntity agent = new AgentEntity();
        agent.setId(7L);

        service.toAgentDefinition(agent);

        verify(validator).validate(same(agent));
    }
}
