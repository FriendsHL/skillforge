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

class TeamTaskRuntimeMigrationIT {
    private static EmbeddedPostgres postgres;
    private static Connection connection;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void start() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        connection = postgres.getPostgresDatabase().getConnection();
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
    }

    @AfterAll
    static void stop() throws SQLException, IOException {
        if (connection != null) connection.close();
        if (postgres != null) postgres.close();
    }

    @BeforeEach
    void baseline() {
        jdbc.execute("DROP TABLE IF EXISTS t_session_task_event CASCADE");
        jdbc.execute("DROP TABLE IF EXISTS t_session_task_attempt CASCADE");
        jdbc.execute("DROP TABLE IF EXISTS t_session_task CASCADE");
        jdbc.execute("DROP TABLE IF EXISTS t_collab_run CASCADE");
        jdbc.execute("CREATE TABLE t_collab_run(collab_run_id VARCHAR(36) PRIMARY KEY)");
        jdbc.execute("""
                CREATE TABLE t_session_task(
                    id VARCHAR(36) NOT NULL,
                    session_id VARCHAR(36) NOT NULL,
                    PRIMARY KEY(id),
                    UNIQUE(id, session_id))
                """);
        jdbc.update("INSERT INTO t_collab_run(collab_run_id) VALUES ('c1')");
        jdbc.update("INSERT INTO t_session_task(id, session_id) VALUES ('t1', 'leader')");
        ScriptUtils.executeSqlScript(connection,
                new ClassPathResource("db/migration/V192__create_team_task_runtime.sql"));
    }

    @Test
    void enforcesOneActiveAttemptAndPreservesImmutableHistory() {
        insertAttempt("a1", 1, "ACTIVE");
        assertThatThrownBy(() -> insertAttempt("a2", 2, "ACTIVE"))
                .isInstanceOf(Exception.class);

        jdbc.update("UPDATE t_session_task_attempt SET status='RELEASED', finished_at=NOW() WHERE id='a1'");
        insertAttempt("a2", 2, "ACTIVE");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_session_task_attempt WHERE task_id='t1'", Long.class))
                .isEqualTo(2L);

        jdbc.update("""
                INSERT INTO t_session_task_event(
                    task_id, graph_session_id, collab_run_id, attempt_id, event_type,
                    actor_session_id, actor_agent_id, from_status, to_status, created_at)
                VALUES ('t1','leader','c1','a2','TASK_CLAIMED','worker-1',9,'pending','in_progress',NOW())
                """);
        assertThat(jdbc.queryForObject(
                "SELECT event_type FROM t_session_task_event WHERE task_id='t1'", String.class))
                .isEqualTo("TASK_CLAIMED");
    }

    @Test
    void rejectsAttemptForAnotherGraphSessionAndCascadesWithTask() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO t_session_task_attempt(
                    id,task_id,graph_session_id,collab_run_id,attempt_no,
                    worker_session_id,worker_agent_id,lease_token,status,
                    leased_at,heartbeat_at,expires_at)
                VALUES ('bad','t1','other','c1',1,'worker-1',9,'lease-bad','ACTIVE',NOW(),NOW(),NOW())
                """)).isInstanceOf(Exception.class);

        insertAttempt("a1", 1, "ACTIVE");
        jdbc.update("DELETE FROM t_session_task WHERE id='t1'");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_session_task_attempt", Long.class)).isZero();
    }

    private void insertAttempt(String id, int attemptNo, String status) {
        jdbc.update("""
                INSERT INTO t_session_task_attempt(
                    id,task_id,graph_session_id,collab_run_id,attempt_no,
                    worker_session_id,worker_agent_id,lease_token,status,
                    leased_at,heartbeat_at,expires_at)
                VALUES (?,'t1','leader','c1',?,'worker-1',9,?,?,NOW(),NOW(),NOW() + INTERVAL '2 minutes')
                """, id, attemptNo, "lease-" + id, status);
    }
}
