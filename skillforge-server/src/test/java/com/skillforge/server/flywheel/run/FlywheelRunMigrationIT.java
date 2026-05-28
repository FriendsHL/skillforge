package com.skillforge.server.flywheel.run;

import com.skillforge.server.AbstractPostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OPT-LOOP-FRAMEWORK Sprint 1: integration tests for the V124
 * {@code rename_opt_report_to_flywheel_run} migration. Confirms post-migration
 * the new table is queryable, OPT-REPORT-V1 rows get a sensible input_json
 * backfill, the backward-compat views serve OPT-REPORT consumers, and CHECK
 * constraints reject illegal enum values.
 *
 * <p>Runs Flyway end-to-end against the Testcontainers Postgres instance —
 * exercising the actual migration on top of every prior V1..V123 baseline. No
 * stubbing, no in-process DB.
 */
@DisplayName("FlywheelRun migration (V124) integration tests")
class FlywheelRunMigrationIT extends AbstractPostgresIT {

    @Autowired
    private FlywheelRunRepository runRepository;

    @Autowired
    private FlywheelRunStepRepository stepRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Each test starts with a clean slate. We use raw {@code DELETE FROM} on
     * the renamed tables so a left-over Sprint 0 (V97/V107) seed row doesn't
     * pollute the run count assertions. JPA repository {@code deleteAll} would
     * cascade into FK joins we don't care about here.
     */
    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM t_flywheel_run_step");
        jdbcTemplate.update("DELETE FROM t_flywheel_run");
    }

    @Test
    @DisplayName("Case 1: post-migration t_flywheel_run exists + a fresh row sees default trigger_source/loop_kind/input_json")
    void case1_tableExists_freshRowGetsDefaults() {
        // Insert directly with the V97 column shape (no loop_kind / no input_json
        // explicitly passed) — defaults from the V124 ADD COLUMN must take.
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO t_flywheel_run (id, agent_id, window_start, window_end, status, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, 'pending', NOW(), NOW())",
                id, 7L, Instant.parse("2026-05-20T00:00:00Z"), Instant.parse("2026-05-27T00:00:00Z"));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT trigger_source, loop_kind, input_json::text AS input_json FROM t_flywheel_run WHERE id = ?",
                id);
        assertThat(row.get("trigger_source")).isEqualTo("user_manual");
        assertThat(row.get("loop_kind")).isEqualTo("opt_report");
        assertThat(row.get("input_json")).isEqualTo("{}");
    }

    @Test
    @DisplayName("Case 1b: Backfill — an opt_report row inserted before the migration would have input_json populated with agentId/windowDays")
    void case1b_backfill_populatesAgentIdAndWindowDays() {
        // Simulate a "pre-V124 row" by inserting then resetting input_json to
        // '{}' (the V124 default for new rows) and then re-running the backfill
        // UPDATE clause manually. This mirrors what V124 phase-4 ran against
        // real pre-existing rows.
        String id = UUID.randomUUID().toString();
        Instant start = Instant.parse("2026-05-20T00:00:00Z");
        Instant end = Instant.parse("2026-05-27T00:00:00Z");
        jdbcTemplate.update(
                "INSERT INTO t_flywheel_run (id, agent_id, window_start, window_end, status, input_json, loop_kind, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, 'completed', '{}'::jsonb, 'opt_report', NOW(), NOW())",
                id, 42L, start, end);
        // Re-run the V124 backfill clause on this single row.
        jdbcTemplate.update("""
                UPDATE t_flywheel_run
                SET input_json = jsonb_build_object(
                        'agentId',     agent_id,
                        'windowDays',  GREATEST(1, ROUND(EXTRACT(EPOCH FROM (window_end - window_start)) / 86400.0)::int),
                        'windowStart', to_char(window_start AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                        'windowEnd',   to_char(window_end   AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
                    )
                WHERE loop_kind = 'opt_report'
                  AND input_json = '{}'::jsonb
                """);

        String json = jdbcTemplate.queryForObject(
                "SELECT input_json::text FROM t_flywheel_run WHERE id = ?", String.class, id);
        assertThat(json).contains("\"agentId\":42");
        assertThat(json).contains("\"windowDays\":7");
        assertThat(json).contains("\"windowStart\":\"2026-05-20T00:00:00Z\"");
        assertThat(json).contains("\"windowEnd\":\"2026-05-27T00:00:00Z\"");
    }

    @Test
    @DisplayName("Case 2: t_opt_report compat view returns the same rows as SELECT FROM t_flywheel_run WHERE loop_kind='opt_report'")
    void case2_optReportView_matchesUnderlyingTableFilter() {
        // 1 opt_report row + 1 memory_curation row.
        String optReportId = insertRun(7L, "opt_report", "user_manual");
        insertRun(8L, "memory_curation", "cron");

        List<Map<String, Object>> viaView = jdbcTemplate.queryForList(
                "SELECT id FROM t_opt_report ORDER BY created_at");
        List<Map<String, Object>> viaTable = jdbcTemplate.queryForList(
                "SELECT id FROM t_flywheel_run WHERE loop_kind = 'opt_report' ORDER BY created_at");

        assertThat(viaView).hasSize(1);
        assertThat(viaView).hasSameElementsAs(viaTable);
        assertThat(viaView.get(0).get("id")).isEqualTo(optReportId);
    }

    @Test
    @DisplayName("Case 3: t_opt_report_batch compat view maps the renamed step columns back to the V97 names")
    void case3_optReportBatchView_columnRemapping() {
        String runId = insertRun(7L, "opt_report", "user_manual");
        String stepId = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO t_flywheel_run_step (id, run_id, step_input_json, status, step_output_count, step_kind, created_at, updated_at) " +
                        "VALUES (?, ?, '[\"sess-a\",\"sess-b\"]'::jsonb, 'completed', 5, 'subagent_dispatch', NOW(), NOW())",
                stepId, runId);

        // View column shape: id, report_id (was run_id), sub_agent_session_id,
        // session_ids_json (was step_input_json), status, annotations_written_count
        // (was step_output_count), error_reason, created_at, updated_at.
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT report_id, session_ids_json::text AS session_ids_json, annotations_written_count " +
                        "FROM t_opt_report_batch WHERE id = ?",
                stepId);
        assertThat(row.get("report_id")).isEqualTo(runId);
        assertThat(((String) row.get("session_ids_json"))).contains("sess-a").contains("sess-b");
        assertThat(((Number) row.get("annotations_written_count")).intValue()).isEqualTo(5);
    }

    @Test
    @DisplayName("Case 4a: CHECK constraint rejects illegal trigger_source value")
    void case4a_triggerSourceCheck_rejectsBadValue() {
        assertThatThrownBy(() -> insertRun(7L, "opt_report", "telepathy"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Case 4b: CHECK constraint rejects illegal loop_kind value")
    void case4b_loopKindCheck_rejectsBadValue() {
        assertThatThrownBy(() -> insertRun(7L, "wormhole", "user_manual"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Case 4c: CHECK constraint rejects illegal status value")
    void case4c_statusCheck_rejectsBadValue() {
        String id = UUID.randomUUID().toString();
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO t_flywheel_run (id, agent_id, window_start, window_end, status, created_at, updated_at) " +
                        "VALUES (?, ?, NOW() - INTERVAL '7 days', NOW(), 'enlightened', NOW(), NOW())",
                id, 7L))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Case 5: JPA repository can save a fresh FlywheelRunEntity end-to-end")
    void case5_repository_savesAndReads() {
        FlywheelRunEntity run = new FlywheelRunEntity();
        run.setId(UUID.randomUUID().toString());
        run.setAgentId(7L);
        run.setWindowStart(Instant.parse("2026-05-20T00:00:00Z"));
        run.setWindowEnd(Instant.parse("2026-05-27T00:00:00Z"));
        run.setStatus(FlywheelRunEntity.STATUS_PENDING);
        run.setLoopKind(FlywheelRunEntity.LOOP_KIND_MEMORY_CURATION);
        run.setTriggerSource(FlywheelRunEntity.TRIGGER_SOURCE_CRON);
        run.setInputJson("{\"cronExpression\":\"0 0 4 * * *\"}");
        runRepository.save(run);

        FlywheelRunEntity loaded = runRepository.findById(run.getId()).orElseThrow();
        assertThat(loaded.getLoopKind()).isEqualTo(FlywheelRunEntity.LOOP_KIND_MEMORY_CURATION);
        assertThat(loaded.getTriggerSource()).isEqualTo(FlywheelRunEntity.TRIGGER_SOURCE_CRON);
        assertThat(loaded.getInputJson()).contains("cronExpression");
        assertThat(loaded.getStatus()).isEqualTo(FlywheelRunEntity.STATUS_PENDING);
    }

    @Test
    @DisplayName("Case 6: FlywheelRunStepEntity round-trips with renamed columns (run_id / step_input_json / step_output_count / step_kind)")
    void case6_stepRepository_savesAndReads() {
        String runId = insertRun(7L, "opt_report", "user_manual");

        FlywheelRunStepEntity step = new FlywheelRunStepEntity();
        step.setId(UUID.randomUUID().toString());
        step.setRunId(runId);
        step.setStatus(FlywheelRunStepEntity.STATUS_COMPLETED);
        step.setStepInputJson("[\"sess-1\",\"sess-2\"]");
        step.setStepOutputCount(3);
        step.setStepKind(FlywheelRunStepEntity.STEP_KIND_SUBAGENT_DISPATCH);
        stepRepository.save(step);

        List<FlywheelRunStepEntity> byRun = stepRepository.findByRunId(runId);
        assertThat(byRun).hasSize(1);
        assertThat(byRun.get(0).getStepOutputCount()).isEqualTo(3);
        assertThat(byRun.get(0).getStepKind()).isEqualTo("subagent_dispatch");
    }

    @Test
    @DisplayName("Case 7 (r1 DB W1): FK constraints renamed to t_flywheel_run_*_fkey; no stale OPT-REPORT constraint names remain")
    void case7_fkConstraints_renamedToNewTableNames() {
        // pg_constraint contype='f' lists FOREIGN KEY constraints. After
        // V124 phase 1 the FK names must match the new table names.
        List<String> runFkNames = jdbcTemplate.queryForList(
                "SELECT conname FROM pg_constraint " +
                        "WHERE conrelid = 't_flywheel_run'::regclass AND contype = 'f'",
                String.class);
        List<String> stepFkNames = jdbcTemplate.queryForList(
                "SELECT conname FROM pg_constraint " +
                        "WHERE conrelid = 't_flywheel_run_step'::regclass AND contype = 'f'",
                String.class);

        assertThat(runFkNames).contains("t_flywheel_run_agent_id_fkey");
        assertThat(runFkNames).noneMatch(n -> n.startsWith("t_opt_report"));
        assertThat(stepFkNames).contains("t_flywheel_run_step_run_id_fkey");
        assertThat(stepFkNames).noneMatch(n -> n.startsWith("t_opt_report"));
    }

    /**
     * Insert a row via raw SQL (skips JPA's optimistic locking + auditing) so
     * each test can craft exactly the column combo it needs. The
     * pre-V124-emulation rows in case1b reuse this helper to set up the
     * "default" {@code input_json='{}'} that the backfill UPDATE then rewrites.
     */
    private String insertRun(long agentId, String loopKind, String triggerSource) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO t_flywheel_run (id, agent_id, window_start, window_end, status, loop_kind, trigger_source, input_json, created_at, updated_at) " +
                        "VALUES (?, ?, NOW() - INTERVAL '7 days', NOW(), 'pending', ?, ?, '{}'::jsonb, NOW(), NOW())",
                id, agentId, loopKind, triggerSource);
        return id;
    }
}
