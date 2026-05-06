package com.skillforge.server.migration;

import com.skillforge.server.AbstractPostgresIT;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.junit.jupiter.EnabledIf;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EVAL-V2 M3a (b2): post-condition checks for V52 / V53 / V54.
 *
 * <ol>
 *   <li>V52 — t_eval_run renamed to t_eval_task; pass_count column exists; the
 *       7 new task-shape columns are present with the documented defaults; the
 *       backward-compat VIEW {@code t_eval_run} is selectable and exposes
 *       {@code passed_scenarios} (alias of {@code pass_count}).</li>
 *   <li>V53 — t_eval_task_item table + indexes + FK CASCADE present.</li>
 *   <li>V54 — DO BLOCK semantics: for any task we INSERT after migration,
 *       running the same migration logic against a freshly inserted jsonb
 *       payload populates t_eval_task_item with the expected mapping. Note:
 *       V54 itself ran during Flyway startup against whatever pre-existing
 *       data the test container happened to have (likely none); this test
 *       re-exercises the same SQL pattern to lock the field-mapping
 *       contract.</li>
 * </ol>
 */
@DisplayName("V52/V53/V54 eval task migrations")
@EnabledIf(expression = "#{systemProperties['skillforge.runMigrationIT'] == 'true'}",
        reason = "Run migration ITs only when explicitly requested")
class EvalTaskMigrationIT extends AbstractPostgresIT {

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void cleanupTaskRows() {
        entityManager.createNativeQuery("DELETE FROM t_eval_task_item").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM t_eval_task").executeUpdate();
    }

    // -----------------------------------------------------------------------
    // V52
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("V52 — rename + 7 new fields + VIEW")
    class V52Schema {

        @Test
        @DisplayName("t_eval_task table exists with renamed pass_count column")
        void taskTableShape() {
            Map<String, Map<String, Object>> cols = describeColumns("t_eval_task");

            assertThat(cols).containsKey("pass_count");
            // Legacy column should NOT exist on the actual table after rename.
            assertThat(cols).doesNotContainKey("passed_scenarios");
            assertThat(cols.get("pass_count").get("data_type")).isEqualTo("integer");
        }

        @Test
        @DisplayName("7 new task-shape columns exist")
        void newColumnsExist() {
            Map<String, Map<String, Object>> cols = describeColumns("t_eval_task");
            assertThat(cols).containsKeys(
                    "attribution_summary",
                    "improvement_suggestion",
                    "analysis_session_id",
                    "dataset_filter",
                    "scenario_count",
                    "fail_count",
                    "composite_avg");

            // fail_count must be NOT NULL DEFAULT 0 (matches V52 spec).
            Map<String, Object> failCount = cols.get("fail_count");
            assertThat(failCount.get("is_nullable")).isEqualTo("NO");
            assertThat(((String) failCount.get("column_default"))).contains("0");

            // composite_avg is numeric(5,2).
            Map<String, Object> composite = cols.get("composite_avg");
            assertThat(composite.get("data_type")).isEqualTo("numeric");
        }

        @Test
        @DisplayName("backward-compat VIEW t_eval_run is selectable + aliases pass_count")
        void viewSelectsLegacyName() {
            // VIEW exists & SELECT 0 rows should succeed (we may have no rows).
            Object count = entityManager.createNativeQuery(
                    "SELECT COUNT(*) FROM t_eval_run").getSingleResult();
            assertThat(count).isNotNull();

            // information_schema.views confirms the VIEW exists.
            @SuppressWarnings("unchecked")
            List<String> viewNames = entityManager.createNativeQuery(
                    "SELECT table_name FROM information_schema.views "
                  + "WHERE table_name = 't_eval_run'").getResultList();
            assertThat(viewNames).contains("t_eval_run");

            // The VIEW exposes 'passed_scenarios' (alias of pass_count).
            Map<String, Map<String, Object>> viewCols = describeColumns("t_eval_run");
            assertThat(viewCols).containsKey("passed_scenarios");
        }

        @Test
        @DisplayName("rerunning V52 on an already-migrated schema stays safe")
        void migrationCanRerun() throws Exception {
            try (Connection connection = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(connection,
                        new ClassPathResource("db/migration/V52__rename_eval_run_to_task.sql"));
            }

            Object count = entityManager.createNativeQuery("SELECT COUNT(*) FROM t_eval_run").getSingleResult();
            assertThat(count).isNotNull();
        }
    }

    // -----------------------------------------------------------------------
    // V53
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("V53 — t_eval_task_item table + indexes + FK")
    class V53Schema {

        @Test
        @DisplayName("t_eval_task_item exists with all expected columns")
        void itemTableShape() {
            Map<String, Map<String, Object>> cols = describeColumns("t_eval_task_item");
            assertThat(cols).containsKeys(
                    "id", "task_id", "scenario_id", "scenario_source", "session_id",
                    "root_trace_id", "composite_score", "status", "loop_count",
                    "tool_call_count", "latency_ms", "attribution",
                    "judge_rationale", "agent_final_output",
                    "started_at", "completed_at", "created_at");

            assertThat(cols.get("task_id").get("is_nullable")).isEqualTo("NO");
            assertThat(cols.get("scenario_id").get("is_nullable")).isEqualTo("NO");
            assertThat(cols.get("status").get("is_nullable")).isEqualTo("NO");
            assertThat(cols.get("created_at").get("is_nullable")).isEqualTo("NO");
        }

        @Test
        @DisplayName("expected indexes are present")
        void indexesPresent() {
            @SuppressWarnings("unchecked")
            List<Object[]> idx = entityManager.createNativeQuery(
                    "SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 't_eval_task_item'")
                    .getResultList();

            Map<String, String> byName = idx.stream().collect(Collectors.toMap(
                    row -> (String) row[0],
                    row -> (String) row[1]
            ));
            assertThat(byName).containsKeys(
                    "idx_task_item_task",
                    "idx_task_item_scenario",
                    "idx_task_item_session");
            assertThat(byName.get("idx_task_item_session"))
                    .contains("WHERE (session_id IS NOT NULL)");
        }

        @Test
        @DisplayName("FK fk_item_task on (task_id) → t_eval_task(id) ON DELETE CASCADE")
        void fkExists() {
            @SuppressWarnings("unchecked")
            List<Object[]> fks = entityManager.createNativeQuery("""
                    SELECT c.conname, c.confdeltype
                      FROM pg_constraint c
                      JOIN pg_class cl ON cl.oid = c.conrelid
                     WHERE cl.relname = 't_eval_task_item'
                       AND c.contype = 'f'
                    """).getResultList();
            assertThat(fks).isNotEmpty();
            // confdeltype = 'c' → CASCADE
            boolean hasCascade = fks.stream().anyMatch(row -> "c".equals(String.valueOf(row[1])));
            assertThat(hasCascade)
                    .as("expected at least one FK on t_eval_task_item with ON DELETE CASCADE")
                    .isTrue();
        }
    }

    // -----------------------------------------------------------------------
    // V54
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("V54 — jsonb → row data migration semantics")
    class V54DataMigration {

        @Test
        @Transactional
        @DisplayName("INSERT a COMPLETED task with jsonb scenarioResults; running V54 SQL maps fields correctly")
        void v54Mapping() {
            // 1. Insert a COMPLETED task with a known scenario_results_json blob.
            String taskId = java.util.UUID.randomUUID().toString();
            String json = """
                    [
                      {
                        "scenarioId": "scenario-one",
                        "compositeScore": 88.5,
                        "status": "PASS",
                        "loopCount": 2,
                        "executionTimeMs": 1500,
                        "attribution": "NONE",
                        "judgeRationale": "ok",
                        "agentFinalOutput": "done"
                      },
                      {
                        "scenarioId": "scenario-two",
                        "compositeScore": 30.0,
                        "status": "FAIL",
                        "loopCount": 5,
                        "executionTimeMs": 12000,
                        "attribution": "PROMPT_QUALITY",
                        "judgeRationale": "missed format",
                        "agentFinalOutput": "wrong"
                      }
                    ]
                    """;
            entityManager.createNativeQuery("""
                    INSERT INTO t_eval_task
                        (id, agent_definition_id, status, scenario_results_json,
                         total_scenarios, pass_count)
                    VALUES (:id, :agent, 'COMPLETED', :json, 2, 1)
                    """)
                    .setParameter("id", taskId)
                    .setParameter("agent", "999")
                    .setParameter("json", json)
                    .executeUpdate();

            rerunMigration("db/migration/V54__migrate_scenario_results_to_items.sql");
            entityManager.flush();
            entityManager.clear();

            // 3. Verify two rows landed with the documented mapping.
            @SuppressWarnings("unchecked")
            List<Object[]> rows = entityManager.createNativeQuery("""
                    SELECT scenario_id, composite_score, status, loop_count,
                           latency_ms, attribution, judge_rationale,
                           agent_final_output
                      FROM t_eval_task_item
                     WHERE task_id = :id
                     ORDER BY scenario_id
                    """)
                    .setParameter("id", taskId)
                    .getResultList();

            assertThat(rows).hasSize(2);

            Map<String, Object[]> byId = rows.stream().collect(Collectors.toMap(
                    r -> (String) r[0], r -> r));

            Object[] one = byId.get("scenario-one");
            assertThat(one[1].toString()).isEqualTo("88.5");
            assertThat(one[2]).isEqualTo("PASS");
            assertThat(((Number) one[3]).intValue()).isEqualTo(2);
            assertThat(((Number) one[4]).longValue()).isEqualTo(1500L);
            assertThat(one[5]).isEqualTo("NONE");
            assertThat(one[6]).isEqualTo("ok");
            assertThat(one[7]).isEqualTo("done");

            Object[] two = byId.get("scenario-two");
            assertThat(two[1].toString()).isEqualTo("30.0");
            assertThat(two[2]).isEqualTo("FAIL");
            assertThat(((Number) two[3]).intValue()).isEqualTo(5);
            assertThat(((Number) two[4]).longValue()).isEqualTo(12000L);
            assertThat(two[5]).isEqualTo("PROMPT_QUALITY");
        }

        @Test
        @Transactional
        @DisplayName("rerunning V54 does not duplicate already migrated task items")
        void v54IsIdempotent() {
            String taskId = java.util.UUID.randomUUID().toString();
            String json = """
                    [
                      { "scenarioId": "scenario-one", "compositeScore": 88.5, "status": "PASS" },
                      { "scenarioId": "scenario-two", "compositeScore": 30.0, "status": "FAIL" }
                    ]
                    """;
            entityManager.createNativeQuery("""
                    INSERT INTO t_eval_task
                        (id, agent_definition_id, status, scenario_results_json,
                         total_scenarios, pass_count)
                    VALUES (:id, :agent, 'COMPLETED', :json, 2, 1)
                    """)
                    .setParameter("id", taskId)
                    .setParameter("agent", "999")
                    .setParameter("json", json)
                    .executeUpdate();

            rerunMigration("db/migration/V54__migrate_scenario_results_to_items.sql");
            rerunMigration("db/migration/V54__migrate_scenario_results_to_items.sql");
            entityManager.flush();
            entityManager.clear();

            Number count = (Number) entityManager.createNativeQuery("""
                    SELECT COUNT(*) FROM t_eval_task_item WHERE task_id = :id
                    """)
                    .setParameter("id", taskId)
                    .getSingleResult();
            assertThat(count.intValue()).isEqualTo(2);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> describeColumns(String tableOrView) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT column_name, data_type, is_nullable, column_default
                  FROM information_schema.columns
                 WHERE table_name = :t
                """)
                .setParameter("t", tableOrView)
                .getResultList();
        return rows.stream().collect(Collectors.toMap(
                r -> (String) r[0],
                r -> Map.of(
                        "data_type", r[1],
                        "is_nullable", r[2],
                        "column_default", r[3] == null ? "" : r[3])));
    }

    private void rerunMigration(String classpathLocation) {
        entityManager.flush();
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(classpathLocation));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        entityManager.clear();
    }
}
