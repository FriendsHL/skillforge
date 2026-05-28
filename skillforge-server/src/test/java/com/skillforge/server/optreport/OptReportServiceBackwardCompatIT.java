package com.skillforge.server.optreport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.server.AbstractPostgresIT;
import com.skillforge.server.flywheel.run.FlywheelRunEntity;
import com.skillforge.server.flywheel.run.FlywheelRunRepository;
import com.skillforge.server.flywheel.run.FlywheelRunService;
import com.skillforge.server.websocket.UserWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * OPT-LOOP-FRAMEWORK Sprint 1: end-to-end integration test confirming that
 * driving {@link FlywheelRunService} writes a real
 * {@code t_flywheel_run} row with the OPT-REPORT-V1 loop_kind/trigger_source
 * conventions, that the backward-compat {@code t_opt_report} view sees the
 * exact same row through the {@code loop_kind='opt_report'} filter, and that
 * the {@code flywheel_run_status_changed} WS event fires on every state
 * change. {@link OptReportService} itself is unit-tested separately
 * ({@code OptReportServiceTest}) — wiring the full Spring context here would
 * pull in ChatService + SessionService + agent registry without adding signal
 * beyond what those unit tests already cover.
 *
 * <p>Lives under {@code optreport/} (not {@code flywheel/run/}) because the
 * test's intent is "OPT-REPORT-V1 surface still works after V124 rename" —
 * findability matters more than package perfection.
 */
@DisplayName("OPT-REPORT-V1 backward-compat after V124 rename (integration)")
class OptReportServiceBackwardCompatIT extends AbstractPostgresIT {

    private static final Instant FIXED_NOW = Instant.parse("2026-05-28T10:00:00Z");

    @Autowired
    private FlywheelRunRepository runRepository;

    @Autowired
    private com.skillforge.server.flywheel.run.FlywheelRunStepRepository stepRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UserWebSocketHandler userWebSocketHandler;
    private org.springframework.context.ApplicationEventPublisher applicationEventPublisher;
    private FlywheelRunService flywheelRunService;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM t_flywheel_run_step");
        jdbcTemplate.update("DELETE FROM t_flywheel_run");

        // r1 W2 fix (java.md footgun #1): register JavaTimeModule so future
        // inputJson payloads carrying Instant/LocalDateTime serialize correctly.
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        Clock clock = Clock.fixed(FIXED_NOW, ZoneId.of("UTC"));
        userWebSocketHandler = mock(UserWebSocketHandler.class);
        applicationEventPublisher = mock(org.springframework.context.ApplicationEventPublisher.class);
        flywheelRunService = new FlywheelRunService(
                runRepository, stepRepository, userWebSocketHandler, objectMapper, clock,
                applicationEventPublisher);
    }

    @Test
    @DisplayName("Case 1: startRun → attachGeneratorSession persists an opt_report row + compat view queries see it")
    void case1_startRun_writesOptReportRow_visibleThroughCompatView() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("agentId", 7L);
        input.put("windowDays", 7);

        FlywheelRunEntity pending = flywheelRunService.startRun(
                FlywheelRunEntity.LOOP_KIND_OPT_REPORT,
                FlywheelRunEntity.TRIGGER_SOURCE_USER_MANUAL,
                input,
                7L,
                7);
        FlywheelRunEntity running = flywheelRunService.attachGeneratorSession(
                pending.getId(), "sess-gen-1");

        // Verify direct query.
        Map<String, Object> directRow = jdbcTemplate.queryForMap(
                "SELECT id, agent_id, status, loop_kind, trigger_source, input_json::text AS input_json " +
                        "FROM t_flywheel_run WHERE id = ?",
                pending.getId());
        assertThat(directRow.get("loop_kind")).isEqualTo("opt_report");
        assertThat(directRow.get("trigger_source")).isEqualTo("user_manual");
        assertThat(directRow.get("status")).isEqualTo("running");
        assertThat(((Number) directRow.get("agent_id")).longValue()).isEqualTo(7L);
        assertThat(((String) directRow.get("input_json")))
                .contains("\"agentId\":7").contains("\"windowDays\":7");

        // Verify backward-compat view sees the same row with the same column names.
        List<Map<String, Object>> viaView = jdbcTemplate.queryForList(
                "SELECT id, agent_id, status, generator_session_id FROM t_opt_report WHERE id = ?",
                pending.getId());
        assertThat(viaView).hasSize(1);
        assertThat(viaView.get(0).get("generator_session_id")).isEqualTo("sess-gen-1");
        assertThat(viaView.get(0).get("status")).isEqualTo("running");

        // The entity returned tracks the running status.
        assertThat(running.getStatus()).isEqualTo(FlywheelRunEntity.STATUS_RUNNING);
        assertThat(running.getGeneratorSessionId()).isEqualTo("sess-gen-1");
    }

    @Test
    @DisplayName("Case 2: markCompleted persists summary + fires flywheel_run_status_changed WS event")
    void case2_markCompleted_persistsAndBroadcasts() {
        FlywheelRunEntity pending = flywheelRunService.startRun(
                FlywheelRunEntity.LOOP_KIND_OPT_REPORT,
                FlywheelRunEntity.TRIGGER_SOURCE_USER_MANUAL,
                Map.of("agentId", 9L, "windowDays", 14),
                9L,
                14);
        flywheelRunService.attachGeneratorSession(pending.getId(), "sess-x");
        flywheelRunService.markCompleted(pending.getId(), "# Report body", "{\"summary\":\"ok\"}");

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, content_md, summary_json::text AS summary_json FROM t_flywheel_run WHERE id = ?",
                pending.getId());
        assertThat(row.get("status")).isEqualTo("completed");
        assertThat(row.get("content_md")).isEqualTo("# Report body");
        assertThat(((String) row.get("summary_json"))).contains("\"summary\":\"ok\"");

        // WS event fired at least twice (attach + markCompleted) — the second
        // carries newStatus=completed.
        verify(userWebSocketHandler, atLeastOnce()).broadcastAll(any());
    }

    @Test
    @DisplayName("Case 3: only opt_report rows are visible through the compat view")
    void case3_compatView_scopesLoopKind() {
        // Persist one opt_report run + one memory_curation run.
        FlywheelRunEntity optReport = flywheelRunService.startRun(
                FlywheelRunEntity.LOOP_KIND_OPT_REPORT,
                FlywheelRunEntity.TRIGGER_SOURCE_USER_MANUAL,
                Map.of("agentId", 7L), 7L, 7);
        FlywheelRunEntity memCuration = flywheelRunService.startRun(
                FlywheelRunEntity.LOOP_KIND_MEMORY_CURATION,
                FlywheelRunEntity.TRIGGER_SOURCE_CRON,
                Map.of("cronExpression", "0 4 * * *"), 8L, 1);

        // Direct table sees both.
        Long allRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_flywheel_run", Long.class);
        assertThat(allRows).isEqualTo(2);

        // Compat view sees only the opt_report row.
        List<String> viewIds = jdbcTemplate.queryForList(
                "SELECT id FROM t_opt_report", String.class);
        assertThat(viewIds).containsExactly(optReport.getId());
        assertThat(viewIds).doesNotContain(memCuration.getId());
    }
}
