package com.skillforge.server.migration;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class V195GoalBriefMetadataContractTest {

    private static final String V194_DEFAULT_MD5 = "4fa17261ac785f220accac65f88081e5";

    @Test
    void migrationAddsStrictGoalBriefMetadataContractToExactV194Default() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.builder().start()) {
            DataSource dataSource = postgres.getPostgresDatabase();
            migrateTo(dataSource, "194");
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            assertThat(promptMd5(jdbc)).isEqualTo(V194_DEFAULT_MD5);

            migrateTo(dataSource, "195");

            assertThat(mainPrompt(jdbc))
                    .contains("proposalStatus 只能是小写 proposed 或 revised")
                    .contains("metadata 必须且只能包含以下 9 个顶层键")
                    .contains("fieldSources 必须且只能包含 outcome、representativeExample、antiGoals、askBefore")
                    .contains("不要加入 capabilityGapAnalysis")
                    .contains("TaskCreate 返回的 Task 必须是 completed");

            String migratedPrompt = mainPrompt(jdbc);
            assertThat(countOccurrences(migratedPrompt, "## Goal Brief metadata 严格契约")).isOne();
            jdbc.update("DELETE FROM flyway_schema_history WHERE version='195'");
            migrateTo(dataSource, "195");
            assertThat(mainPrompt(jdbc)).isEqualTo(migratedPrompt);
        }
    }

    @Test
    void migrationPreservesCustomizedPrompt() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.builder().start()) {
            DataSource dataSource = postgres.getPostgresDatabase();
            migrateTo(dataSource, "194");
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            String customized = mainPrompt(jdbc) + " ";
            jdbc.update("UPDATE t_agent SET system_prompt=? WHERE id=3", customized);

            migrateTo(dataSource, "195");

            assertThat(mainPrompt(jdbc)).isEqualTo(customized);
        }
    }

    private static void migrateTo(DataSource dataSource, String version) {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .placeholders(Map.of("etl_mode", "off", "etl_trace_span_mode", "off"))
                .target(MigrationVersion.fromVersion(version)).load().migrate();
    }

    private static String mainPrompt(JdbcTemplate jdbc) {
        return jdbc.queryForObject(
                "SELECT system_prompt FROM t_agent WHERE id=3 AND name='Main Assistant'", String.class);
    }

    private static String promptMd5(JdbcTemplate jdbc) {
        return jdbc.queryForObject(
                "SELECT md5(system_prompt) FROM t_agent WHERE id=3 AND name='Main Assistant'", String.class);
    }

    private static int countOccurrences(String value, String needle) {
        return (value.length() - value.replace(needle, "").length()) / needle.length();
    }
}
