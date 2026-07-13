package com.skillforge.server.mobile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.SkillDefinition;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.repository.AgentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("MobileClientController")
class MobileClientControllerTest {

    private AgentRepository agentRepository;
    private MobileAgentAccessService mobileAgentAccessService;
    private SkillRegistry skillRegistry;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        agentRepository = mock(AgentRepository.class);
        skillRegistry = new SkillRegistry();
        registerSystemSkill("memory");
        registerSystemSkill("planning");
        registerTool("ZuluTool");
        registerTool("AlphaTool");
        mobileAgentAccessService = new MobileAgentAccessService(
                agentRepository, new ObjectMapper(), skillRegistry, 3L);
        mvc = MockMvcBuilders.standaloneSetup(new MobileClientController(mobileAgentAccessService))
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    @DisplayName("GET /api/mobile/client/me returns device bootstrap data and default agent")
    void me_returnsDefaultAgent() throws Exception {
        AgentEntity agent = agent(3L, "Main Assistant", null, true, "user", "active");
        when(agentRepository.findById(3L)).thenReturn(Optional.of(agent));

        mvc.perform(get("/api/mobile/client/me")
                        .with(principal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(1))
                .andExpect(jsonPath("$.device.deviceName").value("Youren iPhone"))
                .andExpect(jsonPath("$.defaultAgent.id").value(3))
                .andExpect(jsonPath("$.defaultAgent.name").value("Main Assistant"))
                .andExpect(jsonPath("$.features.chat").value(true));
    }

    @Test
    @DisplayName("GET /api/mobile/client/me rejects missing mobile principal")
    void me_rejectsMissingPrincipal() throws Exception {
        mvc.perform(get("/api/mobile/client/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/mobile/client/agents requires a mobile principal")
    void listAgents_withoutPrincipal_returnsUnauthorized() throws Exception {
        mvc.perform(get("/api/mobile/client/agents"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/mobile/client/agents requires agent:read")
    void listAgents_withoutAgentReadScope_returnsForbidden() throws Exception {
        mvc.perform(get("/api/mobile/client/agents")
                        .with(principal("chat:write")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/mobile/client/agents returns selectable safe configuration summaries")
    void listAgents_filtersAndReturnsSafeConfigurationSummaries() throws Exception {
        AgentEntity owned = agent(11L, "Owned", 1L, false, "user", "active");
        AgentEntity publicAgent = agent(12L, "Public", 2L, true, "user", "active");
        AgentEntity defaultAgent = agent(3L, "Main Assistant", null, true, "user", "active");
        AgentEntity system = agent(13L, "System", 1L, false, "system", "active");
        AgentEntity inactive = agent(14L, "Inactive", 1L, false, "user", "inactive");
        AgentEntity foreign = agent(15L, "Foreign", 2L, false, "user", "active");

        owned.setDescription("Owned assistant");
        owned.setSystemPrompt("secret prompt");
        owned.setModelId("private-model");
        owned.setRole("reviewer");
        owned.setExecutionMode("auto");
        owned.setSkillIds("[\"review\",\"planning\"]");
        owned.setToolIds("[\"Bash\",\"Read\"]");
        owned.setLifecycleHooks("{\"hooks\":{}}");

        when(agentRepository.findByOwnerId(1L)).thenReturn(List.of(owned, system, inactive));
        when(agentRepository.findByIsPublicTrue()).thenReturn(List.of(publicAgent, defaultAgent, owned, foreign));
        when(agentRepository.findById(3L)).thenReturn(Optional.of(defaultAgent));

        mvc.perform(get("/api/mobile/client/agents")
                        .with(principal("agent:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[?(@.id == 11)].name").value("Owned"))
                .andExpect(jsonPath("$[?(@.id == 11)].description").value("Owned assistant"))
                .andExpect(jsonPath("$[?(@.id == 11)].status").value("active"))
                .andExpect(jsonPath("$[?(@.id == 11)].role").value("reviewer"))
                .andExpect(jsonPath("$[?(@.id == 11)].modelId").value("private-model"))
                .andExpect(jsonPath("$[?(@.id == 11)].source").value("owned"))
                .andExpect(jsonPath("$[?(@.id == 11)].visibility").value("private"))
                .andExpect(jsonPath("$[?(@.id == 11)].isDefault").value(false))
                .andExpect(jsonPath("$[?(@.id == 11)].executionMode").value("auto"))
                .andExpect(jsonPath("$[?(@.id == 11)].skillCount").value(2))
                .andExpect(jsonPath("$[?(@.id == 11)].toolCount").value(2))
                .andExpect(jsonPath("$[?(@.id == 11)].toolAccess").value("allowlist"))
                .andExpect(jsonPath("$[?(@.id == 11)].configurationAccess").value("detail"))
                .andExpect(jsonPath("$[?(@.id == 12)].name").value("Public"))
                .andExpect(jsonPath("$[?(@.id == 12)].source").value("shared"))
                .andExpect(jsonPath("$[?(@.id == 12)].configurationAccess").value("summary"))
                .andExpect(jsonPath("$[?(@.id == 3)].isDefault").value(true))
                .andExpect(jsonPath("$[?(@.id == 3)].source").value("default"))
                .andExpect(jsonPath("$[?(@.id == 3)].configurationAccess").value("detail"))
                .andExpect(jsonPath("$[?(@.id == 13)]").isEmpty())
                .andExpect(jsonPath("$[?(@.id == 14)]").isEmpty())
                .andExpect(jsonPath("$[?(@.id == 15)]").isEmpty())
                .andExpect(jsonPath("$[0].systemPrompt").doesNotExist())
                .andExpect(jsonPath("$[0].systemPrompt").doesNotExist())
                .andExpect(jsonPath("$[0].toolIds").doesNotExist())
                .andExpect(jsonPath("$[0].lifecycleHooks").doesNotExist())
                .andExpect(jsonPath("$[0].config").doesNotExist())
                .andExpect(jsonPath("$[0].credentials").doesNotExist())
                .andExpect(jsonPath("$[0].behaviorRules").doesNotExist())
                .andExpect(jsonPath("$[0].ownerId").doesNotExist())
                .andExpect(jsonPath("$[0].isPublic").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/mobile/client/agents resolves the default by stable ID when names collide")
    void listAgents_duplicateDefaultName_marksOnlyConfiguredAgentAsDefault() throws Exception {
        AgentEntity configuredDefault = agent(3L, "Main Assistant", null, true, "user", "active");
        AgentEntity userDuplicate = agent(99L, "Main Assistant", 1L, false, "user", "active");

        when(agentRepository.findByOwnerId(1L)).thenReturn(List.of(userDuplicate));
        when(agentRepository.findByIsPublicTrue()).thenReturn(List.of(configuredDefault));
        when(agentRepository.findFirstByName("Main Assistant")).thenReturn(Optional.of(userDuplicate));
        when(agentRepository.findById(3L)).thenReturn(Optional.of(configuredDefault));

        mvc.perform(get("/api/mobile/client/agents")
                        .with(principal("agent:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 3)].isDefault").value(true))
                .andExpect(jsonPath("$[?(@.id == 99)].isDefault").value(false));
    }

    @Test
    @DisplayName("GET /api/mobile/client/agents/{id} requires agent:read")
    void getAgent_withoutAgentReadScope_returnsForbidden() throws Exception {
        mvc.perform(get("/api/mobile/client/agents/11")
                        .with(principal("chat:read")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/mobile/client/agents/{id} requires a mobile principal")
    void getAgent_withoutPrincipal_returnsUnauthorized() throws Exception {
        mvc.perform(get("/api/mobile/client/agents/11"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/mobile/client/agents/{id} returns normalized owned detail without secrets")
    void getAgent_owned_returnsNormalizedAllowlistedDetail() throws Exception {
        AgentEntity owned = agent(11L, "Owned", 1L, false, "user", "active");
        owned.setDescription("Owned assistant");
        owned.setRole("reviewer");
        owned.setModelId("ark:glm-5.2");
        owned.setExecutionMode(null);
        owned.setMaxLoops(null);
        owned.setThinkingMode(null);
        owned.setReasoningEffort(null);
        owned.setSkillIds("[\"review\",\"planning\"]");
        owned.setToolIds("[\"Read\",\"Bash\"]");
        owned.setDisabledSystemSkills("[\"memory\"]");
        owned.setSystemPrompt("agent policy\uD83D\uDE80");
        owned.setSoulPrompt("soul");
        owned.setToolsPrompt("  ");
        owned.setConfig("{\"credentials\":\"secret\"}");
        owned.setLifecycleHooks("{\"hooks\":{}}");
        owned.setBehaviorRules("{\"customRules\":[]}");

        when(agentRepository.findById(11L)).thenReturn(Optional.of(owned));

        mvc.perform(get("/api/mobile/client/agents/11")
                        .with(principal("agent:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(11))
                .andExpect(jsonPath("$.source").value("owned"))
                .andExpect(jsonPath("$.visibility").value("private"))
                .andExpect(jsonPath("$.configurationAccess").value("detail"))
                .andExpect(jsonPath("$.executionMode").value("ask"))
                .andExpect(jsonPath("$.maxLoops").value(25))
                .andExpect(jsonPath("$.thinkingMode").value("auto"))
                .andExpect(jsonPath("$.reasoningEffort").value("provider_default"))
                .andExpect(jsonPath("$.skillNames[0]").value("review"))
                .andExpect(jsonPath("$.skillNames[1]").value("planning"))
                .andExpect(jsonPath("$.toolNames[0]").value("Read"))
                .andExpect(jsonPath("$.toolNames[1]").value("Bash"))
                .andExpect(jsonPath("$.toolAccess").value("allowlist"))
                .andExpect(jsonPath("$.enabledSystemSkillCount").value(1))
                .andExpect(jsonPath("$.promptMetadata.agent.configured").value(true))
                .andExpect(jsonPath("$.promptMetadata.agent.characterCount").value(13))
                .andExpect(jsonPath("$.promptMetadata.soul.configured").value(true))
                .andExpect(jsonPath("$.promptMetadata.soul.characterCount").value(4))
                .andExpect(jsonPath("$.promptMetadata.tools.configured").value(false))
                .andExpect(jsonPath("$.promptMetadata.tools.characterCount").value(0))
                .andExpect(jsonPath("$.systemPrompt").doesNotExist())
                .andExpect(jsonPath("$.soulPrompt").doesNotExist())
                .andExpect(jsonPath("$.toolsPrompt").doesNotExist())
                .andExpect(jsonPath("$.skillIds").doesNotExist())
                .andExpect(jsonPath("$.toolIds").doesNotExist())
                .andExpect(jsonPath("$.config").doesNotExist())
                .andExpect(jsonPath("$.credentials").doesNotExist())
                .andExpect(jsonPath("$.lifecycleHooks").doesNotExist())
                .andExpect(jsonPath("$.behaviorRules").doesNotExist())
                .andExpect(jsonPath("$.ownerId").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/mobile/client/agents/{id} redacts shared configuration")
    void getAgent_shared_returnsCountsWithoutPrivateConfiguration() throws Exception {
        AgentEntity shared = agent(12L, "Shared", 2L, true, "user", "active");
        shared.setSkillIds("[\"review\"]");
        shared.setToolIds("[\"Read\",\"Bash\"]");
        shared.setMaxLoops(99);
        shared.setSystemPrompt("private policy");
        when(agentRepository.findById(12L)).thenReturn(Optional.of(shared));

        mvc.perform(get("/api/mobile/client/agents/12")
                        .with(principal("agent:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("shared"))
                .andExpect(jsonPath("$.configurationAccess").value("summary"))
                .andExpect(jsonPath("$.skillCount").value(1))
                .andExpect(jsonPath("$.toolCount").value(2))
                .andExpect(jsonPath("$.maxLoops").doesNotExist())
                .andExpect(jsonPath("$.thinkingMode").doesNotExist())
                .andExpect(jsonPath("$.reasoningEffort").doesNotExist())
                .andExpect(jsonPath("$.skillNames").doesNotExist())
                .andExpect(jsonPath("$.toolNames").doesNotExist())
                .andExpect(jsonPath("$.enabledSystemSkillCount").doesNotExist())
                .andExpect(jsonPath("$.promptMetadata").doesNotExist());
    }

    @Test
    @DisplayName("blank toolIds means all registered tools with deterministic detail names")
    void getAgent_blankToolIds_returnsSortedRegisteredTools() throws Exception {
        AgentEntity owned = agent(11L, "Owned", 1L, false, "user", "active");
        owned.setToolIds("   ");
        when(agentRepository.findById(11L)).thenReturn(Optional.of(owned));

        mvc.perform(get("/api/mobile/client/agents/11")
                        .with(principal("agent:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toolAccess").value("all"))
                .andExpect(jsonPath("$.toolCount").value(2))
                .andExpect(jsonPath("$.toolNames[0]").value("AlphaTool"))
                .andExpect(jsonPath("$.toolNames[1]").value("ZuluTool"));
    }

    @Test
    @DisplayName("shared unrestricted Agent reports effective count but redacts names")
    void getAgent_sharedNullToolIds_reportsCountWithoutNames() throws Exception {
        AgentEntity shared = agent(12L, "Shared", 2L, true, "user", "active");
        shared.setToolIds(null);
        when(agentRepository.findById(12L)).thenReturn(Optional.of(shared));

        mvc.perform(get("/api/mobile/client/agents/12")
                        .with(principal("agent:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configurationAccess").value("summary"))
                .andExpect(jsonPath("$.toolAccess").value("all"))
                .andExpect(jsonPath("$.toolCount").value(2))
                .andExpect(jsonPath("$.toolNames").doesNotExist());
    }

    @Test
    @DisplayName("configured default receives detail access even when not owned")
    void getAgent_configuredDefault_returnsDetailAccess() throws Exception {
        AgentEntity configuredDefault = agent(3L, "Main Assistant", null, true, "user", "active");
        configuredDefault.setSkillIds("[\"planning\"]");
        when(agentRepository.findById(3L)).thenReturn(Optional.of(configuredDefault));

        mvc.perform(get("/api/mobile/client/agents/3")
                        .with(principal("agent:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("default"))
                .andExpect(jsonPath("$.isDefault").value(true))
                .andExpect(jsonPath("$.configurationAccess").value("detail"))
                .andExpect(jsonPath("$.skillNames[0]").value("planning"))
                .andExpect(jsonPath("$.promptMetadata.agent.configured").value(false));
    }

    @Test
    @DisplayName("GET /api/mobile/client/agents/{id} hides inaccessible agents as not found")
    void getAgent_inaccessibleVariants_returnNotFound() throws Exception {
        List<AgentEntity> hidden = List.of(
                agent(21L, "Foreign private", 2L, false, "user", "active"),
                agent(22L, "Inactive", 1L, false, "user", "inactive"),
                agent(23L, "System", 1L, true, "system", "active"));
        for (AgentEntity agent : hidden) {
            when(agentRepository.findById(agent.getId())).thenReturn(Optional.of(agent));
            mvc.perform(get("/api/mobile/client/agents/{id}", agent.getId())
                            .with(principal("agent:read")))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    @DisplayName("malformed capability lists fail closed instead of returning 500")
    void getAgent_malformedCapabilityLists_returnsEmptyCapabilities() throws Exception {
        AgentEntity owned = agent(11L, "Owned", 1L, false, "user", "active");
        owned.setSkillIds("[\"review\", 7]");
        owned.setToolIds("{\"Read\":true}");
        owned.setDisabledSystemSkills("also-not-json");
        when(agentRepository.findById(11L)).thenReturn(Optional.of(owned));

        mvc.perform(get("/api/mobile/client/agents/11")
                        .with(principal("agent:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skillCount").value(0))
                .andExpect(jsonPath("$.toolCount").value(0))
                .andExpect(jsonPath("$.toolAccess").value("allowlist"))
                .andExpect(jsonPath("$.skillNames").isEmpty())
                .andExpect(jsonPath("$.toolNames").isEmpty())
                .andExpect(jsonPath("$.enabledSystemSkillCount").value(2));
    }

    private static RequestPostProcessor principal(String... scopes) {
        return request -> {
            request.setAttribute(
                    MobileAuthInterceptor.PRINCIPAL_ATTRIBUTE,
                    new MobileDevicePrincipal(UUID.randomUUID(), 1L, "Youren iPhone", Set.of(scopes)));
            return request;
        };
    }

    private static AgentEntity agent(Long id,
                                     String name,
                                     Long ownerId,
                                     boolean isPublic,
                                     String agentType,
                                     String status) {
        AgentEntity agent = new AgentEntity();
        agent.setId(id);
        agent.setName(name);
        agent.setOwnerId(ownerId);
        agent.setPublic(isPublic);
        agent.setAgentType(agentType);
        agent.setStatus(status);
        return agent;
    }

    private void registerSystemSkill(String name) {
        SkillDefinition definition = new SkillDefinition();
        definition.setName(name);
        definition.setSystem(true);
        skillRegistry.registerSkillDefinition(definition);
    }

    private void registerTool(String name) {
        Tool tool = mock(Tool.class);
        when(tool.getName()).thenReturn(name);
        skillRegistry.registerTool(tool);
    }
}
