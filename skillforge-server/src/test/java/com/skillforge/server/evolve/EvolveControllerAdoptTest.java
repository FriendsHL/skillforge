package com.skillforge.server.evolve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.server.evolve.AgentBundleAdoptionService.AdoptResult;
import com.skillforge.server.evolve.AgentBundleAdoptionService.SurfaceResult;
import com.skillforge.server.evolve.dto.CandidateBundle;
import com.skillforge.server.evolve.dto.EvolveRunDetailDto;
import com.skillforge.server.flywheel.run.FlywheelRunService;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.service.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
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
 * AUTOEVOLVE-CLOSE-LOOP P1 — {@link EvolveController#adopt} validation chain +
 * bare {@link AdoptResult} response shape. MockMvc standaloneSetup mirrors
 * {@code EvolveControllerTest}.
 */
@EnableWebMvc
@DisplayName("EvolveController.adopt")
class EvolveControllerAdoptTest {

    private EvolveReadService evolveReadService;
    private AgentBundleAdoptionService adoptionService;
    private EvolveController controller;
    private MockMvc mvc;

    private static final Instant NOW = Instant.parse("2026-06-03T10:00:00Z");

    @BeforeEach
    void setUp() {
        evolveReadService = mock(EvolveReadService.class);
        adoptionService = mock(AgentBundleAdoptionService.class);

        ObjectMapper objectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        controller = new EvolveController(
                mock(AgentRepository.class),
                mock(FlywheelRunService.class),
                mock(SessionService.class),
                mock(ChatService.class),
                evolveReadService,
                adoptionService,
                mock(HarvestedScenarioService.class));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    private EvolveRunDetailDto detail() {
        return new EvolveRunDetailDto("run-1", 7L, "my-agent", "completed", NOW, NOW, List.of());
    }

    @Test
    @DisplayName("system user (userId=0) → 400")
    void adopt_systemUser_400() throws Exception {
        mvc.perform(post("/api/evolve/runs/run-1/adopt")
                        .param("userId", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"promptVersionId\":\"pv-1\"}"))
                .andExpect(status().isBadRequest());
        verify(adoptionService, never()).adopt(any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("run not found / not an evolve run → 404")
    void adopt_runNotFound_404() throws Exception {
        when(evolveReadService.getRunDetail("run-1")).thenReturn(Optional.empty());

        mvc.perform(post("/api/evolve/runs/run-1/adopt")
                        .param("userId", "42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"promptVersionId\":\"pv-1\"}"))
                .andExpect(status().isNotFound());
        verify(adoptionService, never()).adopt(any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("all pointers null → 400")
    void adopt_allPointersNull_400() throws Exception {
        when(evolveReadService.getRunDetail("run-1")).thenReturn(Optional.of(detail()));

        mvc.perform(post("/api/evolve/runs/run-1/adopt")
                        .param("userId", "42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        verify(adoptionService, never()).adopt(any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("pointer not from any kept iteration's bundle → 400 (privilege-escalation guard)")
    void adopt_pointerNotFromKept_400() throws Exception {
        when(evolveReadService.getRunDetail("run-1")).thenReturn(Optional.of(detail()));
        when(evolveReadService.listKeptCandidateBundles("run-1"))
                .thenReturn(List.of(new CandidateBundle("pv-other", null, null)));

        mvc.perform(post("/api/evolve/runs/run-1/adopt")
                        .param("userId", "42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"promptVersionId\":\"pv-1\"}"))
                .andExpect(status().isBadRequest());
        verify(adoptionService, never()).adopt(any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("happy path → 200 bare AdoptResult with per-surface status; adopt called with run's agentId")
    void adopt_happyPath_200() throws Exception {
        when(evolveReadService.getRunDetail("run-1")).thenReturn(Optional.of(detail()));
        when(evolveReadService.listKeptCandidateBundles("run-1"))
                .thenReturn(List.of(new CandidateBundle("pv-1", "rv-1", "sd-1")));
        when(adoptionService.adopt(any(), eq("7"), eq(42L)))
                .thenReturn(new AdoptResult(SurfaceResult.ok(), SurfaceResult.ok(),
                        SurfaceResult.ok(), false));

        mvc.perform(post("/api/evolve/runs/run-1/adopt")
                        .param("userId", "42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"promptVersionId\":\"pv-1\",\"behaviorRuleVersionId\":\"rv-1\","
                                + "\"skillDraftId\":\"sd-1\"}"))
                .andExpect(status().isOk())
                // bare AdoptResult (NOT enveloped)
                .andExpect(jsonPath("$.prompt.status").value("ok"))
                .andExpect(jsonPath("$.rule.status").value("ok"))
                .andExpect(jsonPath("$.skill.status").value("ok"))
                .andExpect(jsonPath("$.anyFailed").value(false));

        verify(adoptionService).adopt(any(CandidateBundle.class), eq("7"), eq(42L));
    }

    @Test
    @DisplayName("partial failure → still 200 with anyFailed=true and the failed surface reason")
    void adopt_partialFailure_200_anyFailed() throws Exception {
        when(evolveReadService.getRunDetail("run-1")).thenReturn(Optional.of(detail()));
        when(evolveReadService.listKeptCandidateBundles("run-1"))
                .thenReturn(List.of(new CandidateBundle("pv-1", null, "sd-1")));
        when(adoptionService.adopt(any(), eq("7"), eq(42L)))
                .thenReturn(new AdoptResult(SurfaceResult.ok(), null,
                        SurfaceResult.failed("Skill name conflict: dup"), true));

        mvc.perform(post("/api/evolve/runs/run-1/adopt")
                        .param("userId", "42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"promptVersionId\":\"pv-1\",\"skillDraftId\":\"sd-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prompt.status").value("ok"))
                .andExpect(jsonPath("$.skill.status").value("failed"))
                .andExpect(jsonPath("$.skill.reason").value("Skill name conflict: dup"))
                .andExpect(jsonPath("$.anyFailed").value(true));
    }

    @Test
    @DisplayName("adopt service throws IAE (ownership) → 400")
    void adopt_serviceIAE_400() throws Exception {
        when(evolveReadService.getRunDetail("run-1")).thenReturn(Optional.of(detail()));
        when(evolveReadService.listKeptCandidateBundles("run-1"))
                .thenReturn(List.of(new CandidateBundle("pv-1", null, null)));
        when(adoptionService.adopt(any(), eq("7"), eq(42L)))
                .thenThrow(new IllegalArgumentException("prompt version pv-1 belongs to agent 9"));

        mvc.perform(post("/api/evolve/runs/run-1/adopt")
                        .param("userId", "42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"promptVersionId\":\"pv-1\"}"))
                .andExpect(status().isBadRequest());
    }

    // ─── GET /skill-drafts/{draftId} (adopt-card diff source) ───────────────

    @Test
    @DisplayName("skill-draft not found → 404 (java review W1)")
    void skillDraft_notFound_404() throws Exception {
        when(evolveReadService.getSkillDraftView("nope")).thenReturn(Optional.empty());

        mvc.perform(get("/api/evolve/skill-drafts/nope"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("skill-draft found → 200 with promptHint projection (java review W1)")
    void skillDraft_found_200() throws Exception {
        when(evolveReadService.getSkillDraftView("d1")).thenReturn(Optional.of(
                Map.of("id", "d1", "name", "MySkill", "promptHint", "do the thing first")));

        mvc.perform(get("/api/evolve/skill-drafts/d1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("d1"))
                .andExpect(jsonPath("$.promptHint").value("do the thing first"));
    }
}
