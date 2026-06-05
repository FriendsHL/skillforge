package com.skillforge.server.evolve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.server.evolve.dto.ActivateScenarioResponse;
import com.skillforge.server.evolve.dto.HarvestedScenarioDto;
import com.skillforge.server.flywheel.run.FlywheelRunService;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.service.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
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
 * BC-M2b — {@link EvolveController} harvested-scenario endpoints: activate human
 * gate (system user rejected), bare activate response, enveloped list + harvest.
 */
@EnableWebMvc
@DisplayName("EvolveController harvested scenarios")
class EvolveControllerActivateTest {

    private HarvestedScenarioService harvestedScenarioService;
    private MockMvc mvc;

    private static final Instant NOW = Instant.parse("2026-06-04T10:00:00Z");

    @BeforeEach
    void setUp() {
        harvestedScenarioService = mock(HarvestedScenarioService.class);
        ObjectMapper objectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        EvolveController controller = new EvolveController(
                mock(AgentRepository.class),
                mock(FlywheelRunService.class),
                mock(SessionService.class),
                mock(ChatService.class),
                mock(EvolveReadService.class),
                mock(AgentBundleAdoptionService.class),
                harvestedScenarioService,
                mock(com.skillforge.workflow.WorkflowRunnerService.class));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    @DisplayName("activate: system user (userId=0) → 400, service not called (Iron-Law human gate)")
    void activate_systemUser_400() throws Exception {
        mvc.perform(post("/api/evolve/scenarios/bad-1/activate").param("userId", "0"))
                .andExpect(status().isBadRequest());
        verify(harvestedScenarioService, never()).activate(anyString(), anyLong());
    }

    @Test
    @DisplayName("activate: happy path → 200 with bare response fields")
    void activate_happy_200() throws Exception {
        when(harvestedScenarioService.activate(eq("bad-1"), eq(42L)))
                .thenReturn(new ActivateScenarioResponse(
                        "bad-1", "active", "7", "v-1", 1, 3, NOW));

        mvc.perform(post("/api/evolve/scenarios/bad-1/activate").param("userId", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenarioId").value("bad-1"))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.agentId").value("7"))
                .andExpect(jsonPath("$.datasetVersionId").value("v-1"))
                .andExpect(jsonPath("$.datasetVersionNumber").value(1))
                .andExpect(jsonPath("$.datasetScenarioCount").value(3));
    }

    @Test
    @DisplayName("activate: not found → 404")
    void activate_notFound_404() throws Exception {
        when(harvestedScenarioService.activate(eq("nope"), eq(42L)))
                .thenThrow(new HarvestedScenarioService.ScenarioNotFoundException("nope"));
        mvc.perform(post("/api/evolve/scenarios/nope/activate").param("userId", "42"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("activate: not draft → 409")
    void activate_notDraft_409() throws Exception {
        when(harvestedScenarioService.activate(eq("bad-1"), eq(42L)))
                .thenThrow(new IllegalStateException("scenario bad-1 is not a draft (status=active)"));
        mvc.perform(post("/api/evolve/scenarios/bad-1/activate").param("userId", "42"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("activate: wrong source_type / missing agentId → 400")
    void activate_badRequest_400() throws Exception {
        when(harvestedScenarioService.activate(eq("bad-1"), eq(42L)))
                .thenThrow(new IllegalArgumentException("scenario bad-1 is not session_derived"));
        mvc.perform(post("/api/evolve/scenarios/bad-1/activate").param("userId", "42"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("list: enveloped {items:[...]}")
    void list_enveloped() throws Exception {
        when(harvestedScenarioService.listHarvestedScenarios("7", "draft"))
                .thenReturn(List.of(new HarvestedScenarioDto(
                        "bad-1", "badcase-bad-1", "desc", "draft", "session:s1", NOW, null)));
        mvc.perform(get("/api/evolve/agents/7/scenarios").param("status", "draft"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value("bad-1"))
                .andExpect(jsonPath("$.items[0].status").value("draft"));
    }

    @Test
    @DisplayName("list: invalid status → 400")
    void list_invalidStatus_400() throws Exception {
        mvc.perform(get("/api/evolve/agents/7/scenarios").param("status", "bogus"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("harvest-bad-cases: enveloped {items, count}")
    void harvest_enveloped() throws Exception {
        when(harvestedScenarioService.harvestBadCases(eq(7L), anyInt()))
                .thenReturn(List.of("d1", "d2"));
        mvc.perform(post("/api/evolve/agents/7/harvest-bad-cases").param("windowDays", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0]").value("d1"))
                .andExpect(jsonPath("$.count").value(2));
    }
}
