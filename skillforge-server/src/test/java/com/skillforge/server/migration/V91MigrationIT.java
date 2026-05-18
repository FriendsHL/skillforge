package com.skillforge.server.migration;

import com.skillforge.server.AbstractPostgresIT;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.junit.jupiter.EnabledIf;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SKILL-CREATOR-WITH-EVAL Phase 1.0 red test:
 * post-condition checks for V91 — {@code t_skill_draft} gains 4 columns +
 * status / source enum widening (per tech-design.md F3 + 数据模型 section).
 *
 * <p>This test is the structural anchor for V91 (added in Phase 1.1). Before
 * Phase 1.1 lands the migration the test <b>fails red</b> because:
 * <ul>
 *   <li>{@code t_skill_draft.target_agent_id} column does not exist</li>
 *   <li>{@code t_skill_draft.candidate_skill_id} column does not exist</li>
 *   <li>{@code t_skill_draft.source} column does not exist</li>
 *   <li>{@code t_skill_draft.evaluation_result_json} column does not exist</li>
 * </ul>
 *
 * <p>After Phase 1.1 lands {@code V91__skill_draft_evaluation.sql} the test
 * passes:
 * <ol>
 *   <li>4 new columns exist, all NULLABLE per tech-design.md</li>
 *   <li>{@code evaluation_result_json} is TEXT (Postgres {@code text} type)</li>
 *   <li>{@code source} is VARCHAR(64)</li>
 *   <li>existing rows (created before V91) carry NULL for all 4 new columns</li>
 * </ol>
 *
 * <p>Gated by {@code -Dskillforge.runMigrationIT=true} per the existing
 * {@link AgentTypeMigrationIT} convention so the migration ITs only spin up
 * the Postgres container when explicitly requested.
 */
@DisplayName("V91 — t_skill_draft evaluation migration")
@EnabledIf(expression = "#{systemProperties['skillforge.runMigrationIT'] == 'true'}",
        reason = "Run migration ITs only when explicitly requested")
class V91MigrationIT extends AbstractPostgresIT {

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @DisplayName("t_skill_draft.target_agent_id column exists as BIGINT NULL")
    void targetAgentIdColumnExists() {
        Map<String, Map<String, Object>> cols = describeColumns("t_skill_draft");

        assertThat(cols)
                .as("t_skill_draft.target_agent_id must exist after V91 migration")
                .containsKey("target_agent_id");

        Map<String, Object> col = cols.get("target_agent_id");
        assertThat(col.get("data_type"))
                .as("target_agent_id data_type must be bigint")
                .isEqualTo("bigint");
        assertThat(col.get("is_nullable"))
                .as("target_agent_id must be NULL (optional — extract path back-fills, " +
                        "upload/marketplace path can leave null until operator assigns)")
                .isEqualTo("YES");
    }

    @Test
    @DisplayName("t_skill_draft.candidate_skill_id column exists as BIGINT NULL")
    void candidateSkillIdColumnExists() {
        Map<String, Map<String, Object>> cols = describeColumns("t_skill_draft");

        assertThat(cols)
                .as("t_skill_draft.candidate_skill_id must exist after V91 migration")
                .containsKey("candidate_skill_id");

        Map<String, Object> col = cols.get("candidate_skill_id");
        assertThat(col.get("data_type"))
                .as("candidate_skill_id data_type must be bigint")
                .isEqualTo("bigint");
        assertThat(col.get("is_nullable"))
                .as("candidate_skill_id must be NULL (populated by dispatchEvaluation " +
                        "after render transient SkillEntity)")
                .isEqualTo("YES");
    }

    @Test
    @DisplayName("t_skill_draft.source column exists as VARCHAR(64) NULL")
    void sourceColumnExists() {
        Map<String, Map<String, Object>> cols = describeColumns("t_skill_draft");

        assertThat(cols)
                .as("t_skill_draft.source must exist after V91 migration")
                .containsKey("source");

        Map<String, Object> col = cols.get("source");
        assertThat(col.get("data_type"))
                .as("source data_type must be character varying")
                .isEqualTo("character varying");
        assertThat(col.get("character_maximum_length"))
                .as("source character_maximum_length must be 64")
                .satisfies(v -> assertThat(((Number) v).intValue()).isEqualTo(64));
        assertThat(col.get("is_nullable"))
                .as("source must be NULL — existing rows pre-V91 carry no source value, " +
                        "Phase 1.1 will set on the 4 entry points (upload / marketplace / " +
                        "natural-language / extract-from-sessions)")
                .isEqualTo("YES");
    }

    @Test
    @DisplayName("t_skill_draft.evaluation_result_json column exists as TEXT NULL")
    void evaluationResultJsonColumnExists() {
        Map<String, Map<String, Object>> cols = describeColumns("t_skill_draft");

        assertThat(cols)
                .as("t_skill_draft.evaluation_result_json must exist after V91 migration")
                .containsKey("evaluation_result_json");

        Map<String, Object> col = cols.get("evaluation_result_json");
        assertThat(col.get("data_type"))
                .as("evaluation_result_json data_type must be text (Postgres)")
                .isEqualTo("text");
        assertThat(col.get("is_nullable"))
                .as("evaluation_result_json must be NULL — only populated after " +
                        "SkillCreatorEvalCoordinator aggregates judge results")
                .isEqualTo("YES");
    }

    @Test
    @DisplayName("Pre-existing t_skill_draft rows carry NULL on all 4 new columns")
    void preExistingRowsHaveNullForNewColumns() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT id, target_agent_id, candidate_skill_id, source, evaluation_result_json
                  FROM t_skill_draft
                 LIMIT 50
                """).getResultList();

        // Not every test profile creates t_skill_draft rows. If empty, the
        // structural existence assertions above still cover the migration; this
        // case asserts only that the migration didn't accidentally back-fill
        // any non-null value into existing rows (which would surprise dashboards
        // and Phase 1.1 listener filters).
        for (Object[] row : rows) {
            String id = (String) row[0];
            assertThat(row[1]).as("row id=%s target_agent_id should be NULL pre-Phase 1.1 write", id).isNull();
            assertThat(row[2]).as("row id=%s candidate_skill_id should be NULL pre-Phase 1.1 write", id).isNull();
            assertThat(row[3]).as("row id=%s source should be NULL pre-Phase 1.1 write", id).isNull();
            assertThat(row[4]).as("row id=%s evaluation_result_json should be NULL pre-Phase 1.1 write", id).isNull();
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // helpers (mirrors AgentTypeMigrationIT.describeColumns)
    // ────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> describeColumns(String table) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT column_name, data_type, is_nullable, column_default,
                               character_maximum_length
                          FROM information_schema.columns
                         WHERE table_name = :t
                        """)
                .setParameter("t", table)
                .getResultList();
        return rows.stream().collect(Collectors.toMap(
                r -> (String) r[0],
                r -> {
                    java.util.Map<String, Object> m = new java.util.HashMap<>();
                    m.put("data_type", r[1]);
                    m.put("is_nullable", r[2]);
                    m.put("column_default", r[3] == null ? "" : r[3]);
                    m.put("character_maximum_length", r[4]);
                    return m;
                }));
    }
}
