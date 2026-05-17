package com.skillforge.server.controller;

import com.skillforge.server.dto.SystemAgentMonitorResponse;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.service.SystemAgentMonitorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SYSTEM-AGENT-TYPING Phase 2.1: contract test for the new
 * {@code GET /api/system-agents/monitor} endpoint that powers the FE
 * {@code AgentList} inline observability cards (PRD F4).
 *
 * <p>Phase 2.0 (this test): red — neither {@code SystemAgentMonitorController}
 * nor {@code SystemAgentMonitorService} nor the {@code SystemAgentMonitorResponse}
 * DTO exist yet, so this file fails to compile. Phase 2.1 lands the
 * implementation and flips it green.
 *
 * <p>Aggregation contract (per tech-design.md §Service layer): one row per
 * system agent (typically 5 — {@code memory-curator} / {@code session-annotator} /
 * {@code metrics-collector} / {@code attribution-curator} /
 * {@code user-simulator}), each row joining:
 * <ul>
 *   <li>{@code t_agent.name / description} — agent identity</li>
 *   <li>{@code t_scheduled_task.cron_expr / last_fire_at} — cron metadata</li>
 *   <li>{@code t_scheduled_task_run} latest status — last run health</li>
 *   <li>{@code t_scheduled_task_run} 7d count — trigger frequency</li>
 *   <li>per-agent output table 7d count — productivity metric
 *       ({@code session-annotator} → {@code t_session_annotation}, etc.)</li>
 * </ul>
 * Aggregation lives in {@link SystemAgentMonitorService} so the controller
 * stays thin and the cross-table joins are unit-testable without spinning
 * Spring context.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SystemAgentMonitorController /api/system-agents/monitor")
class SystemAgentMonitorControllerTest {

    @Mock
    private SystemAgentMonitorService monitorService;

    private SystemAgentMonitorController controller;

    @BeforeEach
    void setUp() {
        controller = new SystemAgentMonitorController(monitorService);
    }

    @Test
    @DisplayName("GET /api/system-agents/monitor returns one record per system agent")
    void monitor_returnsOneRecordPerSystemAgent() {
        List<SystemAgentMonitorResponse> expected = List.of(
                row(6L, "memory-curator", "consolidations"),
                row(7L, "session-annotator", "annotations"),
                row(8L, "metrics-collector", "metrics"),
                row(9L, "attribution-curator", "proposals"),
                row(10L, "user-simulator", "trials")
        );
        when(monitorService.monitorAll()).thenReturn(expected);

        ResponseEntity<List<SystemAgentMonitorResponse>> resp = controller.monitorAll();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody())
                .as("the 5 known V69/V75/V79/V81/V85 system agents")
                .extracting(SystemAgentMonitorResponse::name)
                .containsExactly(
                        "memory-curator",
                        "session-annotator",
                        "metrics-collector",
                        "attribution-curator",
                        "user-simulator");
        verify(monitorService).monitorAll();
    }

    @Test
    @DisplayName("each monitor record exposes cron / lastRun / 7d counts / outputEntityType")
    void monitor_recordCarriesFullAggregateShape() {
        SystemAgentMonitorResponse row = new SystemAgentMonitorResponse(
                7L,
                "session-annotator",
                "Hourly outcome annotator for production sessions",
                "0 30 * * * *",
                Instant.parse("2026-05-17T01:30:00Z"),
                "success",
                168L,
                29L,
                "annotations");
        when(monitorService.monitorAll()).thenReturn(List.of(row));

        ResponseEntity<List<SystemAgentMonitorResponse>> resp = controller.monitorAll();

        assertThat(resp.getBody()).hasSize(1);
        SystemAgentMonitorResponse got = resp.getBody().get(0);
        assertThat(got.agentId()).isEqualTo(7L);
        assertThat(got.cronExpression()).isEqualTo("0 30 * * * *");
        assertThat(got.lastRunStatus()).isEqualTo("success");
        assertThat(got.sevenDayTriggerCount()).isEqualTo(168L);
        assertThat(got.sevenDayOutputCount()).isEqualTo(29L);
        assertThat(got.outputEntityType())
                .as("session-annotator → annotations (PRD F4 mapping)")
                .isEqualTo("annotations");
    }

    @Test
    @DisplayName("system agent without an active scheduled task surfaces null cron / lastRun")
    void monitor_agentWithoutSchedule_emitsNulls() {
        // metrics-collector V87 disabled scheduled task → cron / lastRunAt are null
        SystemAgentMonitorResponse row = new SystemAgentMonitorResponse(
                8L,
                "metrics-collector",
                "V77 canary metrics aggregator (V87 disabled)",
                null,
                null,
                null,
                0L,
                0L,
                "metrics");
        when(monitorService.monitorAll()).thenReturn(List.of(row));

        ResponseEntity<List<SystemAgentMonitorResponse>> resp = controller.monitorAll();

        SystemAgentMonitorResponse got = resp.getBody().get(0);
        assertThat(got.cronExpression()).isNull();
        assertThat(got.lastRunAt()).isNull();
        assertThat(got.lastRunStatus()).isNull();
        assertThat(got.sevenDayTriggerCount()).isZero();
        assertThat(got.sevenDayOutputCount()).isZero();
    }

    private static SystemAgentMonitorResponse row(long id, String name, String entityType) {
        return new SystemAgentMonitorResponse(
                id,
                name,
                name + " description",
                "0 0 * * * *",
                Instant.parse("2026-05-17T00:00:00Z"),
                "success",
                10L,
                5L,
                entityType);
    }

    /** Trivial use-the-import keeper so AgentEntity import survives `mvn unused-import`. */
    @SuppressWarnings("unused")
    private static AgentEntity dummy() {
        return new AgentEntity();
    }
}
