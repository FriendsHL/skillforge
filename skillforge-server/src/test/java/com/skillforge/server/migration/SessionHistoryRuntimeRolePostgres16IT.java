package com.skillforge.server.migration;

import com.skillforge.server.SharedPostgresContainer;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Literal PostgreSQL 16 W-R6-2 acceptance fixture.
 *
 * <p>This deliberately uses independent JDBC connections for the Flyway owner and
 * {@code skillforge_app}. It does not rely on JPA's non-updatable annotations for
 * append-only enforcement. Environments without Docker skip the entire class before
 * {@link SharedPostgresContainer#getInstance()} is invoked.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Session History PostgreSQL 16 runtime-role enforcement")
class SessionHistoryRuntimeRolePostgres16IT {

    private static final String RUNTIME_ROLE = "skillforge_app";
    private static final String RUNTIME_PASSWORD = "history_runtime_test_password";
    private static final String MIGRATOR_PASSWORD = "history_migrator_test_password";

    private static SharedPostgresContainer postgres;
    private static String database;
    private static String migratorRole;
    private static JdbcTemplate adminJdbc;
    private static JdbcTemplate migratorJdbc;
    private static JdbcTemplate runtimeJdbc;

    @BeforeAll
    static void createIsolatedRoleOwnedDatabase() {
        postgres = SharedPostgresContainer.getInstance();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        database = "sf_history16_" + suffix;
        migratorRole = "sf_history16_migrator_" + suffix;

        adminJdbc = new JdbcTemplate(dataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        adminJdbc.execute("CREATE ROLE " + migratorRole
                + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '"
                + MIGRATOR_PASSWORD + "'");
        if (!Boolean.TRUE.equals(adminJdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname=?)",
                Boolean.class, RUNTIME_ROLE))) {
            adminJdbc.execute("CREATE ROLE " + RUNTIME_ROLE
                    + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD '"
                    + RUNTIME_PASSWORD + "'");
        } else {
            adminJdbc.execute("ALTER ROLE " + RUNTIME_ROLE
                    + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD '"
                    + RUNTIME_PASSWORD + "'");
        }
        adminJdbc.execute("CREATE DATABASE " + database + " OWNER " + migratorRole);

        DataSource migratorDataSource = dataSource(
                databaseUrl(), migratorRole, MIGRATOR_PASSWORD);
        migratorJdbc = new JdbcTemplate(migratorDataSource);
        runtimeJdbc = new JdbcTemplate(dataSource(
                databaseUrl(), RUNTIME_ROLE, RUNTIME_PASSWORD));

        Flyway.configure()
                .dataSource(migratorDataSource)
                .locations("classpath:db/migration")
                .placeholders(Map.of("etl_mode", "off", "etl_trace_span_mode", "off"))
                .validateOnMigrate(false)
                .target(MigrationVersion.fromVersion("198"))
                .load()
                .migrate();
    }

    @Test
    @DisplayName("runtime is not owner and audit allows only SELECT and INSERT")
    void auditPrivileges_areEnforcedBySeparatePostgresRoles() {
        assertThat(migratorJdbc.queryForObject(
                "SELECT current_setting('server_version_num')::INTEGER", Integer.class))
                .isBetween(160000, 169999);
        assertThat(runtimeJdbc.queryForObject("SELECT current_user", String.class))
                .isEqualTo(RUNTIME_ROLE);
        assertThat(migratorJdbc.queryForObject("""
                SELECT tableowner FROM pg_tables
                WHERE schemaname='public'
                  AND tablename='t_session_tool_attempt_resolution_audit'
                """, String.class)).isEqualTo(migratorRole);

        assertPrivilege("SELECT", true);
        assertPrivilege("INSERT", true);
        assertPrivilege("UPDATE", false);
        assertPrivilege("DELETE", false);
        assertPrivilege("TRUNCATE", false);

        String sessionId = UUID.randomUUID().toString();
        insertSession(sessionId);
        UUID resolutionRequestId = insertAudit(sessionId, 91001L);

        assertThat(runtimeJdbc.queryForObject("""
                SELECT COUNT(*) FROM t_session_tool_attempt_resolution_audit
                WHERE resolution_request_id=?
                """, Long.class, resolutionRequestId)).isOne();
        assertPrivilegeDenied(() -> runtimeJdbc.update("""
                UPDATE t_session_tool_attempt_resolution_audit
                SET outcome_state='RESOLVED_UNKNOWN' WHERE resolution_request_id=?
                """, resolutionRequestId));
        assertPrivilegeDenied(() -> runtimeJdbc.update("""
                DELETE FROM t_session_tool_attempt_resolution_audit
                WHERE resolution_request_id=?
                """, resolutionRequestId));
        assertPrivilegeDenied(() -> runtimeJdbc.execute(
                "TRUNCATE TABLE t_session_tool_attempt_resolution_audit"));
    }

    @Test
    @DisplayName("attempt pruning and restore-style message deletion preserve audit")
    void ordinaryPruningAndRestoreRewrite_preserveAudit() {
        String pruneSession = UUID.randomUUID().toString();
        insertSession(pruneSession);
        long pruneMessage = insertAssistantMessage(pruneSession, 0L);
        long pruneAttempt = insertAttempt(pruneSession, pruneMessage);
        UUID pruneAudit = insertAudit(pruneSession, pruneAttempt);

        runtimeJdbc.update("DELETE FROM t_session_tool_attempt WHERE id=?", pruneAttempt);
        assertAuditExists(pruneAudit);

        String restoreSession = UUID.randomUUID().toString();
        insertSession(restoreSession);
        long restoreMessage = insertAssistantMessage(restoreSession, 0L);
        long restoreAttempt = insertAttempt(restoreSession, restoreMessage);
        UUID restoreAudit = insertAudit(restoreSession, restoreAttempt);

        runtimeJdbc.update(
                "DELETE FROM t_session_message WHERE session_id=?", restoreSession);
        assertThat(migratorJdbc.queryForObject(
                "SELECT COUNT(*) FROM t_session_tool_attempt WHERE id=?",
                Long.class, restoreAttempt)).isZero();
        assertAuditExists(restoreAudit);
    }

    @Test
    @DisplayName("explicit whole-Session deletion is the only audit cascade exception")
    void wholeSessionDeletion_cascadesAudit() {
        String sessionId = UUID.randomUUID().toString();
        insertSession(sessionId);
        UUID resolutionRequestId = insertAudit(sessionId, 93001L);
        assertAuditExists(resolutionRequestId);

        runtimeJdbc.update("DELETE FROM t_session WHERE id=?", sessionId);

        assertThat(migratorJdbc.queryForObject("""
                SELECT COUNT(*) FROM t_session_tool_attempt_resolution_audit
                WHERE resolution_request_id=?
                """, Long.class, resolutionRequestId)).isZero();
    }

    private static void assertPrivilege(String privilege, boolean expected) {
        assertThat(migratorJdbc.queryForObject("""
                SELECT has_table_privilege(
                    ?, 'public.t_session_tool_attempt_resolution_audit', ?)
                """, Boolean.class, RUNTIME_ROLE, privilege)).isEqualTo(expected);
    }

    private static void insertSession(String sessionId) {
        runtimeJdbc.update("""
                INSERT INTO t_session (id,user_id,agent_id,title)
                VALUES (?,1,1,'PostgreSQL 16 role fixture')
                """, sessionId);
    }

    private static long insertAssistantMessage(String sessionId, long seqNo) {
        runtimeJdbc.update("""
                INSERT INTO t_session_message
                    (session_id,seq_no,role,msg_type,content_json,metadata_json)
                VALUES (?,?,'ASSISTANT','NORMAL','[]','{}')
                """, sessionId, seqNo);
        return runtimeJdbc.queryForObject("""
                SELECT id FROM t_session_message WHERE session_id=? AND seq_no=?
                """, Long.class, sessionId, seqNo);
    }

    private static long insertAttempt(String sessionId, long assistantMessageId) {
        return runtimeJdbc.queryForObject("""
                INSERT INTO t_session_tool_attempt
                    (session_id,step_id,history_epoch,origin_loop_id,origin_fence,
                     assistant_message_id,assistant_payload_hash,
                     pre_intent_max_message_id,pre_intent_max_seq,
                     manifest_json,manifest_hash,replay_safety,state)
                VALUES (?, ?, 0, 'origin-loop', 0, ?, repeat('1',64), -1, -1,
                        '[]', repeat('2',64), 'UNKNOWN', 'INTENT_COMMITTED')
                RETURNING id
                """, Long.class, sessionId, UUID.randomUUID(), assistantMessageId);
    }

    private static UUID insertAudit(String sessionId, long attemptId) {
        UUID resolutionRequestId = UUID.randomUUID();
        runtimeJdbc.update("""
                INSERT INTO t_session_tool_attempt_resolution_audit
                    (resolution_request_id,session_id,attempt_id,step_id,history_epoch,
                     execution_generation,execution_fence,actor_id,actor_authority,
                     reason_hash,action,inbox_dispositions_json,result_batch_id,outcome_state)
                VALUES (?, ?, ?, ?, 0, 1, 1, 1, 'OWNER', repeat('a',64),
                        'CONTINUE_CURRENT_TIMELINE', '[]', ?, 'RESOLVED_UNKNOWN')
                """, resolutionRequestId, sessionId, attemptId,
                UUID.randomUUID(), UUID.randomUUID());
        return resolutionRequestId;
    }

    private static void assertAuditExists(UUID resolutionRequestId) {
        assertThat(migratorJdbc.queryForObject("""
                SELECT COUNT(*) FROM t_session_tool_attempt_resolution_audit
                WHERE resolution_request_id=?
                """, Long.class, resolutionRequestId)).isOne();
    }

    private static void assertPrivilegeDenied(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(Exception.class)
                .hasRootCauseInstanceOf(SQLException.class)
                .rootCause()
                .extracting(error -> ((SQLException) error).getSQLState())
                .isEqualTo("42501");
    }

    private static String databaseUrl() {
        return "jdbc:postgresql://" + postgres.getHost() + ":"
                + postgres.getMappedPort(5432) + "/" + database;
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
