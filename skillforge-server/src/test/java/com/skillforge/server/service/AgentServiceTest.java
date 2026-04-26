package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.context.BehaviorRuleRegistry;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.ReasoningEffort;
import com.skillforge.core.model.ThinkingMode;
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
        existing.setRole("leader");
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
        assertThat(result.getRole()).isEqualTo("leader");
        assertThat(result.getModelId()).isEqualTo("claude");
        assertThat(result.getSystemPrompt()).isEqualTo("original prompt");
        assertThat(result.getBehaviorRules()).contains("minimal-change");
    }

    @Test
    @DisplayName("createAgent defaults null isPublic to false")
    void createAgent_nullPublic_defaultsFalse() {
        var agent = new AgentEntity();
        agent.setName("new");
        when(agentRepository.save(any(AgentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        AgentEntity result = agentService.createAgent(agent);

        assertThat(result.isPublic()).isFalse();
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
        existing.setRole("leader");
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
        full.setRole("reviewer");
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
        assertThat(result.getRole()).isEqualTo("reviewer");
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
    @DisplayName("updateAgent all-null payload preserves existing including isPublic")
    void updateAgent_allNullPayload_preservesExisting() {
        // Arrange: existing with isPublic=true and populated strings
        var existing = new AgentEntity();
        existing.setId(3L);
        existing.setName("keep-name");
        existing.setDescription("keep-desc");
        existing.setRole("leader");
        existing.setModelId("keep-model");
        existing.setSystemPrompt("keep-sys");
        existing.setBehaviorRules("{\"builtinRuleIds\":[\"keep\"],\"customRules\":[]}");
        existing.setStatus("active");
        existing.setMaxLoops(7);
        existing.setPublic(true);

        when(agentRepository.findById(3L)).thenReturn(Optional.of(existing));
        when(agentRepository.save(any(AgentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act: payload leaves all fields null.
        var empty = new AgentEntity();

        var result = agentService.updateAgent(3L, empty);

        // Assert: all nullable fields preserved
        assertThat(result.getName()).isEqualTo("keep-name");
        assertThat(result.getDescription()).isEqualTo("keep-desc");
        assertThat(result.getRole()).isEqualTo("leader");
        assertThat(result.getModelId()).isEqualTo("keep-model");
        assertThat(result.getSystemPrompt()).isEqualTo("keep-sys");
        assertThat(result.getBehaviorRules()).contains("keep");
        assertThat(result.getStatus()).isEqualTo("active");
        assertThat(result.getMaxLoops()).isEqualTo(7);

        assertThat(result.isPublic()).isTrue();
    }

    @Test
    @DisplayName("updateAgent sets role to null when payload role is blank")
    void updateAgent_blankRole_clearsRole() {
        var existing = new AgentEntity();
        existing.setId(4L);
        existing.setName("keep-name");
        existing.setRole("reviewer");
        existing.setPublic(true);

        when(agentRepository.findById(4L)).thenReturn(Optional.of(existing));
        when(agentRepository.save(any(AgentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var patch = new AgentEntity();
        patch.setRole("   ");

        var result = agentService.updateAgent(4L, patch);
        assertThat(result.getRole()).isNull();
    }

    @Test
    @DisplayName("updateAgent sets thinkingMode and reasoningEffort when payload non-null")
    void updateAgent_setsThinkingAndEffort() {
        var existing = new AgentEntity();
        existing.setId(10L);
        existing.setName("x");
        when(agentRepository.findById(10L)).thenReturn(Optional.of(existing));
        when(agentRepository.save(any(AgentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var patch = new AgentEntity();
        patch.setThinkingMode("enabled");
        patch.setReasoningEffort("high");

        var result = agentService.updateAgent(10L, patch);
        assertThat(result.getThinkingMode()).isEqualTo("enabled");
        assertThat(result.getReasoningEffort()).isEqualTo("high");
    }

    @Test
    @DisplayName("updateAgent null thinkingMode preserves existing value")
    void updateAgent_nullThinking_preservesExisting() {
        var existing = new AgentEntity();
        existing.setId(11L);
        existing.setName("x");
        existing.setThinkingMode("disabled");
        existing.setReasoningEffort("low");
        when(agentRepository.findById(11L)).thenReturn(Optional.of(existing));
        when(agentRepository.save(any(AgentEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var patch = new AgentEntity();
        patch.setName("renamed");

        var result = agentService.updateAgent(11L, patch);
        assertThat(result.getThinkingMode()).isEqualTo("disabled");
        assertThat(result.getReasoningEffort()).isEqualTo("low");
    }

    @Test
    @DisplayName("toAgentDefinition maps thinkingMode and reasoningEffort enums")
    void toAgentDefinition_mapsThinkingAndEffort() {
        var entity = new AgentEntity();
        entity.setId(20L);
        entity.setName("x");
        entity.setThinkingMode("enabled");
        entity.setReasoningEffort("high");

        AgentDefinition def = agentService.toAgentDefinition(entity);
        assertThat(def.getThinkingMode()).isEqualTo(ThinkingMode.ENABLED);
        assertThat(def.getReasoningEffort()).isEqualTo(ReasoningEffort.HIGH);
    }

    @Test
    @DisplayName("toAgentDefinition leaves thinkingMode null when DB column null")
    void toAgentDefinition_nullDb_nullEnum() {
        var entity = new AgentEntity();
        entity.setId(21L);
        entity.setName("x");

        AgentDefinition def = agentService.toAgentDefinition(entity);
        assertThat(def.getThinkingMode()).isNull();
        assertThat(def.getReasoningEffort()).isNull();
    }
}
