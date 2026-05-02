package com.skillforge.observability.store;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OBS-2 M1 §B.3 R2-B3 — verifies the SQL contracts encoded in {@link PgLlmTraceStore}'s
 * native SQL for the new lifecycle methods:
 *
 * <ul>
 *   <li>{@code upsertTraceStub} — INSERT ON CONFLICT DO NOTHING (idempotent)</li>
 *   <li>{@code finalizeTrace} — UPDATE WHERE status='running' (idempotent terminal)</li>
 * </ul>
 *
 * <p>This test runs the SQL directly (without Spring) to keep it focused on the
 * SQL contract; integration with the Java method wrapping is exercised via the
 * existing {@code SessionSpansAuthIT} / {@code SessionSpansMergedIT}.
 */
@DisplayName("PgLlmTraceStore — OBS-2 M1 lifecycle SQL semantics")
class PgLlmTraceLifecycleIT {

    /** Mirrors {@code PgLlmTraceStore.INSERT_TRACE_STUB_SQL}. */
    private static final String INSERT_TRACE_STUB_SQL =
            "INSERT INTO t_llm_trace ("
                    + "  trace_id, session_id, agent_id, user_id, agent_name, root_name,"
                    + "  status, started_at, total_input_tokens, total_output_tokens,"
                    + "  total_duration_ms, tool_call_count, event_count,"
                    + "  source, created_at"
                    + ") VALUES ("
                    + "  ?, ?, ?, ?, ?, ?,"
                    + "  'running', ?, 0, 0,"
                    + "  0, 0, 0,"
                    + "  'live', now()"
                    + ") "
                    + "ON CONFLICT (trace_id) DO NOTHING";

    /** Mirrors {@code PgLlmTraceStore.FINALIZE_TRACE_SQL}. */
    private static final String FINALIZE_TRACE_SQL =
            "UPDATE t_llm_trace "
                    + "SET status            = ?, "
                    + "    error             = ?, "
                    + "    total_duration_ms = ?, "
                    + "    tool_call_count   = ?, "
                    + "    event_count       = ?, "
                    + "    ended_at          = ? "
                    + "WHERE trace_id = ? "
                    + "  AND status   = 'running'";

    private static EmbeddedPostgres pg;
    private static DataSource ds;

    @BeforeAll
    static void startPg() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        ds = pg.getPostgresDatabase();
        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .placeholders(java.util.Map.of(
                        "etl_mode", "off",
                        "etl_trace_span_mode", "off"))
                .load()
                .migrate();
    }

    @AfterAll
    static void stopPg() throws Exception {
        if (pg != null) pg.close();
    }

    @BeforeEach
    void clean() throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM t_llm_trace")) {
            ps.executeUpdate();
        }
    }

    @Test
    @DisplayName("upsertTraceStub: first insert creates row with status='running'; second is no-op (DO NOTHING)")
    void upsertTraceStub_idempotent() throws Exception {
        String traceId = UUID.randomUUID().toString();
        String sessionId = UUID.randomUUID().toString();
        Instant first = Instant.parse("2026-05-02T10:00:00Z");
        Instant second = Instant.parse("2026-05-02T10:00:30Z");

        insertStub(traceId, sessionId, "main-agent", first);
        // Second call with different agent_name + later started_at — must NOT overwrite
        insertStub(traceId, sessionId, "different-agent", second);

        Row r = readRow(traceId);
        assertThat(r.status).as("status stays 'running' after stub create").isEqualTo("running");
        assertThat(r.agentName).as("agent_name not overwritten by second stub").isEqualTo("main-agent");
        assertThat(r.totalDurationMs).isEqualTo(0L);
        assertThat(r.toolCallCount).isEqualTo(0);
        assertThat(r.eventCount).isEqualTo(0);
        assertThat(r.error).isNull();
    }

    @Test
    @DisplayName("finalizeTrace: 'running' → 'ok' first, second call as 'error' is idempotent no-op (status='ok' guard)")
    void finalizeTrace_idempotent_terminalStatusNotOverwritten() throws Exception {
        String traceId = UUID.randomUUID().toString();
        String sessionId = UUID.randomUUID().toString();
        insertStub(traceId, sessionId, "agent", Instant.parse("2026-05-02T10:00:00Z"));

        // First finalize: status running → ok with tool/event counts.
        int updated1 = finalize(traceId, "ok", null, 1234L, 3, 2,
                Instant.parse("2026-05-02T10:00:05Z"));
        assertThat(updated1).as("first finalize updates exactly 1 row").isEqualTo(1);

        Row r1 = readRow(traceId);
        assertThat(r1.status).isEqualTo("ok");
        assertThat(r1.error).isNull();
        assertThat(r1.totalDurationMs).isEqualTo(1234L);
        assertThat(r1.toolCallCount).isEqualTo(3);
        assertThat(r1.eventCount).isEqualTo(2);

        // Second finalize: ChatService catch-block-style "error" — must NOT overwrite ok.
        int updated2 = finalize(traceId, "error", "agent_loop_exception", 9999L, 0, 0,
                Instant.parse("2026-05-02T10:01:00Z"));
        assertThat(updated2).as("second finalize is idempotent no-op").isEqualTo(0);

        Row r2 = readRow(traceId);
        assertThat(r2.status).as("terminal 'ok' must not be downgraded to 'error'").isEqualTo("ok");
        assertThat(r2.error).as("error remains null from first finalize").isNull();
        assertThat(r2.totalDurationMs).as("metrics remain from first finalize").isEqualTo(1234L);
    }

    @Test
    @DisplayName("finalizeTrace: catch-block error path on still-running trace successfully finalizes to 'error'")
    void finalizeTrace_catchPath_setsError() throws Exception {
        String traceId = UUID.randomUUID().toString();
        String sessionId = UUID.randomUUID().toString();
        insertStub(traceId, sessionId, "agent", Instant.parse("2026-05-02T10:00:00Z"));

        // Engine threw before its own finalize → ChatService catch block fires:
        int updated = finalize(traceId, "error", "agent_loop_exception", 500L, 0, 0,
                Instant.parse("2026-05-02T10:00:01Z"));
        assertThat(updated).isEqualTo(1);

        Row r = readRow(traceId);
        assertThat(r.status).isEqualTo("error");
        assertThat(r.error).isEqualTo("agent_loop_exception");
        assertThat(r.totalDurationMs).isEqualTo(500L);
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private void insertStub(String traceId, String sessionId, String agentName, Instant startedAt) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(INSERT_TRACE_STUB_SQL)) {
            ps.setString(1, traceId);
            ps.setString(2, sessionId);
            ps.setNull(3, Types.BIGINT);
            ps.setNull(4, Types.BIGINT);
            ps.setString(5, agentName);
            ps.setString(6, agentName);
            ps.setTimestamp(7, Timestamp.from(startedAt));
            ps.executeUpdate();
        }
    }

    private int finalize(String traceId, String status, String error, long durationMs,
                         int toolCount, int eventCount, Instant endedAt) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(FINALIZE_TRACE_SQL)) {
            ps.setString(1, status);
            ps.setString(2, error);
            ps.setLong(3, durationMs);
            ps.setInt(4, toolCount);
            ps.setInt(5, eventCount);
            ps.setTimestamp(6, Timestamp.from(endedAt));
            ps.setString(7, traceId);
            return ps.executeUpdate();
        }
    }

    private Row readRow(String traceId) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT status, error, total_duration_ms, tool_call_count, event_count, "
                             + "agent_name FROM t_llm_trace WHERE trace_id = ?")) {
            ps.setString(1, traceId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("trace row exists").isTrue();
                Row r = new Row();
                r.status = rs.getString(1);
                r.error = rs.getString(2);
                r.totalDurationMs = rs.getLong(3);
                r.toolCallCount = rs.getInt(4);
                r.eventCount = rs.getInt(5);
                r.agentName = rs.getString(6);
                return r;
            }
        }
    }

    private static final class Row {
        String status;
        String error;
        long totalDurationMs;
        int toolCallCount;
        int eventCount;
        String agentName;
    }
}
