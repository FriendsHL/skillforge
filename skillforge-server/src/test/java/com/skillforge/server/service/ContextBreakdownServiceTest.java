package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.context.ContextProvider;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.SkillDefinition;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.core.skill.view.SessionSkillResolver;
import com.skillforge.core.skill.view.SessionSkillView;
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
 * Plan r2 §5 + Code Judge r1 B-BE-3 — verify ContextBreakdownService renders the
 * skill list using {@link SessionSkillView#all()} (the agent-authorised subset),
 * not {@code skillRegistry.getAllSkillDefinitions()} (the registry-wide set).
 * <p>The original B-BE-3 bug listed disabled / unauthorised skills in the breakdown
 * panel — token estimate too high, and in multi-user scenarios leaks names.
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
    @DisplayName("renderSkillsList only lists view.all() — disabled system skills are excluded")
    void renderSkillsList_filtersByView() {
        // Registry-wide: 3 skills exist (skillhub, github, my-private)
        // View: agent has disabled "github" → only skillhub + my-private are authorised.
        SkillDefinition skillhub = def("skillhub", true);
        SkillDefinition github = def("github", true);
        SkillDefinition mySkill = def("my-private", false);

        Map<String, SkillDefinition> allowed = new LinkedHashMap<>();
        allowed.put("skillhub", skillhub);
        allowed.put("my-private", mySkill);
        SessionSkillView view = new SessionSkillView(allowed,
                Set.of("skillhub"), Set.of("my-private"));

        when(sessionSkillResolver.resolveFor(any(AgentDefinition.class))).thenReturn(view);

        AgentDefinition agentDef = new AgentDefinition();
        agentDef.setId("42");

        String block = service.renderSkillsListBlock(agentDef);

        assertThat(block).contains("skillhub");
        assertThat(block).contains("my-private");
        assertThat(block)
                .as("disabled system skill 'github' must NOT appear in breakdown — view-filtered")
                .doesNotContain("github");
    }

    @Test
    @DisplayName("renderSkillsList: empty view → empty block (does not fall back to registry)")
    void renderSkillsList_emptyView_returnsEmpty() {
        when(sessionSkillResolver.resolveFor(any(AgentDefinition.class)))
                .thenReturn(SessionSkillView.EMPTY);

        AgentDefinition agentDef = new AgentDefinition();
        String block = service.renderSkillsListBlock(agentDef);

        assertThat(block).isEmpty();
    }

    @Test
    @DisplayName("renderSkillsList: resolver throws → fail-secure to empty (does NOT leak registry)")
    void renderSkillsList_resolverFailure_failSecure() {
        when(sessionSkillResolver.resolveFor(any(AgentDefinition.class)))
                .thenThrow(new RuntimeException("resolver boom"));

        AgentDefinition agentDef = new AgentDefinition();
        String block = service.renderSkillsListBlock(agentDef);

        assertThat(block)
                .as("resolver exception must NOT fall back to registry-wide list")
                .isEmpty();
    }
}
