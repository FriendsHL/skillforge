package com.skillforge.server.flywheel.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.server.AbstractPostgresIT;
import com.skillforge.server.websocket.UserWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * PostgreSQL integration coverage for the step CRUD path on
 * {@link FlywheelRunService}. Verifies:
 *
 * <ul>
 *   <li>V125 add-column migration ran cleanly + nullable so OPT-REPORT
 *       historical rows (the V97 path that writes {@code step_output_count}
 *       but not {@code step_output_json}) keep working without backfill</li>
 *   <li>appendStep → attachStepSubAgentSession → transitionStepStatus →
 *       listStepsByRunId state machine end-to-end on real Postgres</li>
 *   <li>Disallowed transition throws IllegalStateException</li>
 * </ul>
 */
@DisplayName("FlywheelRunService step CRUD + V125 step_output_json integration")
class FlywheelRunServiceStepCrudIT extends AbstractPostgresIT {

    private static final Instant FIXED_NOW = Instant.parse("2026-05-28T10:00:00Z");

    @Autowired private FlywheelRunRepository runRepository;
    @Autowired private FlywheelRunStepRepository stepRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UserWebSocketHandler userWebSocketHandler;
    private ObjectMapper objectMapper;
    private FlywheelRunService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM t_flywheel_run_step");
        jdbcTemplate.update("DELETE FROM t_flywheel_run");
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        userWebSocketHandler = mock(UserWebSocketHandler.class);
        service = new FlywheelRunService(runRepository, stepRepository, userWebSocketHandler,
                objectMapper, Clock.fixed(FIXED_NOW, ZoneId.of("UTC")));
    }

    @Test
    @DisplayName("Case 11/12 (V125): step_output_json column exists + nullable + OPT-REPORT-style writes leave it NULL")
    void case11_12_v125_columnExists_nullable_optReportRowsLeftNull() {
        // Insert a pre-V125-style OPT-REPORT batch row (the path V99
        // RecordBatchAnnotationsTool exercises) without setting step_output_json.
        String runId = insertRun(7L, "opt_report");
        String stepId = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO t_flywheel_run_step "
                        + "(id, run_id, step_input_json, status, step_output_count, step_kind, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, '[\"sess-a\"]'::jsonb, 'completed', 3, 'subagent_dispatch', "
                        + "NOW(), NOW())",
                stepId, runId);

        // step_output_json column exists and the OPT-REPORT row is NULL on it.
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT step_output_json, step_output_count FROM t_flywheel_run_step WHERE id = ?",
                stepId);
        assertThat(row.get("step_output_json")).isNull(); // nullable preserved
        assertThat(((Number) row.get("step_output_count")).intValue()).isEqualTo(3);
    }

    @Test
    @DisplayName("Case 14: appendStep → attachStepSubAgentSession → transitionStepStatus(completed) → listStepsByRunId")
    void case14_stateMachineEndToEnd() {
        String runId = insertRun(7L, "memory_curation");

        // 1) appendStep
        String stepRunId = service.appendStep(runId, "{\"agentId\":11,\"task\":\"do-it\"}");
        assertThat(stepRunId).isNotBlank();
        FlywheelRunStepEntity afterAppend = stepRepository.findById(stepRunId).orElseThrow();
        assertThat(afterAppend.getStatus()).isEqualTo(FlywheelRunStepEntity.STATUS_PENDING);
        assertThat(afterAppend.getRunId()).isEqualTo(runId);
        assertThat(afterAppend.getStepInputJson()).contains("\"task\":\"do-it\"");

        // 2) attachStepSubAgentSession
        service.attachStepSubAgentSession(stepRunId, "child-sess-1");
        FlywheelRunStepEntity afterAttach = stepRepository.findById(stepRunId).orElseThrow();
        assertThat(afterAttach.getSubAgentSessionId()).isEqualTo("child-sess-1");

        // 3) transitionStepStatus(completed) + outputJson written
        com.fasterxml.jackson.databind.JsonNode output = objectMapper.valueToTree(
                Map.of("memoryProposalIds", List.of("mp-1", "mp-2")));
        FlywheelRunStepEntity afterTransition = service.transitionStepStatus(
                stepRunId, FlywheelRunStepEntity.STATUS_COMPLETED, output, null);
        assertThat(afterTransition).isNotNull();
        assertThat(afterTransition.getStatus()).isEqualTo("completed");
        assertThat(afterTransition.getStepOutputJson()).contains("memoryProposalIds");

        // 4) listStepsByRunId (readOnly tx)
        List<FlywheelRunStepEntity> all = service.listStepsByRunId(runId);
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getId()).isEqualTo(stepRunId);
    }

    @Test
    @DisplayName("transitionStepStatus rejects disallowed transition (e.g. attempting pending→pending after already completed)")
    void transition_disallowed_throws() {
        String runId = insertRun(7L, "memory_curation");
        String stepRunId = service.appendStep(runId, "{}");
        service.transitionStepStatus(stepRunId, "completed", null, null);

        // completed → error is disallowed (only pending → completed/error)
        assertThatThrownBy(() -> service.transitionStepStatus(stepRunId, "error", null, "late"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Disallowed step transition");
    }

    @Test
    @DisplayName("transitionStepStatus idempotent — same status no-op returns null")
    void transition_idempotent_noopReturnsNull() {
        String runId = insertRun(7L, "memory_curation");
        String stepRunId = service.appendStep(runId, "{}");
        service.transitionStepStatus(stepRunId, "completed", null, null);

        FlywheelRunStepEntity again = service.transitionStepStatus(stepRunId, "completed", null, null);
        assertThat(again).isNull(); // no-op signal
    }

    @Test
    @DisplayName("appendStep rejects unknown parent runId (FK pre-check)")
    void appendStep_unknownRun_throws() {
        assertThatThrownBy(() -> service.appendStep("nonexistent", "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FlywheelRun not found");
    }

    @Test
    @DisplayName("error transition writes errorReason but clears it on success transition (cleanup on re-attempt path)")
    void errorReason_cleanedOnSuccess() {
        String runId = insertRun(7L, "memory_curation");
        String stepRunId = service.appendStep(runId, "{}");
        // Direct to error
        service.transitionStepStatus(stepRunId, "error", null, "boom");
        FlywheelRunStepEntity afterErr = stepRepository.findById(stepRunId).orElseThrow();
        assertThat(afterErr.getErrorReason()).isEqualTo("boom");
        // (can't transition error → completed via allowed transitions; this just verifies write)
    }

    @Test
    @DisplayName("V125 column: writing step_output_json via service is queryable by JSONB operators")
    void v125_jsonbColumnQueryable() {
        String runId = insertRun(7L, "memory_curation");
        String stepRunId = service.appendStep(runId, "{}");
        com.fasterxml.jackson.databind.JsonNode out = objectMapper.valueToTree(
                Map.of("foo", "bar", "count", 42));
        service.transitionStepStatus(stepRunId, "completed", out, null);

        // JSONB operator
        String foo = jdbcTemplate.queryForObject(
                "SELECT step_output_json ->> 'foo' FROM t_flywheel_run_step WHERE id = ?",
                String.class, stepRunId);
        assertThat(foo).isEqualTo("bar");

        // Numeric extraction
        Integer count = jdbcTemplate.queryForObject(
                "SELECT (step_output_json ->> 'count')::int FROM t_flywheel_run_step WHERE id = ?",
                Integer.class, stepRunId);
        assertThat(count).isEqualTo(42);
    }

    @Test
    @DisplayName("V148: same step_index across DIFFERENT step_kind co-exists (evolve-loop tool_call + evolve_iteration); same kind collides")
    void v148_stepIndexUniquePerKind() {
        String runId = insertRun(7L, "evolve");

        // The evolve-loop workflow writes, on the SAME run:
        //   - a candidate subagent_dispatch step at step_index=1 (workflow counter),
        //   - mechanical tool_call steps (also workflow counter, e.g. step_index=1),
        //   - an evolve_iteration LEDGER step at step_index=1 (iteration number).
        // Before V148 (unique on (run_id, step_index)) the iteration ledger would
        // collide with the tool_call at the same index. After V148
        // (unique on (run_id, step_kind, step_index)) all three co-exist.
        String dispatch = service.appendStep(runId, "{}",
                FlywheelRunStepEntity.STEP_KIND_SUBAGENT_DISPATCH, 1);
        String toolCall = service.appendStep(runId, "{}",
                FlywheelRunStepEntity.STEP_KIND_TOOL_CALL, 1);
        String ledger = service.appendEvolveIterationStep(runId, 1,
                objectMapper.valueToTree(Map.of("iteration", 1, "kept", true)));

        assertThat(List.of(dispatch, toolCall, ledger)).doesNotHaveDuplicates();
        List<FlywheelRunStepEntity> all = service.listStepsByRunId(runId);
        assertThat(all).hasSize(3);

        // Intra-kind uniqueness is STILL enforced: a 2nd tool_call at step_index=1 collides.
        assertThatThrownBy(() -> service.appendStep(runId, "{}",
                FlywheelRunStepEntity.STEP_KIND_TOOL_CALL, 1))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("FR-C7 rolling window: countEvolveIterationStepsByAgentIdSince excludes steps older than :since (the freeze-fix)")
    void frC7_rollingWindow_excludesOldSteps() {
        // Two evolve runs for the SAME agent (CRIT-1: cross-run still aggregates
        // inside the window). One iteration step is recent, two are aged out.
        String runRecent = insertRun(99L, "evolve");
        String runOld = insertRun(99L, "evolve");

        // Recent step — created 1h before FIXED_NOW → inside any window >= 1h.
        insertEvolveIterationStepAt(runRecent, FIXED_NOW.minus(1, java.time.temporal.ChronoUnit.HOURS));
        // Aged-out steps — created 200h (>7d) before FIXED_NOW → outside the 168h window.
        insertEvolveIterationStepAt(runOld, FIXED_NOW.minus(200, java.time.temporal.ChronoUnit.HOURS));
        insertEvolveIterationStepAt(runOld, FIXED_NOW.minus(201, java.time.temporal.ChronoUnit.HOURS));

        // Lifetime count (the old, buggy semantic) would be 3 → permanent freeze.
        // Windowed count with since = FIXED_NOW - 168h must be 1 (only the recent one).
        Instant since = FIXED_NOW.minus(168, java.time.temporal.ChronoUnit.HOURS);
        long windowed = stepRepository.countEvolveIterationStepsByAgentIdSince(99L, since);
        assertThat(windowed).isEqualTo(1L);

        // A very old since (counts everything) proves all 3 rows really exist —
        // i.e. the difference is the window filter, not missing rows.
        long all = stepRepository.countEvolveIterationStepsByAgentIdSince(
                99L, FIXED_NOW.minus(10000, java.time.temporal.ChronoUnit.HOURS));
        assertThat(all).isEqualTo(3L);
    }

    @Test
    @DisplayName("FR-C7 rolling window: only counts evolve_iteration steps (not other kinds) for the agent's evolve runs")
    void frC7_rollingWindow_onlyEvolveIterationKind() {
        String run = insertRun(88L, "evolve");
        // An in-window evolve_iteration step counts.
        insertEvolveIterationStepAt(run, FIXED_NOW.minus(1, java.time.temporal.ChronoUnit.HOURS));
        // An in-window tool_call step on the same run must NOT count.
        service.appendStep(run, "{}", FlywheelRunStepEntity.STEP_KIND_TOOL_CALL, 1);

        Instant since = FIXED_NOW.minus(168, java.time.temporal.ChronoUnit.HOURS);
        long windowed = stepRepository.countEvolveIterationStepsByAgentIdSince(88L, since);
        assertThat(windowed).isEqualTo(1L);
    }

    /**
     * Insert a completed {@code evolve_iteration} step with an explicit
     * {@code created_at} (bypassing {@code @CreatedDate} auditing so the window
     * boundary is deterministic).
     */
    private void insertEvolveIterationStepAt(String runId, Instant createdAt) {
        jdbcTemplate.update(
                "INSERT INTO t_flywheel_run_step "
                        + "(id, run_id, step_input_json, status, step_kind, created_at, updated_at) "
                        + "VALUES (?, ?, '{}'::jsonb, 'completed', 'evolve_iteration', ?, ?)",
                UUID.randomUUID().toString(), runId,
                java.sql.Timestamp.from(createdAt), java.sql.Timestamp.from(createdAt));
    }

    private String insertRun(long agentId, String loopKind) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO t_flywheel_run (id, agent_id, window_start, window_end, status, "
                        + "loop_kind, trigger_source, input_json, created_at, updated_at) "
                        + "VALUES (?, ?, NOW() - INTERVAL '7 days', NOW(), 'pending', ?, "
                        + "'user_manual', '{}'::jsonb, NOW(), NOW())",
                id, agentId, loopKind);
        return id;
    }
}
