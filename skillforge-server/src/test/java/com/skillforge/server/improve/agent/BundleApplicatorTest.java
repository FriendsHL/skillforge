package com.skillforge.server.improve.agent;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.BehaviorRuleVersionEntity;
import com.skillforge.server.entity.PromptVersionEntity;
import com.skillforge.server.improve.behavior.BehaviorRuleVersionToCustomRulesMapper;
import com.skillforge.server.repository.BehaviorRuleVersionRepository;
import com.skillforge.server.repository.PromptVersionRepository;
import com.skillforge.server.service.AgentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * AUTOEVOLVE-AGENT-LEVEL-BUNDLE — {@link BundleApplicator} unit tests
 * (§2.1 / §7 W7 + Phase 2 §8 #2 rule branch).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BundleApplicator")
class BundleApplicatorTest {

    @Mock private AgentService agentService;
    @Mock private PromptVersionRepository promptVersionRepository;
    @Mock private BehaviorRuleVersionRepository behaviorRuleVersionRepository;

    // Mirror Spring Boot's auto-configured ObjectMapper (FAIL_ON_UNKNOWN_PROPERTIES
    // disabled) so the JSON-roundtrip clone tolerates AgentDefinition's derived
    // read-only getters (getMaxTokens / getTemperature / getMaxContextTokens).
    private final ObjectMapper objectMapper =
            new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private BundleApplicator applicator;
    private AgentEntity base;

    @BeforeEach
    void setUp() {
        applicator = new BundleApplicator(
                agentService, promptVersionRepository, behaviorRuleVersionRepository,
                new BehaviorRuleVersionToCustomRulesMapper(objectMapper),
                new AgentDefinitionCloner(objectMapper));
        base = new AgentEntity();
        base.setId(7L);

        AgentDefinition def = new AgentDefinition();
        def.setId("7");
        def.setSystemPrompt("ACTIVE PROMPT");
        lenient().when(agentService.toAgentDefinition(base)).thenReturn(def);
    }

    private PromptVersionEntity promptVersion(String id, String agentId, String content) {
        PromptVersionEntity v = new PromptVersionEntity();
        v.setId(id);
        v.setAgentId(agentId);
        v.setContent(content);
        return v;
    }

    private BehaviorRuleVersionEntity ruleVersion(String id, String agentId, String rulesJson) {
        BehaviorRuleVersionEntity v = new BehaviorRuleVersionEntity();
        v.setId(id);
        v.setAgentId(agentId);
        v.setRulesJson(rulesJson);
        return v;
    }

    @Test
    @DisplayName("null bundle returns the agent's current definition unchanged")
    void nullBundle_baseUnchanged() {
        AgentDefinition result = applicator.apply(base, null);
        assertThat(result.getSystemPrompt()).isEqualTo("ACTIVE PROMPT");
    }

    @Test
    @DisplayName("all-null pointers returns the agent's current definition unchanged")
    void allNullPointers_baseUnchanged() {
        AgentDefinition result = applicator.apply(base, new Bundle(null, null));
        assertThat(result.getSystemPrompt()).isEqualTo("ACTIVE PROMPT");
    }

    @Test
    @DisplayName("prompt pointer swaps systemPrompt to the version's content")
    void promptBranch_swapsSystemPrompt() {
        when(promptVersionRepository.findById("pv1"))
                .thenReturn(Optional.of(promptVersion("pv1", "7", "NEW PROMPT")));

        AgentDefinition result = applicator.apply(base, new Bundle("pv1", null));

        assertThat(result.getSystemPrompt()).isEqualTo("NEW PROMPT");
    }

    @Test
    @DisplayName("prompt pointer for a DIFFERENT agent fails loud (W7 ownership)")
    void promptBranch_crossAgent_throws() {
        when(promptVersionRepository.findById("pv9"))
                .thenReturn(Optional.of(promptVersion("pv9", "9", "OTHER AGENT PROMPT")));

        assertThatThrownBy(() -> applicator.apply(base, new Bundle("pv9", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("belongs to agent 9");
    }

    @Test
    @DisplayName("unknown prompt pointer fails loud (W7 dead pointer)")
    void promptBranch_unknownVersion_throws() {
        when(promptVersionRepository.findById("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> applicator.apply(base, new Bundle("ghost", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prompt version not found");
    }

    @Test
    @DisplayName("behavior_rule pointer appends the mapped rules to customRules (Phase 2 §8 #2)")
    void ruleBranch_appendsCustomRules() {
        String rulesJson = "[{\"id\":\"r1\",\"priority\":\"P1\",\"when\":\"X\",\"then\":\"do Y\"}]";
        when(behaviorRuleVersionRepository.findById("brv1"))
                .thenReturn(Optional.of(ruleVersion("brv1", "7", rulesJson)));

        AgentDefinition result = applicator.apply(base, new Bundle(null, "brv1"));

        assertThat(result.getBehaviorRules()).isNotNull();
        assertThat(result.getBehaviorRules().getCustomRules())
                .extracting(AgentDefinition.BehaviorRulesConfig.CustomRule::getText)
                .anySatisfy(t -> assertThat(t).contains("do Y"));
        // prompt left untouched when only the rule pointer is set
        assertThat(result.getSystemPrompt()).isEqualTo("ACTIVE PROMPT");
    }

    @Test
    @DisplayName("behavior_rule pointer for a DIFFERENT agent fails loud (W7 ownership)")
    void ruleBranch_crossAgent_throws() {
        when(behaviorRuleVersionRepository.findById("brv9"))
                .thenReturn(Optional.of(ruleVersion("brv9", "9", "[]")));

        assertThatThrownBy(() -> applicator.apply(base, new Bundle(null, "brv9")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("belongs to agent 9");
    }

    @Test
    @DisplayName("unknown behavior_rule pointer fails loud (W7 dead pointer)")
    void ruleBranch_unknownVersion_throws() {
        when(behaviorRuleVersionRepository.findById("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> applicator.apply(base, new Bundle(null, "ghost")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("behavior_rule version not found");
    }

    @Test
    @DisplayName("multi-surface bundle (prompt + rules) applies both")
    void multiSurfaceBundle_appliesBoth() {
        when(promptVersionRepository.findById("pv1"))
                .thenReturn(Optional.of(promptVersion("pv1", "7", "NEW PROMPT")));
        String rulesJson = "[{\"id\":\"r1\",\"priority\":\"P2\",\"then\":\"be concise\"}]";
        when(behaviorRuleVersionRepository.findById("brv1"))
                .thenReturn(Optional.of(ruleVersion("brv1", "7", rulesJson)));

        AgentDefinition result = applicator.apply(base, new Bundle("pv1", "brv1"));

        assertThat(result.getSystemPrompt()).isEqualTo("NEW PROMPT");
        assertThat(result.getBehaviorRules().getCustomRules())
                .extracting(AgentDefinition.BehaviorRulesConfig.CustomRule::getText)
                .anySatisfy(t -> assertThat(t).contains("be concise"));
    }
}
