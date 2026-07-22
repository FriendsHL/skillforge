package com.skillforge.server.migration;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionKillRecoveryMigrationIT {

    private static EmbeddedPostgres postgres;
    private static Connection connection;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        connection = postgres.getPostgresDatabase().getConnection();
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
    }

    @AfterAll
    static void stopPostgres() throws SQLException, IOException {
        if (connection != null) connection.close();
        if (postgres != null) postgres.close();
    }

    @BeforeEach
    void baseline() {
        jdbc.execute("DROP TABLE IF EXISTS t_session");
        jdbc.execute("""
                CREATE TABLE t_session (
                    id VARCHAR(36) PRIMARY KEY,
                    origin VARCHAR(16) NOT NULL DEFAULT 'production',
                    runtime_status VARCHAR(32)
                )
                """);
        ScriptUtils.executeSqlScript(connection,
                new ClassPathResource("db/migration/V177__session_kill_recovery_state.sql"));
    }

    @Test
    void defaultsAndValidRecoveryStatesArePersisted() {
        jdbc.update("INSERT INTO t_session (id, runtime_status) VALUES ('s1', 'running')");
        assertThat(jdbc.queryForMap("""
                SELECT recovery_attempts, recovery_state, recovery_reason, recovery_started_at
                FROM t_session WHERE id='s1'
                """))
                .containsEntry("recovery_attempts", 0)
                .containsEntry("recovery_state", "none")
                .containsEntry("recovery_reason", null)
                .containsEntry("recovery_started_at", null);

        jdbc.update("""
                UPDATE t_session SET recovery_attempts=1, recovery_state='recovering',
                    recovery_reason='SERVER_RESTART', recovery_started_at=NOW()
                WHERE id='s1'
                """);
        assertThat(jdbc.queryForObject(
                "SELECT recovery_state FROM t_session WHERE id='s1'", String.class))
                .isEqualTo("recovering");
    }

    @Test
    void invalidStateAndAttemptBudgetAreRejected() {
        jdbc.update("INSERT INTO t_session (id, runtime_status) VALUES ('s1', 'running')");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE t_session SET recovery_state='unknown' WHERE id='s1'"))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE t_session SET recovery_attempts=4 WHERE id='s1'"))
                .isInstanceOf(Exception.class);
    }
}
