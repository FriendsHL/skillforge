package com.skillforge.server.migration;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class V194GoalBriefWaitPromptTest {

    private static final String V193_DEFAULT_MD5 = "a81f9aa7ceec2409b9f3bbf619bca6c2";
    private static final String HEADING = "## Goal Brief 行为";

    @Test
    void migration_strengthensDefaultPromptToCreateBriefAndWaitBeforeCapabilityMutation() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.builder().start()) {
            DataSource dataSource = postgres.getPostgresDatabase();
            migrateTo193(dataSource);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            assertThat(promptMd5(jdbc)).isEqualTo(V193_DEFAULT_MD5);

            migrateTo194(dataSource);

            assertThat(mainPrompt(jdbc))
                    .contains("必须先创建 Goal Brief")
                    .contains("创建或修订 Goal Brief 后，结束当前回复并等待用户选择")
                    .contains("不得在同一轮继续搜索、安装、导入或启用新能力")
                    .contains("仍需继续走原有 confirmation gate")
                    .doesNotContain("时提出 Goal Brief");
            assertThat(countOccurrences(mainPrompt(jdbc), HEADING)).isEqualTo(1);

            jdbc.update("DELETE FROM flyway_schema_history WHERE version='194'");
            migrateTo194(dataSource);
            assertThat(countOccurrences(mainPrompt(jdbc), HEADING)).isEqualTo(1);
        }
    }

    @Test
    void migration_preservesCustomizedPrompt() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.builder().start()) {
            DataSource dataSource = postgres.getPostgresDatabase();
            migrateTo193(dataSource);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            String customized = mainPrompt(jdbc) + " ";
            jdbc.update("UPDATE t_agent SET system_prompt=? WHERE id=3", customized);

            migrateTo194(dataSource);

            assertThat(mainPrompt(jdbc)).isEqualTo(customized);
        }
    }

    private static void migrateTo193(DataSource dataSource) {
        flyway(dataSource, MigrationVersion.fromVersion("193")).migrate();
    }

    private static void migrateTo194(DataSource dataSource) {
        flyway(dataSource, MigrationVersion.fromVersion("194")).migrate();
    }

    private static Flyway flyway(DataSource dataSource, MigrationVersion target) {
        var config = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .placeholders(Map.of("etl_mode", "off", "etl_trace_span_mode", "off"));
        if (target != null) config.target(target);
        return config.load();
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
