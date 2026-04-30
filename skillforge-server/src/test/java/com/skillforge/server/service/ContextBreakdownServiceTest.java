package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.context.ContextProvider;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.SkillDefinition;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.core.skill.view.SessionSkillResolver;
import com.skillforge.core.skill.view.SessionSkillView;
import com.skillforge.server.dto.ContextBreakdownDto;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SessionEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Verifies the context breakdown follows the same skill exposure model as the
 * runtime request: no prompt-side skill list, one view-filtered Skill loader schema.
 */
@ExtendWith(MockitoExtension.class)
class ContextBreakdownServiceTest {

    @Mock private AgentService agentService;
    @Mock private SessionService sessionService;
    @Mock private SkillRegistry skillRegistry;
    @Mock private MemoryService memoryService;
    @Mock private UserConfigService userConfigService;
    @Mock private SessionSkillResolver sessionSkillResolver;

    private ContextBreakdownService service;

    @BeforeEach
    void setUp() {
        service = new ContextBreakdownService(
                agentService, sessionService, skillRegistry, memoryService,
                userConfigService, List.<ContextProvider>of(), new ObjectMapper(),
                sessionSkillResolver);
    }

    private SkillDefinition def(String name, boolean isSystem) {
        SkillDefinition d = new SkillDefinition();
        d.setName(name);
        d.setDescription("desc-" + name);
        d.setSystem(isSystem);
        return d;
    }

    @Test
    @DisplayName("renderSkillsList is empty because skills are exposed by the Skill loader tool")
    void renderSkillsList_returnsEmptyWhenSkillsMoveToLoaderTool() {
        AgentDefinition agentDef = new AgentDefinition();
        agentDef.setId("42");

        String block = service.renderSkillsListBlock(agentDef);

        assertThat(block).isEmpty();
    }

    @Test
    @DisplayName("breakdown counts visible skills as one Skill loader tool schema")
    void breakdown_countsSkillLoaderSchema() {
        SkillDefinition skillhub = def("skillhub", true);
        SkillDefinition mySkill = def("my-private", false);
        Map<String, SkillDefinition> allowed = new LinkedHashMap<>();
        allowed.put("skillhub", skillhub);
        allowed.put("my-private", mySkill);
        SessionSkillView view = new SessionSkillView(allowed,
                Set.of("skillhub"), Set.of("my-private"));

        AgentDefinition agentDef = new AgentDefinition();
        agentDef.setId("42");
        AgentEntity agentEntity = new AgentEntity();
        agentEntity.setId(42L);
        agentEntity.setModelId("gpt-4o");
        SessionEntity session = new SessionEntity();
        session.setId("s1");
        session.setAgentId(42L);

        when(agentService.getAgent(42L)).thenReturn(agentEntity);
        when(agentService.toAgentDefinition(agentEntity)).thenReturn(agentDef);
        when(sessionService.getContextMessages("s1")).thenReturn(List.of());
        when(sessionSkillResolver.resolveFor(agentDef)).thenReturn(view);

        ContextBreakdownDto breakdown = service.breakdown(session, 7L);

        ContextBreakdownDto.Segment tools = breakdown.segments().stream()
                .filter(s -> "tool_schemas".equals(s.key()))
                .findFirst()
                .orElseThrow();
        assertThat(tools.tokens())
                .as("one Skill loader schema should be counted even when no Java tools are registered")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("renderSkillsList: empty view → empty block (does not fall back to registry)")
    void renderSkillsList_emptyView_returnsEmpty() {
        AgentDefinition agentDef = new AgentDefinition();
        String block = service.renderSkillsListBlock(agentDef);

        assertThat(block).isEmpty();
    }

    @Test
    @DisplayName("renderSkillsList: no resolver access needed because prompt-side skill list is gone")
    void renderSkillsList_doesNotResolveSkills() {
        AgentDefinition agentDef = new AgentDefinition();
        String block = service.renderSkillsListBlock(agentDef);

        assertThat(block)
                .as("resolver exception must NOT fall back to registry-wide list")
                .isEmpty();
    }
}
