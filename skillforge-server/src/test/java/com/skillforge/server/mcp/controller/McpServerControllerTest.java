package com.skillforge.server.mcp.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.server.mcp.entity.McpServerEntity;
import com.skillforge.server.mcp.exception.McpServerInUseException;
import com.skillforge.server.mcp.exception.McpServerNotFoundException;
import com.skillforge.server.mcp.service.McpServerLifecycle;
import com.skillforge.server.mcp.service.McpServerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnableWebMvc
@DisplayName("McpServerController")
class McpServerControllerTest {

    private McpServerService service;
    private McpServerLifecycle lifecycle;
    private ObjectMapper objectMapper;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(McpServerService.class);
        lifecycle = mock(McpServerLifecycle.class);
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        McpServerController controller = new McpServerController(service, lifecycle, objectMapper);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    private McpServerEntity row() {
        McpServerEntity e = new McpServerEntity();
        e.setId(7L);
        e.setName("time");
        e.setCommand("npx");
        e.setArgs("[\"-y\",\"server-time\"]");
        e.setEnv("{}");
        e.setEnabled(true);
        return e;
    }

    @Test
    @DisplayName("POST /api/mcp-servers returns 201 with response shape")
    void create_returns201() throws Exception {
        when(service.create(eq(1L), any())).thenReturn(row());
        when(lifecycle.runtimeStatus(any())).thenReturn("connected");
        when(lifecycle.liveTools(any())).thenReturn(List.of(
                Map.of("name", "get_current_time", "registeredName", "mcp_time_get_current_time")));
        mvc.perform(post("/api/mcp-servers").param("userId", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"time\",\"command\":\"npx\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.name").value("time"))
                .andExpect(jsonPath("$.status").value("connected"))
                .andExpect(jsonPath("$.tools[0].name").value("get_current_time"));
    }

    @Test
    @DisplayName("POST /api/mcp-servers returns 400 on validation failure")
    void create_400_onValidation() throws Exception {
        when(service.create(eq(1L), any())).thenThrow(
                new IllegalArgumentException("name must match [a-z0-9_]+"));
        mvc.perform(post("/api/mcp-servers").param("userId", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"BAD\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("[a-z0-9_]+")));
    }

    @Test
    @DisplayName("GET /api/mcp-servers lists rows with runtime status")
    void list_returnsList() throws Exception {
        when(service.list()).thenReturn(List.of(row()));
        when(lifecycle.runtimeStatus(any())).thenReturn("disconnected");
        when(lifecycle.liveTools(any())).thenReturn(List.of());
        mvc.perform(get("/api/mcp-servers").param("userId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("time"))
                .andExpect(jsonPath("$[0].status").value("disconnected"));
    }

    @Test
    @DisplayName("GET /api/mcp-servers/{id} returns 404 if not found")
    void get_notFound() throws Exception {
        when(service.get(99L)).thenThrow(new McpServerNotFoundException(99L));
        mvc.perform(get("/api/mcp-servers/99").param("userId", "1"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PUT /api/mcp-servers/{id} returns 200 on success")
    void update_returns200() throws Exception {
        when(service.update(eq(7L), eq(1L), any())).thenReturn(row());
        when(lifecycle.runtimeStatus(any())).thenReturn("connected");
        when(lifecycle.liveTools(any())).thenReturn(List.of());
        mvc.perform(put("/api/mcp-servers/7").param("userId", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"updated\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("DELETE /api/mcp-servers/{id} returns 409 with referencing agents on INV-12")
    void delete_409_inUse() throws Exception {
        doThrow(new McpServerInUseException("time", List.of("agentA", "agentB")))
                .when(service).delete(eq(7L), eq(1L));
        mvc.perform(delete("/api/mcp-servers/7").param("userId", "1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.referencingAgents[0]").value("agentA"))
                .andExpect(jsonPath("$.referencingAgents[1]").value("agentB"));
    }

    @Test
    @DisplayName("DELETE /api/mcp-servers/{id} returns 204 on success")
    void delete_returns204() throws Exception {
        mvc.perform(delete("/api/mcp-servers/7").param("userId", "1"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("POST /api/mcp-servers/{id}/test-connection returns 502 on connection failure")
    void testConnection_502_onFailure() throws Exception {
        when(lifecycle.testConnection(7L)).thenThrow(new RuntimeException("connect failed"));
        mvc.perform(post("/api/mcp-servers/7/test-connection").param("userId", "1"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("connect failed")));
    }

    @Test
    @DisplayName("POST /api/mcp-servers/{id}/test-connection returns 200 with tool list")
    void testConnection_200() throws Exception {
        when(lifecycle.testConnection(7L)).thenReturn(List.of(
                Map.of("name", "get_current_time")));
        mvc.perform(post("/api/mcp-servers/7/test-connection").param("userId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.tools[0].name").value("get_current_time"));
    }
}
