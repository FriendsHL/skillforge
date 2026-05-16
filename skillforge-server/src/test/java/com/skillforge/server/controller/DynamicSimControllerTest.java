package com.skillforge.server.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.server.entity.SimulatorTrialEntity;
import com.skillforge.server.eval.usersim.SimulatorTrialOrchestrator;
import com.skillforge.server.eval.usersim.SimulatorTrialOrchestrator.SimulationOutcome;
import com.skillforge.server.eval.usersim.SimulatorTrialOrchestrator.TrialRequest;
import com.skillforge.server.repository.SimulatorTrialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V5 EVAL-DYNAMIC-USER-SIM Phase 1.3 — {@link DynamicSimController} REST shape
 * tests. Uses MockMvc standalone setup; service layer is mocked.
 *
 * <p>4 cases per team-lead brief:
 * <ol>
 *   <li>POST /api/dynamic-sim/trials → 202 happy path (async fan-out)</li>
 *   <li>GET /api/dynamic-sim/trials → 200 with filter narrowing</li>
 *   <li>GET /api/dynamic-sim/trials/{id} → 200 on hit</li>
 *   <li>GET /api/dynamic-sim/trials/{id} → 404 on miss</li>
 * </ol>
 */
@EnableWebMvc
@DisplayName("DynamicSimController")
class DynamicSimControllerTest {

    private SimulatorTrialOrchestrator orchestrator;
    private SimulatorTrialRepository trialRepository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        orchestrator = mock(SimulatorTrialOrchestrator.class);
        trialRepository = mock(SimulatorTrialRepository.class);

        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        DynamicSimController controller = new DynamicSimController(
                orchestrator, trialRepository, Executors.newSingleThreadExecutor());
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    @DisplayName("POST /trials → 202 launches one trial per persona (async fan-out)")
    void postTrials_happyPath_returns202AndFansOut() throws Exception {
        when(orchestrator.runTrial(any(TrialRequest.class)))
                .thenReturn(new SimulationOutcome("trial-A", "sess-A", 5,
                        "task_completed", List.of()));

        String body = """
            {
              "scenarioId": "scen-1",
              "candidateAgentVersionId": "ver-1",
              "candidateSurfaceType": "prompt",
              "personas": ["CEO 高高在上", "DBA 老手"],
              "maxTurns": 8
            }
            """;

        mvc.perform(post("/api/dynamic-sim/trials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.scenarioId").value("scen-1"))
                .andExpect(jsonPath("$.personaCount").value(2))
                .andExpect(jsonPath("$.status").value("RUNNING"));

        // Async fan-out: verify orchestrator gets invoked twice (one per persona).
        verify(orchestrator, timeout(2_000).times(2)).runTrial(any(TrialRequest.class));
    }

    @Test
    @DisplayName("GET /trials returns paginated list with scenario filter")
    void listTrials_withScenarioFilter_returnsPage() throws Exception {
        SimulatorTrialEntity row = newTrial("trial-A", "scen-1", "ver-1", "prompt",
                "CEO 高高在上", "sess-A", 5, "task_completed", null);
        Page<SimulatorTrialEntity> page = new PageImpl<>(
                List.of(row), PageRequest.of(0, 20), 1);

        when(trialRepository.findByScenarioId(eq("scen-1"), any(Pageable.class)))
                .thenReturn(page);

        mvc.perform(get("/api/dynamic-sim/trials").param("scenarioId", "scen-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].trialId").value("trial-A"))
                .andExpect(jsonPath("$.content[0].scenarioId").value("scen-1"))
                .andExpect(jsonPath("$.content[0].sessionId").value("sess-A"))
                .andExpect(jsonPath("$.content[0].persona").value("CEO 高高在上"))
                .andExpect(jsonPath("$.content[0].turnsUsed").value(5))
                .andExpect(jsonPath("$.content[0].terminationReason").value("task_completed"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("GET /trials/{trialId} returns 200 with the trial detail")
    void getTrial_existing_returns200() throws Exception {
        SimulatorTrialEntity row = newTrial("trial-A", "scen-1", "ver-1", "prompt",
                "实习生小白", "sess-A", 7, "failure_signal",
                "用户开始重复同一问题");
        when(trialRepository.findById("trial-A")).thenReturn(Optional.of(row));

        mvc.perform(get("/api/dynamic-sim/trials/trial-A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trialId").value("trial-A"))
                .andExpect(jsonPath("$.persona").value("实习生小白"))
                .andExpect(jsonPath("$.observedFailureSignals").value("用户开始重复同一问题"));
    }

    @Test
    @DisplayName("POST /trials rejects behavior_rule surface with 400 (V5 known limitation)")
    void postTrials_behaviorRuleSurface_returns400() throws Exception {
        String body = """
            {
              "scenarioId": "scen-1",
              "candidateAgentVersionId": "rule-v1",
              "candidateSurfaceType": "behavior_rule",
              "personas": ["实习生小白"]
            }
            """;

        mvc.perform(post("/api/dynamic-sim/trials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("behavior_rule")))
                .andExpect(jsonPath("$.supportedSurfaces[0]").value("prompt"))
                .andExpect(jsonPath("$.supportedSurfaces[1]").value("skill"));

        // orchestrator never invoked
        org.mockito.Mockito.verify(orchestrator, org.mockito.Mockito.never()).runTrial(any());
    }

    @Test
    @DisplayName("GET /trials/{trialId} returns 404 when trial is missing")
    void getTrial_missing_returns404() throws Exception {
        when(trialRepository.findById("trial-missing")).thenReturn(Optional.empty());

        mvc.perform(get("/api/dynamic-sim/trials/trial-missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Trial not found: trial-missing"));
    }

    private SimulatorTrialEntity newTrial(String trialId, String scenarioId,
                                           String candidateVersionId, String surfaceType,
                                           String persona, String sessionId,
                                           int turnsUsed, String terminationReason,
                                           String observedSignals) {
        SimulatorTrialEntity t = new SimulatorTrialEntity();
        t.setTrialId(trialId);
        t.setScenarioId(scenarioId);
        t.setCandidateAgentVersionId(candidateVersionId);
        t.setCandidateSurfaceType(surfaceType);
        t.setPersona(persona);
        t.setSessionId(sessionId);
        t.setTurnsUsed(turnsUsed);
        t.setTerminationReason(terminationReason);
        t.setObservedFailureSignals(observedSignals);
        t.setCreatedAt(Instant.now());
        return t;
    }
}
