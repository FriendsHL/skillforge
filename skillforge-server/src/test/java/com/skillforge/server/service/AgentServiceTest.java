package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.context.BehaviorRuleRegistry;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.exception.AgentNotFoundException;
import com.skillforge.server.repository.AgentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    @Test
    @DisplayName("updateAgent throws AgentNotFoundException when id missing")
    void updateAgent_notFound_throwsAgentNotFoundException() {
        // Arrange: repository returns empty for the requested id
        when(agentRepository.findById(999L)).thenReturn(Optional.empty());

        var payload = new AgentEntity();
        payload.setName("irrelevant");

        // Act + Assert: service throws AgentNotFoundException and never saves
        assertThatThrownBy(() -> agentService.updateAgent(999L, payload))
                .isInstanceOf(AgentNotFoundException.class)
                .hasMessageContaining("999");

        verify(agentRepository, never()).save(any(AgentEntity.class));
    }

    @Test
    @DisplayName("updateAgent full payload replaces every nullable field")
    void updateAgent_fullPayload_replacesAllFields() {
        // Arrange: existing agent populated with sentinel values
        var existing = new AgentEntity();
        existing.setId(2L);
        existing.setName("old-name");
        existing.setDescription("old-desc");
        existing.setModelId("old-model");
        existing.setSystemPrompt("old-sys");
        existing.setSkillIds("[\"old-skill\"]");
        existing.setToolIds("[\"old-tool\"]");
        existing.setConfig("{\"old\":true}");
        existing.setSoulPrompt("old-soul");
        existing.setToolsPrompt("old-tools");
        existing.setBehaviorRules("{\"builtinRuleIds\":[],\"customRules\":[]}");
        existing.setLifecycleHooks("{\"version\":1,\"hooks\":{}}");
        existing.setOwnerId(100L);
        existing.setStatus("draft");
        existing.setMaxLoops(10);
        existing.setExecutionMode("local");
        existing.setPublic(false);

        when(agentRepository.findById(2L)).thenReturn(Optional.of(existing));
        when(agentRepository.save(any(AgentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act: full PUT payload with every nullable field set to a new value
        var full = new AgentEntity();
        full.setName("new-name");
        full.setDescription("new-desc");
        full.setModelId("new-model");
        full.setSystemPrompt("new-sys");
        full.setSkillIds("[\"new-skill\"]");
        full.setToolIds("[\"new-tool\"]");
        full.setConfig("{\"new\":true}");
        full.setSoulPrompt("new-soul");
        full.setToolsPrompt("new-tools");
        full.setBehaviorRules("{\"builtinRuleIds\":[\"ruleA\"],\"customRules\":[]}");
        full.setLifecycleHooks("{\"version\":2,\"hooks\":{}}");
        full.setOwnerId(200L);
        full.setStatus("active");
        full.setMaxLoops(42);
        full.setExecutionMode("remote");
        full.setPublic(true);

        var result = agentService.updateAgent(2L, full);

        // Assert: every field reflects the new payload
        assertThat(result.getName()).isEqualTo("new-name");
        assertThat(result.getDescription()).isEqualTo("new-desc");
        assertThat(result.getModelId()).isEqualTo("new-model");
        assertThat(result.getSystemPrompt()).isEqualTo("new-sys");
        assertThat(result.getSkillIds()).isEqualTo("[\"new-skill\"]");
        assertThat(result.getToolIds()).isEqualTo("[\"new-tool\"]");
        assertThat(result.getConfig()).isEqualTo("{\"new\":true}");
        assertThat(result.getSoulPrompt()).isEqualTo("new-soul");
        assertThat(result.getToolsPrompt()).isEqualTo("new-tools");
        assertThat(result.getBehaviorRules()).contains("ruleA");
        assertThat(result.getLifecycleHooks()).contains("\"version\":2");
        assertThat(result.getOwnerId()).isEqualTo(200L);
        assertThat(result.getStatus()).isEqualTo("active");
        assertThat(result.getMaxLoops()).isEqualTo(42);
        assertThat(result.getExecutionMode()).isEqualTo("remote");
        assertThat(result.isPublic()).isTrue();
    }

    @Test
    @DisplayName("updateAgent all-null payload preserves existing; isPublic is primitive and always written")
    void updateAgent_allNullPayload_preservesExisting() {
        // Arrange: existing with isPublic=true and populated strings
        var existing = new AgentEntity();
        existing.setId(3L);
        existing.setName("keep-name");
        existing.setDescription("keep-desc");
        existing.setModelId("keep-model");
        existing.setSystemPrompt("keep-sys");
        existing.setBehaviorRules("{\"builtinRuleIds\":[\"keep\"],\"customRules\":[]}");
        existing.setStatus("active");
        existing.setMaxLoops(7);
        existing.setPublic(true);

        when(agentRepository.findById(3L)).thenReturn(Optional.of(existing));
        when(agentRepository.save(any(AgentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act: payload leaves all nullable fields null; primitive isPublic defaults to false
        var empty = new AgentEntity();

        var result = agentService.updateAgent(3L, empty);

        // Assert: all nullable fields preserved
        assertThat(result.getName()).isEqualTo("keep-name");
        assertThat(result.getDescription()).isEqualTo("keep-desc");
        assertThat(result.getModelId()).isEqualTo("keep-model");
        assertThat(result.getSystemPrompt()).isEqualTo("keep-sys");
        assertThat(result.getBehaviorRules()).contains("keep");
        assertThat(result.getStatus()).isEqualTo("active");
        assertThat(result.getMaxLoops()).isEqualTo(7);

        // isPublic is a primitive: we cannot distinguish "unset" from "false",
        // so the default (false) in the empty payload DOES overwrite existing.
        // Lock this documented current behavior — changing it is deferred to a
        // separate PR that might switch the field to Boolean.
        assertThat(result.isPublic()).isFalse();
    }
}
