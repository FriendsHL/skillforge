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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeFailureMigrationIT {

    private static EmbeddedPostgres postgres;
    private static Connection connection;
    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        connection = postgres.getPostgresDatabase().getConnection();
        jdbcTemplate = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
    }

    @AfterAll
    static void stopPostgres() throws SQLException, IOException {
        if (connection != null) connection.close();
        if (postgres != null) postgres.close();
    }

    @BeforeEach
    void createBaselineSchema() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS t_session");
        jdbcTemplate.execute("""
                CREATE TABLE t_session (
                    id VARCHAR(36) PRIMARY KEY,
                    runtime_status VARCHAR(32),
                    runtime_step VARCHAR(256),
                    runtime_error TEXT
                )
                """);
    }

    @Test
    void v175BackfillsLegacyErrorsAndLeavesNonErrorsWithoutFailureFact() {
        jdbcTemplate.update("""
                INSERT INTO t_session (id, runtime_status, runtime_step, runtime_error) VALUES
                    ('retryable-error', 'error', 'retryable', '/Users/private/provider-body secret-token'),
                    ('unsafe-error', 'error', NULL, 'legacy failure with internal diagnostics'),
                    ('idle-session', 'idle', 'healthy', '/Users/private/stale-error secret-token')
                """);

        runV175();

        assertThat(failureFact("retryable-error")).containsAllEntriesOf(Map.of(
                "runtime_failure_source", "unknown",
                "runtime_failure_code", "LEGACY_RUNTIME_FAILURE",
                "runtime_retryable", false,
                "runtime_side_effects", "possible"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT runtime_step FROM t_session WHERE id = 'retryable-error'", String.class))
                .isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT runtime_error FROM t_session WHERE id = 'retryable-error'", String.class))
                .isEqualTo("A previous runtime failure was recorded.")
                .doesNotContain("/Users/private", "secret-token", "provider-body");
        assertThat(failureFact("unsafe-error")).containsAllEntriesOf(Map.of(
                "runtime_failure_source", "unknown",
                "runtime_failure_code", "LEGACY_RUNTIME_FAILURE",
                "runtime_retryable", false,
                "runtime_side_effects", "possible"));

        Map<String, Object> idle = failureFact("idle-session");
        assertThat(idle.get("runtime_failure_source")).isNull();
        assertThat(idle.get("runtime_failure_code")).isNull();
        assertThat(idle.get("runtime_retryable")).isEqualTo(false);
        assertThat(idle.get("runtime_side_effects")).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT runtime_step FROM t_session WHERE id = 'idle-session'", String.class))
                .isEqualTo("healthy");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT runtime_error FROM t_session WHERE id = 'idle-session'", String.class))
                .isNull();
    }

    @Test
    void v175RejectsUnknownEnumsPartialFactsAndUnsafeRetryableCombinations() {
        runV175();

        assertThatThrownBy(() -> insertFact("bad-source", "other", "BAD", false, "possible"))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> insertFact("bad-side-effects", "harness", "BAD", false, "maybe"))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> insertFact("partial", "harness", null, false, "possible"))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> insertFact("unsafe-retry", "network", "NETWORK_TIMEOUT", true, "possible"))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> insertFact("unsafe-source-retry", "tool", "TOOL_FAILED", true, "none"))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO t_session (
                    id, runtime_status, runtime_failure_source, runtime_failure_code,
                    runtime_retryable, runtime_side_effects)
                VALUES ('idle-with-fact', 'idle', 'harness', 'STALE_FAILURE', FALSE, 'possible')
                """))
                .isInstanceOf(Exception.class);

        insertFact("safe-retry", "network", "NETWORK_TIMEOUT", true, "none");
        assertThat(failureFact("safe-retry").get("runtime_retryable")).isEqualTo(true);

        runV175();
        assertThat(failureFact("safe-retry")).containsAllEntriesOf(Map.of(
                "runtime_failure_source", "network",
                "runtime_failure_code", "NETWORK_TIMEOUT",
                "runtime_retryable", true,
                "runtime_side_effects", "none"));
    }

    @Test
    void v175RejectsErrorWithoutFailureFact() {
        runV175();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO t_session (id, runtime_status)
                VALUES ('error-without-fact', 'error')
                """))
                .isInstanceOf(Exception.class);
    }

    @Test
    void v175RejectsErrorFactWithoutRuntimeError() {
        runV175();

        assertThatThrownBy(() -> insertFact(
                "error-without-reason", "harness", "LOOP_STATE", false, "possible", null))
                .isInstanceOf(Exception.class);
    }

    @Test
    void v175RejectsErrorFactWithBlankRuntimeError() {
        runV175();

        assertThatThrownBy(() -> insertFact(
                "error-with-blank-reason", "harness", "LOOP_STATE", false, "possible", "   "))
                .isInstanceOf(Exception.class);
    }

    @Test
    void v175RejectsIdleWithoutFactButWithStaleRuntimeError() {
        runV175();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO t_session (id, runtime_status, runtime_error)
                VALUES ('idle-with-stale-reason', 'idle', 'stale runtime failure')
                """))
                .isInstanceOf(Exception.class);
    }

    @Test
    void v175RejectsRunningWithoutFactButWithStaleRuntimeError() {
        runV175();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO t_session (id, runtime_status, runtime_error)
                VALUES ('running-with-stale-reason', 'running', 'stale runtime failure')
                """))
                .isInstanceOf(Exception.class);
    }

    @Test
    void v175RejectsErrorFactWithBlankFailureCode() {
        runV175();

        assertThatThrownBy(() -> insertFact(
                "error-with-blank-code", "harness", "   ", false, "possible"))
                .isInstanceOf(Exception.class);
    }

    private static void insertFact(String id, String source, String code,
                                   boolean retryable, String sideEffects) {
        insertFact(id, source, code, retryable, sideEffects, "A runtime failure was recorded.");
    }

    private static void insertFact(String id, String source, String code,
                                   boolean retryable, String sideEffects, String runtimeError) {
        jdbcTemplate.update("""
                INSERT INTO t_session (
                    id, runtime_status, runtime_failure_source, runtime_failure_code,
                    runtime_retryable, runtime_side_effects, runtime_error)
                VALUES (?, 'error', ?, ?, ?, ?, ?)
                """, id, source, code, retryable, sideEffects, runtimeError);
    }

    private static Map<String, Object> failureFact(String id) {
        return jdbcTemplate.queryForMap("""
                SELECT runtime_failure_source, runtime_failure_code,
                       runtime_retryable, runtime_side_effects
                FROM t_session WHERE id = ?
                """, id);
    }

    private static void runV175() {
        ScriptUtils.executeSqlScript(
                connection,
                new ClassPathResource("db/migration/V175__structured_runtime_failure_fact.sql"));
    }
}
