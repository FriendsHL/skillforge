package com.skillforge.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.flywheel.run.FlywheelRunEntity;
import com.skillforge.server.flywheel.run.FlywheelRunRepository;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.MemoryProposalRepository;
import com.skillforge.workflow.dto.AutoEvolvingDtos.OverviewResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AutoEvolvingOverviewService} — KPI counts (incl. the
 * date-windowed completed count), cross-agent report mapping with batch
 * agent-name resolution + lenient topIssueCount parse, and the failed-run
 * anomaly mapping.
 */
@DisplayName("AutoEvolvingOverviewService")
class AutoEvolvingOverviewServiceTest {

    private static final Instant NOW = Instant.parse("2026-05-29T12:00:00Z");

    private FlywheelRunRepository runRepository;
    private MemoryProposalRepository memoryProposalRepository;
    private AgentRepository agentRepository;
    private AutoEvolvingOverviewService service;

    @BeforeEach
    void setUp() {
        runRepository = mock(FlywheelRunRepository.class);
        memoryProposalRepository = mock(MemoryProposalRepository.class);
        agentRepository = mock(AgentRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();
        Clock fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new AutoEvolvingOverviewService(
                runRepository, memoryProposalRepository, agentRepository, objectMapper, fixedClock);
    }

    @Test
    @DisplayName("KPI counts wire through; completed-this-week uses a clock-derived window")
    void buildOverview_kpiCounts() {
        when(runRepository.countByLoopKindAndStatus("workflow", "running")).thenReturn(2L);
        when(runRepository.countByLoopKindAndStatusAndCreatedAtAfter(
                eq("workflow"), eq("completed"), any())).thenReturn(5L);
        when(memoryProposalRepository.countByStatus("proposed")).thenReturn(3L);

        OverviewResponse res = service.buildOverview(7, 8);

        assertThat(res.kpi().workflowRunning()).isEqualTo(2L);
        assertThat(res.kpi().workflowCompletedThisWeek()).isEqualTo(5L);
        assertThat(res.kpi().memoryProposalPending()).isEqualTo(3L);
        assertThat(res.kpi().autoResearchPending()).isNull();  // V1 placeholder

        // window = NOW - 7d (strictly-after semantics handled by the derived query)
        verify(runRepository).countByLoopKindAndStatusAndCreatedAtAfter(
                "workflow", "completed", NOW.minus(java.time.Duration.ofDays(7)));
    }

    @Test
    @DisplayName("recentReports: cross-agent rows mapped + agent names batch-resolved + topIssueCount parsed")
    void buildOverview_recentReports() {
        FlywheelRunEntity r1 = report("rep-1", 7L, "completed",
                "{\"topIssues\":[{\"id\":\"i1\"},{\"id\":\"i2\"}]}");
        FlywheelRunEntity r2 = report("rep-2", 9L, "running", null);  // no summary yet
        when(runRepository.findByLoopKindOrderByCreatedAtDescIdDesc(eq("opt_report"), any()))
                .thenReturn(List.of(r1, r2));
        when(agentRepository.findAllById(any()))
                .thenReturn(List.of(agent(7L, "design-agent"), agent(9L, "router-agent")));
        when(memoryProposalRepository.countByStatus("proposed")).thenReturn(0L);

        OverviewResponse res = service.buildOverview(7, 8);

        assertThat(res.recentReports()).hasSize(2);
        assertThat(res.recentReports().get(0).reportId()).isEqualTo("rep-1");
        assertThat(res.recentReports().get(0).agentName()).isEqualTo("design-agent");
        assertThat(res.recentReports().get(0).topIssueCount()).isEqualTo(2);
        assertThat(res.recentReports().get(1).agentName()).isEqualTo("router-agent");
        assertThat(res.recentReports().get(1).topIssueCount()).isZero();  // null summary → 0
    }

    @Test
    @DisplayName("topIssueCount is 0 (never throws) on malformed summary_json")
    void buildOverview_malformedSummaryJson_toleratedAsZero() {
        FlywheelRunEntity bad = report("rep-bad", 1L, "completed", "{not valid json");
        when(runRepository.findByLoopKindOrderByCreatedAtDescIdDesc(eq("opt_report"), any()))
                .thenReturn(List.of(bad));
        when(agentRepository.findAllById(any())).thenReturn(List.of(agent(1L, "a")));
        when(memoryProposalRepository.countByStatus("proposed")).thenReturn(0L);

        OverviewResponse res = service.buildOverview(7, 8);

        assertThat(res.recentReports().get(0).topIssueCount()).isZero();
    }

    @Test
    @DisplayName("recentAnomalies: failed workflow runs mapped with workflow_name + errorReason")
    void buildOverview_recentAnomalies() {
        FlywheelRunEntity failed = new FlywheelRunEntity();
        failed.setId("run-err");
        failed.setStatus("error");
        failed.setInputJson("{\"workflow_name\":\"opt-report\"}");
        failed.setErrorReason("LLM timeout");
        failed.setUpdatedAt(NOW);
        when(runRepository.findByLoopKindAndStatusOrderByCreatedAtDescIdDesc(
                eq("workflow"), eq("error"), any())).thenReturn(List.of(failed));
        when(memoryProposalRepository.countByStatus("proposed")).thenReturn(0L);

        OverviewResponse res = service.buildOverview(7, 8);

        assertThat(res.recentAnomalies()).hasSize(1);
        assertThat(res.recentAnomalies().get(0).runId()).isEqualTo("run-err");
        assertThat(res.recentAnomalies().get(0).name()).isEqualTo("opt-report");
        assertThat(res.recentAnomalies().get(0).status()).isEqualTo("error");
        assertThat(res.recentAnomalies().get(0).errorReason()).isEqualTo("LLM timeout");
    }

    @Test
    @DisplayName("empty repositories → empty lists, no NPE")
    void buildOverview_emptyData() {
        when(memoryProposalRepository.countByStatus("proposed")).thenReturn(0L);

        OverviewResponse res = service.buildOverview(7, 8);

        assertThat(res.recentReports()).isEmpty();
        assertThat(res.recentAnomalies()).isEmpty();
        assertThat(res.kpi().workflowRunning()).isZero();
    }

    private static FlywheelRunEntity report(String id, long agentId, String status, String summaryJson) {
        FlywheelRunEntity r = new FlywheelRunEntity();
        r.setId(id);
        r.setAgentId(agentId);
        r.setStatus(status);
        r.setWindowEnd(NOW);
        r.setSummaryJson(summaryJson);
        return r;
    }

    private static AgentEntity agent(long id, String name) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setName(name);
        return a;
    }
}
