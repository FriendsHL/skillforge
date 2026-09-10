package com.skillforge.server.migration;

import com.skillforge.server.SharedPostgresContainer;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V195-to-V198 PostgreSQL contract for deterministic checkpoint sidecars added in V196. */
@Testcontainers(disabledWithoutDocker = true)
class CheckpointSidecarWatermarkV196MigrationIT {

    private static SharedPostgresContainer postgres;
    private static JdbcTemplate adminJdbc;
    private static JdbcTemplate migrationJdbc;
    private static DataSource migrationDataSource;
    private static String database;

    @BeforeAll
    static void migratePopulatedV195DatabaseToV198() {
        postgres = SharedPostgresContainer.getInstance();
        database = "sf_checkpoint_v196_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        adminJdbc = new JdbcTemplate(dataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        adminJdbc.execute("CREATE DATABASE " + database);
        migrationDataSource = dataSource(
                databaseUrl(), postgres.getUsername(), postgres.getPassword());
        migrationJdbc = new JdbcTemplate(migrationDataSource);
        migrateTo("195");
        migrationJdbc.update("""
                INSERT INTO t_session (id, user_id, agent_id, title)
                VALUES ('checkpoint-v196', 1, 1, 'V196 upgrade fixture')
                """);
        migrationJdbc.update("""
                INSERT INTO t_session_compaction_checkpoint
                    (id, session_id, boundary_seq_no, reason)
                VALUES ('legacy-checkpoint', 'checkpoint-v196', 0, 'manual')
                """);
        migrateTo("198");
    }

    @AfterAll
    static void dropDatabase() {
        if (adminJdbc != null && database != null) {
            adminJdbc.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
        }
    }

    @Test
    void populatedV195Upgrade_preservesLegacyNullAndDefaultsNewPairedWatermarks() {
        Map<String, Object> legacy = migrationJdbc.queryForMap("""
                SELECT sidecar_watermark, summary_id_watermark
                FROM t_session_compaction_checkpoint
                WHERE id = 'legacy-checkpoint'
                """);
        assertThat(legacy.get("sidecar_watermark")).isNull();
        assertThat(legacy.get("summary_id_watermark")).isNull();
        assertThat(migrationJdbc.queryForObject("""
                SELECT version FROM flyway_schema_history
                WHERE success AND version IS NOT NULL
                ORDER BY installed_rank DESC LIMIT 1
                """, String.class)).isEqualTo("198");

        Long first = insertCheckpoint("new-checkpoint-1", 0L);
        Long second = insertCheckpoint("new-checkpoint-2", 0L);
        assertThat(first).isPositive();
        assertThat(second).isGreaterThan(first);
        assertThat(migrationJdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes
                WHERE schemaname = 'public'
                  AND indexname = 'uq_scc_session_sidecar_watermark'
                """, Long.class)).isOne();

        assertThatThrownBy(() -> migrationJdbc.update("""
                INSERT INTO t_session_compaction_checkpoint
                    (id, session_id, boundary_seq_no, reason)
                VALUES ('missing-summary-watermark', 'checkpoint-v196', 0, 'manual')
                """)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> migrationJdbc.update("""
                INSERT INTO t_session_compaction_checkpoint
                    (id, session_id, boundary_seq_no, reason,
                     sidecar_watermark, summary_id_watermark)
                VALUES ('duplicate-sidecar-watermark', 'checkpoint-v196', 0, 'manual', ?, 0)
                """, first)).isInstanceOf(Exception.class);
    }

    private static Long insertCheckpoint(String id, long summaryIdWatermark) {
        return migrationJdbc.queryForObject("""
                INSERT INTO t_session_compaction_checkpoint
                    (id, session_id, boundary_seq_no, reason, summary_id_watermark)
                VALUES (?, 'checkpoint-v196', 0, 'manual', ?)
                RETURNING sidecar_watermark
                """, Long.class, id, summaryIdWatermark);
    }

    private static void migrateTo(String target) {
        Flyway.configure()
                .dataSource(migrationDataSource)
                .locations("classpath:db/migration")
                .placeholders(Map.of("etl_mode", "off", "etl_trace_span_mode", "off"))
                .validateOnMigrate(false)
                .target(MigrationVersion.fromVersion(target))
                .load()
                .migrate();
    }

    private static String databaseUrl() {
        return "jdbc:postgresql://localhost:" + postgres.getMappedPort(5432) + "/" + database;
    }

    private static DataSource dataSource(String url, String username, String password) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        return dataSource;
    }
}
