package com.skillforge.server.optreport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.OptReportEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.OptReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
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
 * Wiring-level tests for {@link OptReportController} — 202 happy path,
 * 404 when target agent missing, 400 when agentId / windowDays invalid,
 * 200 list + 200 detail / 404 detail.
 */
@EnableWebMvc
@DisplayName("OptReportController")
class OptReportControllerTest {

    private OptReportService reportService;
    private OptReportRepository reportRepository;
    private AgentRepository agentRepository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reportService = mock(OptReportService.class);
        reportRepository = mock(OptReportRepository.class);
        agentRepository = mock(AgentRepository.class);

        ObjectMapper objectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        OptReportController controller = new OptReportController(
                reportService, reportRepository, agentRepository);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    @DisplayName("POST /agents/{id}/generate-report → 202 + reportId")
    void generateReport_happyPath_returns202() throws Exception {
        AgentEntity target = newAgent(7L, "design-agent");
        when(agentRepository.findById(7L)).thenReturn(Optional.of(target));

        OptReportEntity created = new OptReportEntity();
        created.setId("rep-uuid");
        created.setAgentId(7L);
        created.setWindowStart(Instant.parse("2026-05-15T00:00:00Z"));
        created.setWindowEnd(Instant.parse("2026-05-22T00:00:00Z"));
        created.setStatus(OptReportEntity.STATUS_RUNNING);
        when(reportService.startReport(eq(7L), anyInt())).thenReturn(created);

        mvc.perform(post("/api/flywheel/agents/7/generate-report"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.reportId").value("rep-uuid"))
                .andExpect(jsonPath("$.agentId").value(7))
                .andExpect(jsonPath("$.agentName").value("design-agent"))
                .andExpect(jsonPath("$.status").value("running"));

        // Default windowDays=14 (V1.1 — wider window to exercise SubAgent fan-out)
        verify(reportService).startReport(eq(7L), eq(14));
    }

    @Test
    @DisplayName("POST /generate-report?windowDays=999 → clamps to 30")
    void generateReport_windowDaysClamp() throws Exception {
        AgentEntity target = newAgent(7L, "design-agent");
        when(agentRepository.findById(7L)).thenReturn(Optional.of(target));
        OptReportEntity created = new OptReportEntity();
        created.setId("rep-uuid");
        created.setAgentId(7L);
        created.setWindowStart(Instant.parse("2026-04-22T00:00:00Z"));
        created.setWindowEnd(Instant.parse("2026-05-22T00:00:00Z"));
        created.setStatus(OptReportEntity.STATUS_RUNNING);
        when(reportService.startReport(eq(7L), eq(30))).thenReturn(created);

        mvc.perform(post("/api/flywheel/agents/7/generate-report")
                        .param("windowDays", "999"))
                .andExpect(status().isAccepted());
        verify(reportService).startReport(eq(7L), eq(30));
    }

    @Test
    @DisplayName("POST /generate-report on missing agent → 404")
    void generateReport_missingAgent_returns404() throws Exception {
        when(agentRepository.findById(99L)).thenReturn(Optional.empty());

        mvc.perform(post("/api/flywheel/agents/99/generate-report"))
                .andExpect(status().isNotFound());

        verify(reportService, never()).startReport(anyLong(), anyInt());
    }

    @Test
    @DisplayName("POST /generate-report with agentId=0 → 400")
    void generateReport_invalidAgentId_returns400() throws Exception {
        mvc.perform(post("/api/flywheel/agents/0/generate-report"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /agents/{id}/reports → 200 + list")
    void listReports_returns200() throws Exception {
        OptReportEntity r1 = new OptReportEntity();
        r1.setId("rep-1");
        r1.setAgentId(7L);
        r1.setWindowStart(Instant.parse("2026-05-15T00:00:00Z"));
        r1.setWindowEnd(Instant.parse("2026-05-22T00:00:00Z"));
        r1.setStatus(OptReportEntity.STATUS_COMPLETED);
        r1.setCreatedAt(Instant.parse("2026-05-22T01:00:00Z"));
        r1.setUpdatedAt(Instant.parse("2026-05-22T01:02:00Z"));

        when(reportRepository.findByAgentIdOrderByCreatedAtDesc(eq(7L), any()))
                .thenReturn(java.util.List.of(r1));

        mvc.perform(get("/api/flywheel/agents/7/reports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items[0].reportId").value("rep-1"))
                .andExpect(jsonPath("$.items[0].status").value("completed"))
                .andExpect(jsonPath("$.limit").value(20));
    }

    @Test
    @DisplayName("GET /reports/{id} → 200 with full body")
    void getReport_returns200() throws Exception {
        OptReportEntity r = new OptReportEntity();
        r.setId("rep-1");
        r.setAgentId(7L);
        r.setWindowStart(Instant.parse("2026-05-15T00:00:00Z"));
        r.setWindowEnd(Instant.parse("2026-05-22T00:00:00Z"));
        r.setStatus(OptReportEntity.STATUS_COMPLETED);
        r.setContentMd("# Hello report");
        r.setSummaryJson("{\"successRate\":0.42}");
        when(reportRepository.findById("rep-1")).thenReturn(Optional.of(r));

        mvc.perform(get("/api/flywheel/reports/rep-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportId").value("rep-1"))
                .andExpect(jsonPath("$.contentMd").value("# Hello report"))
                .andExpect(jsonPath("$.summaryJson").value("{\"successRate\":0.42}"));
    }

    @Test
    @DisplayName("GET /reports/{id} on missing id → 404")
    void getReport_missing_returns404() throws Exception {
        when(reportRepository.findById("nope")).thenReturn(Optional.empty());
        mvc.perform(get("/api/flywheel/reports/nope"))
                .andExpect(status().isNotFound());
    }

    private static AgentEntity newAgent(long id, String name) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setName(name);
        return a;
    }
}
