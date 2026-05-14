package com.skillforge.server.canary;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.server.entity.CanaryMetricSnapshotEntity;
import com.skillforge.server.entity.CanaryRolloutEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SKILL-CANARY-ROLLOUT V2 Phase 1.3 — {@link CanaryRolloutController} REST
 * shape + status code mapping tests. Uses MockMvc standalone setup (same
 * pattern as {@code ScheduledTaskControllerTest}). Service layer is mocked
 * — business logic is covered by {@code CanaryRolloutServiceTest}.
 */
@EnableWebMvc
@DisplayName("CanaryRolloutController")
class CanaryRolloutControllerTest {

    private CanaryRolloutService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(CanaryRolloutService.class);
        // Match Spring's default ObjectMapper config — java.md footgun #1.
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        CanaryRolloutController controller = new CanaryRolloutController(service);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    // ───────────────────────── helpers ─────────────────────────

    private CanaryRolloutEntity entity(Long id, String stage, int pct) {
        CanaryRolloutEntity c = new CanaryRolloutEntity();
        c.setId(id);
        c.setAgentId(42L);
        c.setSurfaceType("skill");
        c.setBaselineSkillName("my-skill");
        c.setCandidateSkillName("my-skill-v2");
        c.setRolloutStage(stage);
        c.setRolloutPercentage(pct);
        Instant now = Instant.now();
        c.setStartedAt(now);
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return c;
    }

    private CanaryMetricSnapshotEntity snapshot(Long id, int controlSamples, int candidateSamples) {
        CanaryMetricSnapshotEntity s = new CanaryMetricSnapshotEntity();
        s.setId(id);
        s.setCanaryId(7L);
        s.setBucketAt(Instant.now());
        s.setControlSampleSize(controlSamples);
        s.setControlSuccessCount(controlSamples - 1);
        s.setControlFailureCount(1);
        s.setControlAvgQuality(new BigDecimal("85.50"));
        s.setCandidateSampleSize(candidateSamples);
        s.setCandidateSuccessCount(candidateSamples - 1);
        s.setCandidateFailureCount(1);
        s.setCandidateAvgQuality(new BigDecimal("87.00"));
        s.setFailRateRatio(new BigDecimal("1.000"));
        s.setCreatedAt(Instant.now());
        return s;
    }

    // ───────────────────────── POST start ─────────────────────

    @Test
    @DisplayName("POST /api/canary/rollouts returns 201 with response body")
    void start_returns201() throws Exception {
        when(service.startCanary(eq(42L), eq("skill"), eq("my-skill"), eq("my-skill-v2"), eq(10)))
                .thenReturn(entity(99L, "canary", 10));

        mvc.perform(post("/api/canary/rollouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "agentId": 42,
                                  "surfaceType": "skill",
                                  "baselineSkillName": "my-skill",
                                  "candidateSkillName": "my-skill-v2",
                                  "percentage": 10
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(99))
                .andExpect(jsonPath("$.agentId").value(42))
                .andExpect(jsonPath("$.baselineSkillName").value("my-skill"))
                .andExpect(jsonPath("$.candidateSkillName").value("my-skill-v2"))
                .andExpect(jsonPath("$.rolloutStage").value("canary"))
                .andExpect(jsonPath("$.rolloutPercentage").value(10));
    }

    @Test
    @DisplayName("POST returns 400 when service throws IllegalArgumentException")
    void start_returns400_onBadRequest() throws Exception {
        when(service.startCanary(any(), any(), any(), any(), anyInt()))
                .thenThrow(new IllegalArgumentException("baselineSkillName is required"));

        mvc.perform(post("/api/canary/rollouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":42,\"surfaceType\":\"skill\",\"candidateSkillName\":\"v2\",\"percentage\":10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("baselineSkillName is required"));
    }

    @Test
    @DisplayName("POST returns 409 on CanaryStateException (active canary exists)")
    void start_returns409_onConflict() throws Exception {
        when(service.startCanary(any(), any(), any(), any(), anyInt()))
                .thenThrow(new CanaryStateException("Agent 42 already has an active canary (id=7)"));

        mvc.perform(post("/api/canary/rollouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"agentId":42,"surfaceType":"skill","baselineSkillName":"a","candidateSkillName":"b","percentage":10}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Agent 42 already has an active canary (id=7)"));
    }

    @Test
    @DisplayName("POST maps DataIntegrityViolationException → 409 (concurrent start race)")
    void start_returns409_whenDbUniqueViolated() throws Exception {
        // Phase 1.3 r1 W1 fix: two concurrent startCanary requests can both pass
        // the application-side uniqueness pre-check; the loser then trips the
        // Postgres `uq_canary_active` partial UNIQUE index. The handler must map
        // this to 409 (not Spring's default 500) so the client gets a retry-able
        // signal that "someone else won the race".
        when(service.startCanary(any(), any(), any(), any(), anyInt()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uq_canary_active"));

        mvc.perform(post("/api/canary/rollouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"agentId":42,"surfaceType":"skill","baselineSkillName":"a","candidateSkillName":"b","percentage":10}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Active canary already exists for this agent and surface"));
    }

    // ───────────────────────── PATCH step-up ──────────────────

    @Test
    @DisplayName("PATCH /{id}/step-up returns 200 with updated entity")
    void stepUp_returns200() throws Exception {
        when(service.stepUp(7L, 25)).thenReturn(entity(7L, "canary", 25));

        mvc.perform(patch("/api/canary/rollouts/7/step-up")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"percentage\":25}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.rolloutPercentage").value(25));
    }

    @Test
    @DisplayName("PATCH returns 400 when body missing percentage")
    void stepUp_returns400_whenPercentageMissing() throws Exception {
        mvc.perform(patch("/api/canary/rollouts/7/step-up")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("percentage is required"));
    }

    @Test
    @DisplayName("PATCH returns 404 when canary not found")
    void stepUp_returns404_whenNotFound() throws Exception {
        when(service.stepUp(eq(99L), anyInt()))
                .thenThrow(new NoSuchElementException("Canary not found: id=99"));

        mvc.perform(patch("/api/canary/rollouts/99/step-up")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"percentage\":25}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Canary not found: id=99"));
    }

    @Test
    @DisplayName("PATCH returns 409 when stage no longer canary")
    void stepUp_returns409_whenStageTerminal() throws Exception {
        when(service.stepUp(eq(7L), anyInt()))
                .thenThrow(new CanaryStateException("Cannot step up canary id=7 — stage is production"));

        mvc.perform(patch("/api/canary/rollouts/7/step-up")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"percentage\":100}"))
                .andExpect(status().isConflict());
    }

    // ───────────────────────── POST publish ───────────────────

    @Test
    @DisplayName("POST /{id}/publish returns 200 with stage=production, pct=100")
    void publish_returns200() throws Exception {
        when(service.publish(7L)).thenReturn(entity(7L, "production", 100));

        mvc.perform(post("/api/canary/rollouts/7/publish"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rolloutStage").value("production"))
                .andExpect(jsonPath("$.rolloutPercentage").value(100));
    }

    @Test
    @DisplayName("POST publish returns 404 when canary not found")
    void publish_returns404_whenNotFound() throws Exception {
        when(service.publish(99L))
                .thenThrow(new NoSuchElementException("Canary not found: id=99"));

        mvc.perform(post("/api/canary/rollouts/99/publish"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST publish returns 409 when canary already terminal")
    void publish_returns409_whenTerminal() throws Exception {
        when(service.publish(7L))
                .thenThrow(new CanaryStateException("Cannot publish canary id=7 — stage is rolled_back"));

        mvc.perform(post("/api/canary/rollouts/7/publish"))
                .andExpect(status().isConflict());
    }

    // ───────────────────────── POST rollback ──────────────────

    @Test
    @DisplayName("POST /{id}/rollback with reason returns 200")
    void rollback_returns200_withReason() throws Exception {
        when(service.rollback(eq(7L), eq("operator pushed wrong button")))
                .thenReturn(entity(7L, "rolled_back", 0));

        mvc.perform(post("/api/canary/rollouts/7/rollback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"operator pushed wrong button\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rolloutStage").value("rolled_back"))
                .andExpect(jsonPath("$.rolloutPercentage").value(0));
    }

    @Test
    @DisplayName("POST /{id}/rollback without body defaults reason='manual'")
    void rollback_returns200_withoutBody() throws Exception {
        when(service.rollback(eq(7L), eq("manual")))
                .thenReturn(entity(7L, "rolled_back", 0));

        mvc.perform(post("/api/canary/rollouts/7/rollback"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST rollback returns 409 when already terminal")
    void rollback_returns409_whenTerminal() throws Exception {
        when(service.rollback(anyLong(), anyString()))
                .thenThrow(new CanaryStateException("Cannot rollback canary id=7 — stage is production"));

        mvc.perform(post("/api/canary/rollouts/7/rollback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"manual\"}"))
                .andExpect(status().isConflict());
    }

    // ───────────────────────── GET detail ─────────────────────

    @Test
    @DisplayName("GET /{id} returns 200 with full entity")
    void detail_returns200() throws Exception {
        when(service.findById(7L)).thenReturn(entity(7L, "canary", 25));

        mvc.perform(get("/api/canary/rollouts/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.rolloutStage").value("canary"));
    }

    @Test
    @DisplayName("GET /{id} returns 404 when canary not found")
    void detail_returns404() throws Exception {
        when(service.findById(99L))
                .thenThrow(new NoSuchElementException("Canary not found: id=99"));

        mvc.perform(get("/api/canary/rollouts/99"))
                .andExpect(status().isNotFound());
    }

    // ───────────────────────── GET metrics ────────────────────

    @Test
    @DisplayName("GET /{id}/metrics returns 200 with snapshot list (default limit=24)")
    void metrics_returns200_withDefaultLimit() throws Exception {
        when(service.findMetricSnapshots(7L, 24))
                .thenReturn(List.of(snapshot(1L, 100, 50), snapshot(2L, 50, 25)));

        mvc.perform(get("/api/canary/rollouts/7/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].controlSampleSize").value(100))
                .andExpect(jsonPath("$[0].candidateSampleSize").value(50))
                .andExpect(jsonPath("$[1].id").value(2));
    }

    @Test
    @DisplayName("GET /{id}/metrics?limit=N honours custom limit")
    void metrics_honoursLimitParam() throws Exception {
        when(service.findMetricSnapshots(7L, 48)).thenReturn(List.of());

        mvc.perform(get("/api/canary/rollouts/7/metrics").param("limit", "48"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /{id}/metrics clamps limit to MAX_METRICS_LIMIT (168)")
    void metrics_clampsLimitToMax() throws Exception {
        when(service.findMetricSnapshots(eq(7L), eq(168))).thenReturn(List.of());

        mvc.perform(get("/api/canary/rollouts/7/metrics").param("limit", "9999"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /{id}/metrics returns 404 when canary not found")
    void metrics_returns404_whenCanaryMissing() throws Exception {
        when(service.findMetricSnapshots(eq(99L), anyInt()))
                .thenThrow(new NoSuchElementException("Canary not found: id=99"));

        mvc.perform(get("/api/canary/rollouts/99/metrics"))
                .andExpect(status().isNotFound());
    }

    // ───────────────────────── GET list ───────────────────────

    @Test
    @DisplayName("GET /?agentId= returns list filtered by agent (default surface=skill)")
    void list_returnsByAgent() throws Exception {
        when(service.listByAgent(42L, null, null))
                .thenReturn(List.of(entity(1L, "canary", 25), entity(2L, "rolled_back", 0)));

        mvc.perform(get("/api/canary/rollouts").param("agentId", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[1].id").value(2));
    }

    @Test
    @DisplayName("GET /?agentId=&stage=canary filters by stage")
    void list_filtersByStage() throws Exception {
        when(service.listByAgent(42L, null, "canary"))
                .thenReturn(List.of(entity(1L, "canary", 25)));

        mvc.perform(get("/api/canary/rollouts")
                        .param("agentId", "42")
                        .param("stage", "canary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].rolloutStage").value("canary"));
    }

    @Test
    @DisplayName("GET / returns 400 when service rejects bad input")
    void list_returns400_whenInvalidSurface() throws Exception {
        when(service.listByAgent(eq(42L), eq("prompt"), any()))
                .thenThrow(new IllegalArgumentException("surfaceType must be 'skill'"));

        mvc.perform(get("/api/canary/rollouts")
                        .param("agentId", "42")
                        .param("surfaceType", "prompt"))
                .andExpect(status().isBadRequest());
    }
}
