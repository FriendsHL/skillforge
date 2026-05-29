package com.skillforge.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.workflow.dto.AutoEvolvingDtos.KpiDto;
import com.skillforge.workflow.dto.AutoEvolvingDtos.OverviewResponse;
import com.skillforge.workflow.dto.AutoEvolvingDtos.RecentAnomalyDto;
import com.skillforge.workflow.dto.AutoEvolvingDtos.RecentReportDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Wiring tests for {@link AutoEvolvingController} — the single-object overview
 * envelope (footgun #6b), nested report/anomaly shape, and param clamping.
 */
@EnableWebMvc
@DisplayName("AutoEvolvingController")
class AutoEvolvingControllerTest {

    private AutoEvolvingOverviewService overviewService;
    private ObjectMapper objectMapper;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        overviewService = mock(AutoEvolvingOverviewService.class);
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        AutoEvolvingController controller = new AutoEvolvingController(overviewService);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    @DisplayName("GET /overview → single {kpi, recentReports, recentAnomalies} object (not a bare array)")
    void overview_envelopeShape() throws Exception {
        OverviewResponse res = new OverviewResponse(
                new KpiDto(2L, 5L, 3L, null),
                List.of(new RecentReportDto("rep-1", 7L, "design-agent",
                        "2026-05-22T00:00:00Z", "completed", 2)),
                List.of(new RecentAnomalyDto("run-err", "opt-report", "error",
                        "LLM timeout", "2026-05-29T00:00:00Z")));
        when(overviewService.buildOverview(7, 8)).thenReturn(res);

        mvc.perform(get("/api/autoevolving/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kpi.workflowRunning").value(2))
                .andExpect(jsonPath("$.kpi.workflowCompletedThisWeek").value(5))
                .andExpect(jsonPath("$.kpi.memoryProposalPending").value(3))
                .andExpect(jsonPath("$.kpi.autoResearchPending").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.recentReports").isArray())
                .andExpect(jsonPath("$.recentReports[0].reportId").value("rep-1"))
                .andExpect(jsonPath("$.recentReports[0].agentName").value("design-agent"))
                .andExpect(jsonPath("$.recentReports[0].topIssueCount").value(2))
                .andExpect(jsonPath("$.recentAnomalies").isArray())
                .andExpect(jsonPath("$.recentAnomalies[0].runId").value("run-err"))
                .andExpect(jsonPath("$.recentAnomalies[0].name").value("opt-report"))
                .andExpect(jsonPath("$.recentAnomalies[0].errorReason").value("LLM timeout"));
    }

    @Test
    @DisplayName("params clamp: weekDays / reportLimit bounded; defaults applied when absent")
    void overview_paramClamp() throws Exception {
        when(overviewService.buildOverview(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new OverviewResponse(new KpiDto(0, 0, 0, null), List.of(), List.of()));

        // weekDays=999 → clamp to 90 (MAX); reportLimit=999 → clamp to 50 (MAX)
        mvc.perform(get("/api/autoevolving/overview")
                        .param("weekDays", "999").param("reportLimit", "999"))
                .andExpect(status().isOk());
        verify(overviewService).buildOverview(90, 50);

        // weekDays=0 → clamp to 1 (MIN); reportLimit=0 → clamp to 1 (MIN)
        mvc.perform(get("/api/autoevolving/overview")
                        .param("weekDays", "0").param("reportLimit", "0"))
                .andExpect(status().isOk());
        verify(overviewService).buildOverview(1, 1);
    }

    @Test
    @DisplayName("FE-BE roundtrip: serialized field names match the TS OverviewResponse contract")
    void overview_jsonRoundtrip_matchesFeContract() throws Exception {
        OverviewResponse res = new OverviewResponse(
                new KpiDto(1L, 2L, 3L, null),
                List.of(new RecentReportDto("rep-1", 7L, "a", "2026-05-22T00:00:00Z", "completed", 1)),
                List.of(new RecentAnomalyDto("run-1", "demo", "error", "boom", "2026-05-29T00:00:00Z")));
        String json = objectMapper.writeValueAsString(res);

        // camelCase field names the FE TS interface expects
        assertThat(json).contains("\"kpi\":");
        assertThat(json).contains("\"workflowRunning\":");
        assertThat(json).contains("\"workflowCompletedThisWeek\":");
        assertThat(json).contains("\"memoryProposalPending\":");
        assertThat(json).contains("\"autoResearchPending\":");
        assertThat(json).contains("\"recentReports\":");
        assertThat(json).contains("\"topIssueCount\":");
        assertThat(json).contains("\"recentAnomalies\":");
        assertThat(json).contains("\"errorReason\":");
        // single object, not wrapped in items[]
        assertThat(json).doesNotContain("\"items\":");
    }

    @Test
    @DisplayName("default params (no query) → buildOverview(7, 8)")
    void overview_defaults() throws Exception {
        when(overviewService.buildOverview(eq(7), eq(8)))
                .thenReturn(new OverviewResponse(new KpiDto(0, 0, 0, null), List.of(), List.of()));

        mvc.perform(get("/api/autoevolving/overview"))
                .andExpect(status().isOk());
        verify(overviewService).buildOverview(7, 8);
    }
}
