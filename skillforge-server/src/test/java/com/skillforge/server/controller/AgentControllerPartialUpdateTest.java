package com.skillforge.server.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.context.BehaviorRuleRegistry;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.service.AgentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentControllerPartialUpdateTest {
    private AgentRepository repository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        repository = mock(AgentRepository.class);
        AgentService service = new AgentService(repository, new ObjectMapper(), mock(BehaviorRuleRegistry.class));
        mvc = MockMvcBuilders.standaloneSetup(new AgentController(service)).build();
        when(repository.save(any(AgentEntity.class))).thenAnswer(call -> call.getArgument(0));
    }

    private void existingSystemAgent() {
        AgentEntity existing = new AgentEntity();
        existing.setId(26L);
        existing.setAgentType("system");
        existing.setStatus("inactive");
        existing.setExecutionMode("auto");
        existing.setMcpServerIds("configured-server");
        existing.setModelId("old:model");
        existing.setPublic(true);
        when(repository.findById(26L)).thenReturn(Optional.of(existing));
    }

    @Test
    void updateAgent_modelOnlyJson_preservesUnsubmittedDefaultedFields() throws Exception {
        existingSystemAgent();
        mvc.perform(put("/api/agents/26").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelId\":\"bailian-token-plan:qwen3.7-max\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelId").value("bailian-token-plan:qwen3.7-max"))
                .andExpect(jsonPath("$.status").value("inactive"))
                .andExpect(jsonPath("$.executionMode").value("auto"))
                .andExpect(jsonPath("$.mcpServerIds").value("configured-server"))
                .andExpect(jsonPath("$.agentType").value("system"));
    }

    @Test
    void updateAgent_explicitJson_updatesStatusExecutionAndMcp() throws Exception {
        existingSystemAgent();
        mvc.perform(put("/api/agents/26").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"active\",\"executionMode\":\"ask\",\"mcpServerIds\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.executionMode").value("ask"))
                .andExpect(jsonPath("$.mcpServerIds").value(""))
                .andExpect(jsonPath("$.modelId").value("old:model"));
    }

    @Test
    void updateAgent_nullJson_preservesFieldsAndPublicFalseIsExplicit() throws Exception {
        existingSystemAgent();
        mvc.perform(put("/api/agents/26").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":null,\"executionMode\":null,\"mcpServerIds\":null,\"public\":false,\"thinkingVisible\":false,\"reasoningEffort\":\"high\",\"maxLoops\":42}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("inactive"))
                .andExpect(jsonPath("$.executionMode").value("auto"))
                .andExpect(jsonPath("$.mcpServerIds").value("configured-server"))
                .andExpect(jsonPath("$.public").value(false))
                .andExpect(jsonPath("$.thinkingVisible").value(false))
                .andExpect(jsonPath("$.reasoningEffort").value("high"))
                .andExpect(jsonPath("$.maxLoops").value(42));
    }

    @Test
    void createAgent_minimalJson_keepsCreationDefaults() throws Exception {
        mvc.perform(post("/api/agents").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"new agent\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.executionMode").value("ask"))
                .andExpect(jsonPath("$.mcpServerIds").value(""));
    }
}
