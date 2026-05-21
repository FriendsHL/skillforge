package com.skillforge.server.flywheel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.server.bootstrap.SystemAgentNames;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.service.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FLYWHEEL-PER-RUN — {@link FlywheelController} REST shape + param validation.
 *
 * <p>r2 W2 coverage: agentType must be one of {@code user} / {@code system} —
 * unknown values fail fast with 400 (was previously silent empty []).
 *
 * <p>MockMvc standaloneSetup mirrors {@code AttributionEventControllerTest}.
 */
@EnableWebMvc
@DisplayName("FlywheelController")
class FlywheelControllerTest {

    private FlywheelRunsService runsService;
    private AgentRepository agentRepository;
    private SessionService sessionService;
    private ChatService chatService;
    private FlywheelChainOrchestrator chainOrchestrator;
    private FlywheelController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        runsService = mock(FlywheelRunsService.class);
        agentRepository = mock(AgentRepository.class);
        sessionService = mock(SessionService.class);
        chatService = mock(ChatService.class);
        chainOrchestrator = mock(FlywheelChainOrchestrator.class);
        // r2 W3: mirror Spring autoconfigured ObjectMapper (findAndRegisterModules
        // discovers JavaTimeModule via SPI same way Spring Boot does).
        ObjectMapper objectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        controller = new FlywheelController(runsService, agentRepository,
                sessionService, chatService, chainOrchestrator);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    @DisplayName("GET /api/flywheel/runs → 200 with defaults (limit=20, hideTerminal=true)")
    void list_defaults_returns200() throws Exception {
        when(runsService.listRecentRuns(any(), any(), anyInt(), anyBoolean()))
                .thenReturn(List.of());

        mvc.perform(get("/api/flywheel/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.limit").value(20))
                .andExpect(jsonPath("$.hideTerminal").value(true));

        verify(runsService).listRecentRuns(eq(null), eq(null), eq(20), eq(true));
    }

    @Test
    @DisplayName("agentType=invalid → 400 (r2 W2 — fail-fast, not silent [])")
    void list_invalidAgentType_returns400() throws Exception {
        mvc.perform(get("/api/flywheel/runs").param("agentType", "robot"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("agentType=user → 200 + service receives 'user'")
    void list_userAgentType_returns200AndPassesThrough() throws Exception {
        when(runsService.listRecentRuns(eq("user"), any(), anyInt(), anyBoolean()))
                .thenReturn(List.of());

        mvc.perform(get("/api/flywheel/runs").param("agentType", "user"))
                .andExpect(status().isOk());

        verify(runsService).listRecentRuns(eq("user"), eq(null), eq(20), eq(true));
    }

    @Test
    @DisplayName("agentType=system → 200 + service receives 'system'")
    void list_systemAgentType_returns200AndPassesThrough() throws Exception {
        when(runsService.listRecentRuns(eq("system"), any(), anyInt(), anyBoolean()))
                .thenReturn(List.of());

        mvc.perform(get("/api/flywheel/runs").param("agentType", "system"))
                .andExpect(status().isOk());

        verify(runsService).listRecentRuns(eq("system"), eq(null), eq(20), eq(true));
    }

    @Test
    @DisplayName("agentType=  (blank) → 200, treated as no filter (not 400)")
    void list_blankAgentType_treatedAsNoFilter() throws Exception {
        when(runsService.listRecentRuns(any(), any(), anyInt(), anyBoolean()))
                .thenReturn(List.of());

        mvc.perform(get("/api/flywheel/runs").param("agentType", ""))
                .andExpect(status().isOk());

        verify(runsService).listRecentRuns(eq(null), eq(null), eq(20), eq(true));
    }

    @Test
    @DisplayName("limit clamping: limit=999 → 100; limit=-5 → 1")
    void list_limitClamping_minMaxEnforced() throws Exception {
        when(runsService.listRecentRuns(any(), any(), anyInt(), anyBoolean()))
                .thenReturn(List.of());

        mvc.perform(get("/api/flywheel/runs").param("limit", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(100));

        mvc.perform(get("/api/flywheel/runs").param("limit", "-5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(1));
    }

    @Test
    @DisplayName("hideTerminal=false → service called with false")
    void list_hideTerminalFalse_passesThrough() throws Exception {
        when(runsService.listRecentRuns(any(), any(), anyInt(), eq(false)))
                .thenReturn(List.of());

        mvc.perform(get("/api/flywheel/runs").param("hideTerminal", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hideTerminal").value(false));

        verify(runsService).listRecentRuns(eq(null), eq(null), eq(20), eq(false));
    }

    // ─── FLYWHEEL-PER-AGENT-RUN-NOW (2026-05-21): runLoop endpoint ────────

    @Test
    @DisplayName("POST /agents/{id}/run-loop → 202, fires annotator chatAsync + registers chain hook")
    void runLoop_happyPath_fires202_andWiresChain() throws Exception {
        AgentEntity target = agentEntity(7L, "my-agent", "user");
        AgentEntity annotator = agentEntity(100L, SystemAgentNames.SESSION_ANNOTATOR, "system");
        AgentEntity dispatcher = agentEntity(101L, SystemAgentNames.ATTRIBUTION_DISPATCHER, "system");
        when(agentRepository.findById(7L)).thenReturn(Optional.of(target));
        when(agentRepository.findFirstByName(SystemAgentNames.SESSION_ANNOTATOR))
                .thenReturn(Optional.of(annotator));
        when(agentRepository.findFirstByName(SystemAgentNames.ATTRIBUTION_DISPATCHER))
                .thenReturn(Optional.of(dispatcher));

        SessionEntity annotatorSession = sessionEntity("sess-annot");
        when(sessionService.createSession(eq(0L), eq(100L))).thenReturn(annotatorSession);

        mvc.perform(post("/api/flywheel/agents/7/run-loop"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.agentId").value(7))
                .andExpect(jsonPath("$.agentName").value("my-agent"))
                .andExpect(jsonPath("$.annotatorSessionId").value("sess-annot"))
                .andExpect(jsonPath("$.windowHours").value(24))
                .andExpect(jsonPath("$.max").value(10))
                .andExpect(jsonPath("$.status").value("triggered"));

        // STEP 1: annotator chatAsync fires with the scope keyword "agentId=7"
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(chatService).chatAsync(eq("sess-annot"), prompt.capture(), eq(0L));
        assertThat(prompt.getValue()).contains("agentId=7").contains("24");

        // STEP 2: chain hook is registered for the annotator session
        verify(chainOrchestrator).registerAnnotatorEndHook(eq("sess-annot"), any(Runnable.class));

        // Dispatcher session is NOT fired synchronously from the controller —
        // it only fires when the orchestrator polling tick detects the
        // annotator's terminal runtimeStatus. Verify dispatcher session not
        // created yet (only the annotator one was).
        verify(sessionService, never()).createSession(eq(0L), eq(101L));
    }

    @Test
    @DisplayName("POST /agents/{id}/run-loop → 404 when target agent missing")
    void runLoop_unknownAgent_returns404() throws Exception {
        when(agentRepository.findById(999L)).thenReturn(Optional.empty());

        mvc.perform(post("/api/flywheel/agents/999/run-loop"))
                .andExpect(status().isNotFound());

        verify(sessionService, never()).createSession(anyLong(), anyLong());
        verify(chatService, never()).chatAsync(anyString(), anyString(), anyLong());
        verify(chainOrchestrator, never()).registerAnnotatorEndHook(anyString(), any());
    }

    @Test
    @DisplayName("POST /agents/{id}/run-loop → 503 when session-annotator system agent not seeded")
    void runLoop_annotatorAgentMissing_returns503() throws Exception {
        AgentEntity target = agentEntity(7L, "my-agent", "user");
        when(agentRepository.findById(7L)).thenReturn(Optional.of(target));
        when(agentRepository.findFirstByName(SystemAgentNames.SESSION_ANNOTATOR))
                .thenReturn(Optional.empty());

        mvc.perform(post("/api/flywheel/agents/7/run-loop"))
                .andExpect(status().isServiceUnavailable());

        verify(sessionService, never()).createSession(anyLong(), anyLong());
    }

    @Test
    @DisplayName("POST /agents/{id}/run-loop → 503 when attribution-dispatcher system agent not seeded (r2 F3 java-reviewer W-2)")
    void runLoop_dispatcherAgentMissing_returns503() throws Exception {
        AgentEntity target = agentEntity(7L, "my-agent", "user");
        AgentEntity annotator = agentEntity(100L, SystemAgentNames.SESSION_ANNOTATOR, "system");
        when(agentRepository.findById(7L)).thenReturn(Optional.of(target));
        when(agentRepository.findFirstByName(SystemAgentNames.SESSION_ANNOTATOR))
                .thenReturn(Optional.of(annotator));
        when(agentRepository.findFirstByName(SystemAgentNames.ATTRIBUTION_DISPATCHER))
                .thenReturn(Optional.empty());

        mvc.perform(post("/api/flywheel/agents/7/run-loop"))
                .andExpect(status().isServiceUnavailable());

        // Critical: must fail-fast BEFORE annotator session is created, so we
        // don't leak a hung annotator chatAsync when the dispatcher half is
        // missing (would leave an orphan in-flight session with no downstream).
        verify(sessionService, never()).createSession(anyLong(), anyLong());
        verify(chatService, never()).chatAsync(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("POST /agents/{id}/run-loop?windowHours=999&max=999 → clamped to (168, 20)")
    void runLoop_overlargeParams_clamped() throws Exception {
        AgentEntity target = agentEntity(7L, "my-agent", "user");
        AgentEntity annotator = agentEntity(100L, SystemAgentNames.SESSION_ANNOTATOR, "system");
        AgentEntity dispatcher = agentEntity(101L, SystemAgentNames.ATTRIBUTION_DISPATCHER, "system");
        when(agentRepository.findById(7L)).thenReturn(Optional.of(target));
        when(agentRepository.findFirstByName(SystemAgentNames.SESSION_ANNOTATOR))
                .thenReturn(Optional.of(annotator));
        when(agentRepository.findFirstByName(SystemAgentNames.ATTRIBUTION_DISPATCHER))
                .thenReturn(Optional.of(dispatcher));
        when(sessionService.createSession(eq(0L), eq(100L)))
                .thenReturn(sessionEntity("sess-annot"));

        mvc.perform(post("/api/flywheel/agents/7/run-loop")
                        .param("windowHours", "999")
                        .param("max", "999"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.windowHours").value(168))
                .andExpect(jsonPath("$.max").value(20));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(chatService).chatAsync(eq("sess-annot"), prompt.capture(), eq(0L));
        // Prompt embeds the clamped window so the LLM sees a realistic value.
        assertThat(prompt.getValue()).contains("168");
    }

    @Test
    @DisplayName("fireDispatcher (hook callback): fires dispatcher chatAsync with agentId=N scope keyword")
    void fireDispatcher_unitInvoke_firesDispatcherWithScopedPrompt() {
        SessionEntity dispatcherSession = sessionEntity("sess-disp");
        when(sessionService.createSession(eq(0L), eq(101L))).thenReturn(dispatcherSession);

        controller.fireDispatcher(7L, 101L, 10);

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(chatService).chatAsync(eq("sess-disp"), prompt.capture(), eq(0L));
        assertThat(prompt.getValue()).contains("agentId=7").contains("max=10");
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private static AgentEntity agentEntity(Long id, String name, String agentType) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setName(name);
        a.setAgentType(agentType);
        return a;
    }

    private static SessionEntity sessionEntity(String id) {
        SessionEntity s = new SessionEntity();
        s.setId(id);
        s.setUserId(0L);
        s.setAgentId(0L);
        return s;
    }
}
