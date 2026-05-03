package com.skillforge.server.observability;

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
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OBS-2 M1 W3 — dual-write tool span ID consistency IT.
 *
 * <p>During the M1→M3 transition both {@code t_trace_span} (legacy) and
 * {@code t_llm_span} (kind='tool', new) receive a row when {@code AgentLoopEngine}
 * finishes a tool call. The PRD §6 invariant is that the same UUID is used for
 * {@code t_trace_span.id} and {@code t_llm_span.span_id} so the consistency
 * script ({@code scripts/observability/check_dual_write.sql}) can join across
 * the two tables on a single key.
 *
 * <p>This IT seeds the dual write and verifies:
 * <ol>
 *   <li>Both tables can hold the same UUID (no PK collision: separate tables, separate PKs).</li>
 *   <li>An INNER JOIN on {@code t_trace_span.id = t_llm_span.span_id} returns the row.</li>
 *   <li>The {@code kind='tool'} discriminator on the new table mirrors the
 *       {@code span_type='TOOL_CALL'} discriminator on the old table.</li>
 * </ol>
 */
@DisplayName("OBS-2 M1 W3 — t_trace_span.id == t_llm_span.span_id (dual write)")
class DualWriteToolSpanIT {

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
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM t_llm_span")) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM t_trace_span")) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM t_llm_trace")) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM t_session")) {
                ps.executeUpdate();
            }
        }
    }

    @Test
    @DisplayName("Dual-write writes same UUID to both tables; join on t_trace_span.id = t_llm_span.span_id matches")
    void dualWriteSameUuidJoinsCorrectly() throws Exception {
        String traceId = UUID.randomUUID().toString();
        String sessionId = UUID.randomUUID().toString();
        String sharedSpanId = UUID.randomUUID().toString();
        Instant t = Instant.parse("2026-05-02T10:00:00Z");

        seedSession(sessionId);
        seedTrace(traceId, sessionId, t);
        // 1) Old table — t_trace_span row written by traceCollector.record(...)
        insertTraceSpan(sharedSpanId, traceId, sessionId, "TOOL_CALL", "FileRead", t);
        // 2) New table — t_llm_span row written by PgLlmTraceStore.writeToolSpan
        insertLlmToolSpan(sharedSpanId, traceId, sessionId, "FileRead", t);

        // Cross-table consistency check (mirrors scripts/observability/check_dual_write.sql).
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ts.id, ls.span_id, ts.span_type, ls.kind "
                             + "FROM t_trace_span ts "
                             + "JOIN t_llm_span ls ON ls.span_id = ts.id "
                             + "WHERE ts.span_type = 'TOOL_CALL' "
                             + "  AND ls.kind      = 'tool'")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("dual-write row visible via cross-table join").isTrue();
                assertThat(rs.getString("id")).isEqualTo(sharedSpanId);
                assertThat(rs.getString("span_id")).isEqualTo(sharedSpanId);
                assertThat(rs.getString("span_type")).isEqualTo("TOOL_CALL");
                assertThat(rs.getString("kind")).isEqualTo("tool");
                assertThat(rs.next()).as("only one matching row").isFalse();
            }
        }
    }

    @Test
    @DisplayName("Mismatched IDs surface as missing in cross-table join (sanity for diagnostic SQL)")
    void mismatchedIdsBreakJoin() throws Exception {
        String traceId = UUID.randomUUID().toString();
        String sessionId = UUID.randomUUID().toString();
        String oldId = UUID.randomUUID().toString();
        String newId = UUID.randomUUID().toString();
        Instant t = Instant.parse("2026-05-02T10:00:00Z");

        seedSession(sessionId);
        seedTrace(traceId, sessionId, t);
        insertTraceSpan(oldId, traceId, sessionId, "TOOL_CALL", "FileRead", t);
        insertLlmToolSpan(newId, traceId, sessionId, "FileRead", t);

        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM t_trace_span ts "
                             + "JOIN t_llm_span ls ON ls.span_id = ts.id "
                             + "WHERE ts.span_type = 'TOOL_CALL' AND ls.kind = 'tool'")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).as("no join row when IDs diverged").isZero();
            }
        }

        // The diagnostic 'missing tool' query should pick this up.
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM t_trace_span ts "
                             + "LEFT JOIN t_llm_span ls ON ls.span_id = ts.id AND ls.kind = 'tool' "
                             + "WHERE ts.span_type = 'TOOL_CALL' AND ls.span_id IS NULL")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).as("missing_tool diagnostic catches divergent IDs").isOne();
            }
        }
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private void seedSession(String sessionId) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO t_session (id, user_id, agent_id, runtime_status, status) "
                             + "VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, sessionId);
            ps.setLong(2, 1L);
            ps.setLong(3, 1L);
            ps.setString(4, "idle");
            ps.setString(5, "active");
            ps.executeUpdate();
        }
    }

    private void seedTrace(String traceId, String sessionId, Instant startedAt) throws Exception {
        // OBS-4: root_trace_id NOT NULL after V46 — set self-as-root (matches V45 backfill).
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO t_llm_trace ("
                             + " trace_id, root_trace_id, session_id, agent_id, user_id, agent_name, root_name,"
                             + " status, started_at, total_input_tokens, total_output_tokens,"
                             + " total_duration_ms, tool_call_count, event_count,"
                             + " source, created_at"
                             + ") VALUES ("
                             + " ?, ?, ?, NULL, NULL, 'agent', 'agent',"
                             + " 'running', ?, 0, 0,"
                             + " 0, 0, 0,"
                             + " 'live', now())")) {
            ps.setString(1, traceId);
            ps.setString(2, traceId); // root_trace_id = self
            ps.setString(3, sessionId);
            ps.setTimestamp(4, Timestamp.from(startedAt));
            ps.executeUpdate();
        }
    }

    private void insertTraceSpan(String id, String parentSpanId, String sessionId,
                                  String spanType, String name, Instant startTime) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO t_trace_span ("
                             + " id, parent_span_id, session_id, span_type, name,"
                             + " start_time, end_time, duration_ms, iteration_index, success"
                             + ") VALUES (?, ?, ?, ?, ?, ?, ?, 100, 0, true)")) {
            ps.setString(1, id);
            ps.setString(2, parentSpanId);
            ps.setString(3, sessionId);
            ps.setString(4, spanType);
            ps.setString(5, name);
            ps.setTimestamp(6, Timestamp.from(startTime));
            ps.setTimestamp(7, Timestamp.from(startTime.plusMillis(100)));
            ps.executeUpdate();
        }
    }

    private void insertLlmToolSpan(String spanId, String traceId, String sessionId,
                                    String name, Instant startedAt) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO t_llm_span ("
                             + " span_id, trace_id, parent_span_id, session_id, agent_id,"
                             + " kind, name, iteration_index, stream,"
                             + " input_tokens, output_tokens,"
                             + " latency_ms, started_at,"
                             + " source, created_at, blob_status"
                             + ") VALUES ("
                             + " ?, ?, ?, ?, NULL,"
                             + " 'tool', ?, 0, false,"
                             + " 0, 0,"
                             + " 100, ?,"
                             + " 'live', now(), 'ok')")) {
            ps.setString(1, spanId);
            ps.setString(2, traceId);
            ps.setString(3, traceId);
            ps.setString(4, sessionId);
            ps.setString(5, name);
            ps.setTimestamp(6, Timestamp.from(startedAt));
            ps.executeUpdate();
        }
    }
}
