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

/** V195-to-V198 contract for the internal W-R6-1 inbox-handoff checkpoint in V197. */
@Testcontainers(disabledWithoutDocker = true)
class SessionRunCoordinatorV197MigrationIT {

    private static SharedPostgresContainer postgres;
    private static JdbcTemplate adminJdbc;
    private static JdbcTemplate migrationJdbc;
    private static DataSource migrationDataSource;
    private static String database;

    @BeforeAll
    static void migratePopulatedV195DatabaseToV198() {
        postgres = SharedPostgresContainer.getInstance();
        database = "sf_run_coordinator_v197_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        adminJdbc = new JdbcTemplate(dataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        adminJdbc.execute("CREATE DATABASE " + database);

        migrationDataSource = dataSource(
                databaseUrl(), postgres.getUsername(), postgres.getPassword());
        migrationJdbc = new JdbcTemplate(migrationDataSource);
        migrateTo("195");
        insertV195Session();
        migrateTo("198");
        insertCompletedContinuation();
    }

    @AfterAll
    static void dropDatabase() {
        if (adminJdbc != null && database != null) {
            adminJdbc.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
        }
    }

    @Test
    void populatedV195Upgrade_embedsInboxHandoffCheckpointInV197Shape() {
        assertThat(migrationJdbc.queryForObject("""
                SELECT post_action_inbox_handoff_accepted
                FROM t_session_tool_attempt
                WHERE session_id = 'v197-upgrade'
                """, Boolean.class)).isTrue();
        assertThat(migrationJdbc.queryForObject("""
                SELECT version FROM flyway_schema_history
                WHERE success AND version IS NOT NULL
                ORDER BY installed_rank DESC LIMIT 1
                """, String.class)).isEqualTo("198");
        assertThat(migrationJdbc.queryForObject("""
                SELECT column_default FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 't_session_tool_attempt'
                  AND column_name = 'post_action_inbox_handoff_accepted'
                """, String.class)).contains("false");

        assertThatThrownBy(() -> migrationJdbc.update("""
                UPDATE t_session_tool_attempt
                SET post_action_inbox_handoff_accepted = FALSE
                WHERE session_id = 'v197-upgrade'
                """))
                .isInstanceOf(Exception.class);
    }

    private static void insertV195Session() {
        migrationJdbc.update("""
                INSERT INTO t_session (id, user_id, agent_id, title)
                VALUES ('v197-upgrade', 1, 1, 'V197 upgrade fixture')
                """);
    }

    private static void insertCompletedContinuation() {
        Long messageId = migrationJdbc.queryForObject("""
                INSERT INTO t_session_message
                    (session_id, seq_no, role, msg_type, content_json, metadata_json)
                VALUES ('v197-upgrade', 0, 'ASSISTANT', 'NORMAL', '[]', '{}')
                RETURNING id
                """, Long.class);
        UUID resultBatchId = UUID.randomUUID();
        migrationJdbc.update("""
                INSERT INTO t_session_tool_attempt (
                    session_id, step_id, history_epoch, origin_loop_id, origin_fence,
                    assistant_message_id, assistant_payload_hash,
                    pre_intent_max_message_id, pre_intent_max_seq,
                    manifest_json, manifest_hash, replay_safety, state,
                    execution_loop_id, execution_fence, execution_owner_instance_id,
                    execution_generation, claim_request_id, claimed_at, execution_lease_until,
                    result_batch_id, result_execution_generation, result_execution_fence,
                    archive_preparation_state, archive_prepared_count, archive_total_count,
                    post_action_state, post_action_resolution_request_id,
                    post_action_result_batch_id, post_action_kind,
                    post_action_claim_request_id, post_action_loop_id, post_action_fence,
                    post_action_inbox_handoff_accepted)
                VALUES (
                    'v197-upgrade', ?, 0, 'origin-loop', 0, ?, repeat('a', 64),
                    -1, -1, '[]', repeat('b', 64), 'MUTATING', 'RESOLVED_UNKNOWN',
                    'execution-loop', 0, 'execution-owner', 1, ?, clock_timestamp(),
                    clock_timestamp() + INTERVAL '1 minute', ?, 1, 0,
                    'RAW_FALLBACK', 0, 1, 'COMPLETED', ?, ?,
                    'CONTINUE_CURRENT_TIMELINE', ?, 'continuation-loop', 1, TRUE)
                """, UUID.randomUUID(), messageId, UUID.randomUUID(), resultBatchId,
                UUID.randomUUID(), resultBatchId, UUID.randomUUID());
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
