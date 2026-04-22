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
 * AgentService 单元测试：验证 updateAgent 的部分更新语义——payload 中为 null 的字段
 * 不应覆盖 existing 中已有的值。
 */
@ExtendWith(MockitoExtension.class)
class AgentServiceTest {

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
    @DisplayName("updateAgent partial payload preserves untouched fields")
    void updateAgent_partialPayload_preservesOtherFields() {
        // Arrange: existing agent with populated fields
        var existing = new AgentEntity();
        existing.setId(1L);
        existing.setName("Original Name");
        existing.setDescription("Original Description");
        existing.setModelId("claude");
        existing.setSystemPrompt("original prompt");
        existing.setBehaviorRules(null);
        when(agentRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(agentRepository.save(any(AgentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act: partial PUT that only changes behaviorRules
        var partial = new AgentEntity();
        partial.setBehaviorRules("{\"builtinRuleIds\":[\"minimal-change\"],\"customRules\":[]}");

        var result = agentService.updateAgent(1L, partial);

        // Assert: only behaviorRules changed, other fields untouched
        assertThat(result.getName()).isEqualTo("Original Name");
        assertThat(result.getDescription()).isEqualTo("Original Description");
        assertThat(result.getModelId()).isEqualTo("claude");
        assertThat(result.getSystemPrompt()).isEqualTo("original prompt");
        assertThat(result.getBehaviorRules()).contains("minimal-change");
    }
}
