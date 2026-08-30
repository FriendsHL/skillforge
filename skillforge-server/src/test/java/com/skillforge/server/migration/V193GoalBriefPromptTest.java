package com.skillforge.server.migration;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class V193GoalBriefPromptTest {

    private static final String GOAL_BRIEF_HEADING = "## Goal Brief 行为";

    @Test
    void migration_updatesExactV189PromptAndAppendsGuidanceOnce() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.builder().start()) {
            DataSource dataSource = postgres.getPostgresDatabase();
            migrateTo192(dataSource);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);

            migrateTo193(dataSource);
            String migrated = mainPrompt(jdbc);
            assertThat(migrated).contains(GOAL_BRIEF_HEADING)
                    .contains("Goal Brief 按钮消息")
                    .contains("不能满足 CreateAgent、外部代码执行、权限、发布或其他高影响操作的确认要求");

            jdbc.update("DELETE FROM flyway_schema_history WHERE version='193'");
            migrateTo193(dataSource);
            assertThat(countOccurrences(mainPrompt(jdbc), GOAL_BRIEF_HEADING)).isEqualTo(1);
        }
    }

    @Test
    void migration_preservesCustomizedPrompt() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.builder().start()) {
            DataSource dataSource = postgres.getPostgresDatabase();
            migrateTo192(dataSource);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            String customized = mainPrompt(jdbc) + " ";
            jdbc.update("UPDATE t_agent SET system_prompt=? WHERE id=3", customized);

            migrateTo193(dataSource);

            assertThat(mainPrompt(jdbc)).isEqualTo(customized);
        }
    }

    private static void migrateTo192(DataSource dataSource) {
        flyway(dataSource, MigrationVersion.fromVersion("192")).migrate();
    }

    private static void migrateTo193(DataSource dataSource) {
        flyway(dataSource, MigrationVersion.fromVersion("193")).migrate();
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

    private static int countOccurrences(String value, String needle) {
        return (value.length() - value.replace(needle, "").length()) / needle.length();
    }
}
