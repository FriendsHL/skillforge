package com.skillforge.server.attribution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.server.entity.OptimizationEventEntity;
import com.skillforge.server.repository.OptimizationEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V3 ATTRIBUTION-AGENT Phase 1.4 — {@link AttributionEventController} REST
 * shape + status mapping. MockMvc standaloneSetup mirroring
 * {@code CanaryRolloutControllerTest}.
 */
@EnableWebMvc
@DisplayName("AttributionEventController")
class AttributionEventControllerTest {

    private OptimizationEventRepository eventRepository;
    private AttributionApprovalService approvalService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        eventRepository = mock(OptimizationEventRepository.class);
        approvalService = mock(AttributionApprovalService.class);
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        AttributionEventController controller =
                new AttributionEventController(eventRepository, approvalService);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    private OptimizationEventEntity entity(Long id, String stage) {
        OptimizationEventEntity e = new OptimizationEventEntity();
        e.setId(id);
        e.setPatternId(42L);
        e.setAgentId(7L);
        e.setSurfaceType(OptimizationEventEntity.SURFACE_SKILL);
        e.setStage(stage);
        Instant now = Instant.parse("2026-05-15T10:00:00Z");
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        return e;
    }

    @Test
    @DisplayName("GET /api/attribution/events?stage=proposal_pending → 200 + items + total field")
    void list_byStage_returnsItemsAndTotal() throws Exception {
        // Phase 1.4 reviewer fix W2/W3: list now goes through findFiltered + Page
        // so total comes from page.getTotalElements() (truthful for FE pagination).
        Page<OptimizationEventEntity> page = new PageImpl<>(
                List.of(entity(1L, OptimizationEventEntity.STAGE_PROPOSAL_PENDING),
                        entity(2L, OptimizationEventEntity.STAGE_PROPOSAL_PENDING)),
                PageRequest.of(0, 20),
                /*total*/ 2);
        when(eventRepository.findFiltered(
                eq(OptimizationEventEntity.STAGE_PROPOSAL_PENDING),
                eq(null), eq(null), any()))
                .thenReturn(page);

        mvc.perform(get("/api/attribution/events").param("stage", "proposal_pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].stage").value("proposal_pending"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    @DisplayName("Phase 1.4 W2/W3: combined filter (stage+agentId+surfaceType) returns truthful total")
    void list_combinedFilter_returnsTruthfulTotal() throws Exception {
        // Page 0 of 7 total matching rows — controller delegates to repository's
        // page.getTotalElements() so FE knows there are more pages even when
        // page 0's items.length() < total.
        Page<OptimizationEventEntity> page = new PageImpl<>(
                List.of(entity(10L, OptimizationEventEntity.STAGE_PROPOSAL_PENDING),
                        entity(11L, OptimizationEventEntity.STAGE_PROPOSAL_PENDING),
                        entity(12L, OptimizationEventEntity.STAGE_PROPOSAL_PENDING)),
                PageRequest.of(0, 3),
                /*total*/ 7);
        when(eventRepository.findFiltered(
                eq(OptimizationEventEntity.STAGE_PROPOSAL_PENDING),
                eq(7L),
                eq("skill"),
                any()))
                .thenReturn(page);

        mvc.perform(get("/api/attribution/events")
                        .param("stage", "proposal_pending")
                        .param("agentId", "7")
                        .param("surfaceType", "skill")
                        .param("size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.size").value(3))
                .andExpect(jsonPath("$.total").value(7));
    }

    @Test
    @DisplayName("Phase 1.4 W2/W3: empty filter (no params) → all-row scan with total")
    void list_noFilters_returnsAllRowsTotal() throws Exception {
        Page<OptimizationEventEntity> page = new PageImpl<>(
                List.of(entity(1L, OptimizationEventEntity.STAGE_PROPOSAL_PENDING)),
                PageRequest.of(0, 20),
                /*total*/ 1);
        when(eventRepository.findFiltered(eq(null), eq(null), eq(null), any()))
                .thenReturn(page);

        mvc.perform(get("/api/attribution/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    @DisplayName("GET /api/attribution/events/{id} → 200 OK with DTO; missing id → 404")
    void detail_returnsDtoOr404() throws Exception {
        when(eventRepository.findById(99L)).thenReturn(Optional.of(entity(99L,
                OptimizationEventEntity.STAGE_CANDIDATE_READY)));
        when(eventRepository.findById(404L)).thenReturn(Optional.empty());

        mvc.perform(get("/api/attribution/events/99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(99))
                .andExpect(jsonPath("$.stage").value("candidate_ready"));

        mvc.perform(get("/api/attribution/events/404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("POST /{id}/approve → 200 + DTO when service succeeds")
    void approve_returnsUpdatedDto() throws Exception {
        when(approvalService.approve(eq(99L), eq(7L)))
                .thenReturn(entity(99L, OptimizationEventEntity.STAGE_CANDIDATE_READY));

        mvc.perform(post("/api/attribution/events/99/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approverUserId\": 7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("candidate_ready"));
    }

    @Test
    @DisplayName("POST /{id}/approve → 409 when service throws IllegalStateException (wrong stage)")
    void approve_returnsConflictOnIllegalState() throws Exception {
        when(approvalService.approve(anyLong(), any()))
                .thenThrow(new IllegalStateException("Illegal stage transition: ab_running → proposal_approved"));

        mvc.perform(post("/api/attribution/events/99/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approverUserId\": 7}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Illegal stage transition")));
    }

    @Test
    @DisplayName("POST /{id}/approve → 404 when service throws \"not found\" IAE")
    void approve_returnsNotFoundForMissingEvent() throws Exception {
        when(approvalService.approve(anyLong(), any()))
                .thenThrow(new IllegalArgumentException("optimization event not found: 9999"));

        mvc.perform(post("/api/attribution/events/9999/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approverUserId\": 7}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /{id}/reject → 200 + DTO; reason flows to service")
    void reject_returnsUpdatedDto() throws Exception {
        when(approvalService.reject(eq(99L), eq(7L), eq("duplicate of #95")))
                .thenReturn(entity(99L, OptimizationEventEntity.STAGE_PROPOSAL_REJECTED));

        mvc.perform(post("/api/attribution/events/99/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approverUserId\": 7, \"reason\": \"duplicate of #95\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("proposal_rejected"));
    }

    @Test
    @DisplayName("POST /{id}/retry → 200 + DTO when retry succeeds")
    void retry_returnsUpdatedDto() throws Exception {
        when(approvalService.retryCandidateGeneration(eq(99L), eq(7L)))
                .thenReturn(entity(99L, OptimizationEventEntity.STAGE_CANDIDATE_READY));

        mvc.perform(post("/api/attribution/events/99/retry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approverUserId\": 7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("candidate_ready"));
    }
}
