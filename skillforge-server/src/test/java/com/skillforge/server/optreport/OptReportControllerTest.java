package com.skillforge.server.optreport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.flywheel.run.FlywheelRunEntity;
import com.skillforge.server.flywheel.run.FlywheelRunRepository;
import com.skillforge.server.entity.OptimizationEventEntity;
import com.skillforge.server.repository.AgentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
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
 * Wiring-level tests for {@link OptReportController} — 202 happy path,
 * 404 when target agent missing, 400 when agentId / windowDays invalid,
 * 200 list + 200 detail / 404 detail.
 */
@EnableWebMvc
@DisplayName("OptReportController")
class OptReportControllerTest {

    private OptReportService reportService;
    private FlywheelRunRepository reportRepository;
    private AgentRepository agentRepository;
    private OptReportToEventBridge bridge;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reportService = mock(OptReportService.class);
        reportRepository = mock(FlywheelRunRepository.class);
        agentRepository = mock(AgentRepository.class);
        // V1.2 r2 fix: enrichTopIssues moved from Controller → Bridge. Stub
        // the bridge method directly per test case below instead of mocking
        // eventRepository + summaryParser at the controller level.
        bridge = mock(OptReportToEventBridge.class);

        ObjectMapper objectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        OptReportController controller = new OptReportController(
                reportService, reportRepository, agentRepository, bridge);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    @DisplayName("POST /agents/{id}/generate-report → 202 + reportId")
    void generateReport_happyPath_returns202() throws Exception {
        AgentEntity target = newAgent(7L, "design-agent");
        when(agentRepository.findById(7L)).thenReturn(Optional.of(target));

        FlywheelRunEntity created = new FlywheelRunEntity();
        created.setId("rep-uuid");
        created.setAgentId(7L);
        created.setWindowStart(Instant.parse("2026-05-15T00:00:00Z"));
        created.setWindowEnd(Instant.parse("2026-05-22T00:00:00Z"));
        created.setStatus(FlywheelRunEntity.STATUS_RUNNING);
        when(reportService.startReport(eq(7L), anyInt())).thenReturn(created);

        mvc.perform(post("/api/flywheel/agents/7/generate-report"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.reportId").value("rep-uuid"))
                .andExpect(jsonPath("$.agentId").value(7))
                .andExpect(jsonPath("$.agentName").value("design-agent"))
                .andExpect(jsonPath("$.status").value("running"));

        // V1.4: default windowDays=20 (was 14 in V1.1)
        verify(reportService).startReport(eq(7L), eq(20));
    }

    @Test
    @DisplayName("POST /generate-report?windowDays=999 → clamps to 30")
    void generateReport_windowDaysClamp() throws Exception {
        AgentEntity target = newAgent(7L, "design-agent");
        when(agentRepository.findById(7L)).thenReturn(Optional.of(target));
        FlywheelRunEntity created = new FlywheelRunEntity();
        created.setId("rep-uuid");
        created.setAgentId(7L);
        created.setWindowStart(Instant.parse("2026-04-22T00:00:00Z"));
        created.setWindowEnd(Instant.parse("2026-05-22T00:00:00Z"));
        created.setStatus(FlywheelRunEntity.STATUS_RUNNING);
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
        FlywheelRunEntity r1 = new FlywheelRunEntity();
        r1.setId("rep-1");
        r1.setAgentId(7L);
        r1.setWindowStart(Instant.parse("2026-05-15T00:00:00Z"));
        r1.setWindowEnd(Instant.parse("2026-05-22T00:00:00Z"));
        r1.setStatus(FlywheelRunEntity.STATUS_COMPLETED);
        r1.setCreatedAt(Instant.parse("2026-05-22T01:00:00Z"));
        r1.setUpdatedAt(Instant.parse("2026-05-22T01:02:00Z"));

        when(reportRepository.findByAgentIdAndLoopKindOrderByCreatedAtDesc(
                eq(7L), eq(FlywheelRunEntity.LOOP_KIND_OPT_REPORT), any()))
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
        FlywheelRunEntity r = new FlywheelRunEntity();
        r.setId("rep-1");
        r.setAgentId(7L);
        r.setWindowStart(Instant.parse("2026-05-15T00:00:00Z"));
        r.setWindowEnd(Instant.parse("2026-05-22T00:00:00Z"));
        r.setStatus(FlywheelRunEntity.STATUS_COMPLETED);
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

    // ─────────────────────────────────────────────────────────────────────
    // V1.2 Convert to Event endpoint
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("V1.2 POST convert-to-event → 200 + eventId + alreadyConverted=false")
    void convertIssueToEvent_happyPath_returns200() throws Exception {
        OptimizationEventEntity created = new OptimizationEventEntity();
        created.setId(42L);
        created.setStage(OptimizationEventEntity.STAGE_PROPOSAL_PENDING);
        created.setSurfaceType("skill");
        when(bridge.convertIssueToEvent("rep-1", "issue-1"))
                .thenReturn(new OptReportToEventBridge.ConvertResult(created, false));

        mvc.perform(post("/api/flywheel/reports/rep-1/issues/issue-1/convert-to-event"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(42))
                .andExpect(jsonPath("$.alreadyConverted").value(false))
                .andExpect(jsonPath("$.stage").value("proposal_pending"))
                .andExpect(jsonPath("$.surface").value("skill"))
                .andExpect(jsonPath("$.reportId").value("rep-1"))
                .andExpect(jsonPath("$.issueId").value("issue-1"));
    }

    @Test
    @DisplayName("V1.2 POST convert-to-event (already converted) → 200 + alreadyConverted=true")
    void convertIssueToEvent_idempotent_returns200() throws Exception {
        OptimizationEventEntity existing = new OptimizationEventEntity();
        existing.setId(99L);
        existing.setStage(OptimizationEventEntity.STAGE_PROPOSAL_PENDING);
        existing.setSurfaceType("prompt");
        when(bridge.convertIssueToEvent("rep-1", "issue-1"))
                .thenReturn(new OptReportToEventBridge.ConvertResult(existing, true));

        mvc.perform(post("/api/flywheel/reports/rep-1/issues/issue-1/convert-to-event"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(99))
                .andExpect(jsonPath("$.alreadyConverted").value(true));
    }

    @Test
    @DisplayName("V1.2 POST convert-to-event (report not found) → 404")
    void convertIssueToEvent_reportMissing_returns404() throws Exception {
        when(bridge.convertIssueToEvent("nope", "issue-1"))
                .thenThrow(new NoSuchElementException("Report not found: id=nope"));

        mvc.perform(post("/api/flywheel/reports/nope/issues/issue-1/convert-to-event"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("V1.2 POST convert-to-event (issue not found) → 404")
    void convertIssueToEvent_issueMissing_returns404() throws Exception {
        when(bridge.convertIssueToEvent("rep-1", "issue-99"))
                .thenThrow(new NoSuchElementException("Issue not found in report: reportId=rep-1, issueId=issue-99"));

        mvc.perform(post("/api/flywheel/reports/rep-1/issues/issue-99/convert-to-event"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("V1.2 POST convert-to-event ('other' surface) → 400")
    void convertIssueToEvent_otherSurface_returns400() throws Exception {
        when(bridge.convertIssueToEvent("rep-1", "issue-1"))
                .thenThrow(new IllegalArgumentException(
                        "Issue suspectSurface='other' cannot be auto-converted to an OptimizationEvent."));

        mvc.perform(post("/api/flywheel/reports/rep-1/issues/issue-1/convert-to-event"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("V1.2 POST convert-to-event (report still running) → 400")
    void convertIssueToEvent_reportNotCompleted_returns400() throws Exception {
        when(bridge.convertIssueToEvent("rep-running", "issue-1"))
                .thenThrow(new IllegalStateException(
                        "Report status must be 'completed' to convert issues; got: running"));

        mvc.perform(post("/api/flywheel/reports/rep-running/issues/issue-1/convert-to-event"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("V1.2 GET /reports/{id} enriches topIssues with alreadyConverted flag")
    void getReport_v1_2_enrichesTopIssues() throws Exception {
        String summaryJson = """
                { "topIssues": [
                    {
                      "id": "issue-1",
                      "title": "ReadFile bug",
                      "severity": "high",
                      "sessionCount": 3,
                      "exampleSessionIds": ["sess-a"],
                      "suspectSurface": "skill",
                      "confidence": 0.85,
                      "suggestion": "Rewrite"
                    },
                    {
                      "id": "issue-2",
                      "title": "Loop",
                      "severity": "low",
                      "sessionCount": 1,
                      "exampleSessionIds": ["sess-b"],
                      "suspectSurface": "other",
                      "confidence": 0.4,
                      "suggestion": "Skip"
                    }
                ]}
                """;
        FlywheelRunEntity r = new FlywheelRunEntity();
        r.setId("rep-1");
        r.setAgentId(7L);
        r.setWindowStart(Instant.parse("2026-05-15T00:00:00Z"));
        r.setWindowEnd(Instant.parse("2026-05-22T00:00:00Z"));
        r.setStatus(FlywheelRunEntity.STATUS_COMPLETED);
        r.setContentMd("# md");
        r.setSummaryJson(summaryJson);
        when(reportRepository.findById("rep-1")).thenReturn(Optional.of(r));

        // V1.2 r2: bridge.enrichTopIssues stubbed directly (was inline in Controller before).
        java.util.LinkedHashMap<String, Object> issue1 = new java.util.LinkedHashMap<>();
        issue1.put("id", "issue-1");
        issue1.put("title", "ReadFile bug");
        issue1.put("severity", "high");
        issue1.put("suspectSurface", "skill");
        issue1.put("alreadyConverted", true);
        issue1.put("convertedEventId", 123L);
        issue1.put("convertible", true);
        java.util.LinkedHashMap<String, Object> issue2 = new java.util.LinkedHashMap<>();
        issue2.put("id", "issue-2");
        issue2.put("title", "Loop");
        issue2.put("severity", "low");
        issue2.put("suspectSurface", "other");
        issue2.put("alreadyConverted", false);
        issue2.put("convertedEventId", null);
        issue2.put("convertible", false);
        when(bridge.enrichTopIssues(eq("rep-1"), anyString()))
                .thenReturn(List.of(issue1, issue2));

        mvc.perform(get("/api/flywheel/reports/rep-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.topIssues").isArray())
                .andExpect(jsonPath("$.summary.topIssues[0].id").value("issue-1"))
                .andExpect(jsonPath("$.summary.topIssues[0].alreadyConverted").value(true))
                .andExpect(jsonPath("$.summary.topIssues[0].convertedEventId").value(123))
                .andExpect(jsonPath("$.summary.topIssues[0].convertible").value(true))
                .andExpect(jsonPath("$.summary.topIssues[1].id").value("issue-2"))
                .andExpect(jsonPath("$.summary.topIssues[1].alreadyConverted").value(false))
                .andExpect(jsonPath("$.summary.topIssues[1].convertible").value(false));  // 'other' surface
    }

    @Test
    @DisplayName("V1.2 GET /reports/{id} on V1.0/V1.1 report (no V1.2 schema) → summary=null fallback")
    void getReport_legacyReportNoSchema_returnsNullSummary() throws Exception {
        FlywheelRunEntity r = new FlywheelRunEntity();
        r.setId("rep-legacy");
        r.setAgentId(7L);
        r.setStatus(FlywheelRunEntity.STATUS_COMPLETED);
        r.setSummaryJson("{ \"totalSessions\": 5, \"successRate\": 0.4 }");  // no topIssues key
        when(reportRepository.findById("rep-legacy")).thenReturn(Optional.of(r));
        // Bridge returns empty list for legacy / parse-fail reports — Controller
        // converts that to summary=null in the response so FE falls back to
        // markdown-only view (V1.2 r2 fix BLOCKER-1).
        when(bridge.enrichTopIssues(eq("rep-legacy"), anyString()))
                .thenReturn(java.util.Collections.emptyList());

        mvc.perform(get("/api/flywheel/reports/rep-legacy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value(org.hamcrest.Matchers.nullValue()));
    }

    private static AgentEntity newAgent(long id, String name) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setName(name);
        return a;
    }
}
