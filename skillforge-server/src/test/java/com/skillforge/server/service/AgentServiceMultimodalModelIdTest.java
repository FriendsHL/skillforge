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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * MULTIMODAL-MVP Task #1: AgentService partial-update semantics for the new
 * {@code multimodalModelId} column. Mirrors the conventions in
 * {@link AgentServiceTest}:
 *
 * <ul>
 *   <li>null in the payload → leave existing alone</li>
 *   <li>non-blank string → overwrite</li>
 *   <li>blank string ({@code ""}) → clear to null (the FE-BE-agreed
 *       sentinel for "remove multimodal model")</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AgentServiceMultimodalModelIdTest {

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
    @DisplayName("createAgent persists multimodalModelId when provided")
    void createAgent_persistsMultimodalModelId() {
        var agent = new AgentEntity();
        agent.setName("vision-bot");
        agent.setMultimodalModelId("mimo-v2-omni");
        when(agentRepository.save(any(AgentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        AgentEntity result = agentService.createAgent(agent);

        assertThat(result.getMultimodalModelId()).isEqualTo("mimo-v2-omni");
    }

    @Test
    @DisplayName("updateAgent sets multimodalModelId when payload non-null")
    void updateAgent_setsMultimodalModelId() {
        var existing = new AgentEntity();
        existing.setId(1L);
        existing.setName("x");
        when(agentRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(agentRepository.save(any(AgentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var patch = new AgentEntity();
        patch.setMultimodalModelId("mimo-v2-omni");

        AgentEntity result = agentService.updateAgent(1L, patch);
        assertThat(result.getMultimodalModelId()).isEqualTo("mimo-v2-omni");
    }

    @Test
    @DisplayName("updateAgent empty string clears multimodalModelId to null")
    void updateAgent_emptyStringClearsMultimodalModelId() {
        var existing = new AgentEntity();
        existing.setId(2L);
        existing.setName("x");
        existing.setMultimodalModelId("mimo-v2-omni");
        when(agentRepository.findById(2L)).thenReturn(Optional.of(existing));
        when(agentRepository.save(any(AgentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var patch = new AgentEntity();
        patch.setMultimodalModelId("");

        AgentEntity result = agentService.updateAgent(2L, patch);
        assertThat(result.getMultimodalModelId()).isNull();
    }

    @Test
    @DisplayName("updateAgent null multimodalModelId preserves existing value")
    void updateAgent_nullMultimodalModelId_preservesExisting() {
        var existing = new AgentEntity();
        existing.setId(3L);
        existing.setName("x");
        existing.setMultimodalModelId("mimo-v2-omni");
        when(agentRepository.findById(3L)).thenReturn(Optional.of(existing));
        when(agentRepository.save(any(AgentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        // Partial payload touching only `name` — multimodalModelId left null in the
        // patch must NOT wipe the existing value.
        var patch = new AgentEntity();
        patch.setName("renamed");

        AgentEntity result = agentService.updateAgent(3L, patch);
        assertThat(result.getMultimodalModelId()).isEqualTo("mimo-v2-omni");
        assertThat(result.getName()).isEqualTo("renamed");
    }

    @Test
    @DisplayName("updateAgent whitespace-only multimodalModelId clears to null")
    void updateAgent_whitespaceClearsMultimodalModelId() {
        // Defensive: treat "   " same as "" so accidental FE form-field trim differences
        // can't slip through as a literal whitespace model id (which would never resolve).
        var existing = new AgentEntity();
        existing.setId(4L);
        existing.setName("x");
        existing.setMultimodalModelId("mimo-v2-omni");
        when(agentRepository.findById(4L)).thenReturn(Optional.of(existing));
        when(agentRepository.save(any(AgentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var patch = new AgentEntity();
        patch.setMultimodalModelId("   ");

        AgentEntity result = agentService.updateAgent(4L, patch);
        assertThat(result.getMultimodalModelId()).isNull();
    }
}
