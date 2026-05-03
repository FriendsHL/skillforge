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
import java.sql.Types;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OBS-2 M1 W3 — end-to-end trace_id propagation IT.
 *
 * <p>Verifies the contract enforced across {@code ChatService} (writes
 * {@code t_session_message.trace_id}), {@code AgentLoopEngine}/{@code PgLlmTraceStore}
 * (writes {@code t_llm_trace.trace_id} and {@code t_llm_span.trace_id}): the same
 * UUID flows through all three tables, so per-trace queries can filter consistently.
 *
 * <p>Implementation uses raw JDBC against {@code EmbeddedPostgres}+Flyway (the same
 * pattern as {@code PgLlmTraceLifecycleIT}) so the test stays focused on the schema
 * contract — it does not boot Spring or run a real chat loop. Live propagation is
 * also covered by {@code AgentLoopEngineCompactTest} et al; this IT specifically
 * locks the cross-table column shape so a future schema change cannot silently drop it.
 */
@DisplayName("OBS-2 M1 W3 — t_session_message.trace_id == t_llm_trace.trace_id == t_llm_span.trace_id")
class TraceIdPropagationIT {

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
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM t_llm_trace")) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM t_session_message")) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM t_session")) {
                ps.executeUpdate();
            }
        }
    }

    @Test
    @DisplayName("Same trace_id readable from all three tables after a simulated chat turn")
    void traceIdConsistentAcrossThreeTables() throws Exception {
        String traceId = UUID.randomUUID().toString();
        String sessionId = UUID.randomUUID().toString();
        String spanId = UUID.randomUUID().toString();
        Instant now = Instant.parse("2026-05-02T10:00:00Z");

        seedSession(sessionId);
        // 1) ChatService writes the user message row with trace_id = X.
        insertSessionMessage(sessionId, 0L, "user", traceId, now);
        // 2) AgentLoopEngine.upsertTraceStub writes t_llm_trace with trace_id = X.
        insertTraceStub(traceId, sessionId, now);
        // 3) AgentLoopEngine writes a tool span with trace_id = X.
        insertLlmSpan(spanId, traceId, sessionId, "tool", "Bash", now);

        // Verify by INNER JOIN on trace_id — if any column drifted, the join returns 0.
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT m.trace_id AS msg_trace, "
                             + "       t.trace_id AS trc_trace, "
                             + "       s.trace_id AS span_trace, "
                             + "       s.kind AS kind "
                             + "FROM t_session_message m "
                             + "JOIN t_llm_trace t ON t.trace_id = m.trace_id "
                             + "JOIN t_llm_span s ON s.trace_id = t.trace_id "
                             + "WHERE m.session_id = ?")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("join across 3 tables matches").isTrue();
                assertThat(rs.getString("msg_trace")).isEqualTo(traceId);
                assertThat(rs.getString("trc_trace")).isEqualTo(traceId);
                assertThat(rs.getString("span_trace")).isEqualTo(traceId);
                assertThat(rs.getString("kind")).isEqualTo("tool");
            }
        }
    }

    @Test
    @DisplayName("OBS-4 INV-3 — multiple traces in same user message share one root_trace_id")
    void sameUserMessageMultipleTracesShareRoot() throws Exception {
        // Simulates: user → main agent → spawns child → main agent later resumes for summary.
        // Both main-agent traces (and the child trace) share the same root_trace_id.
        String sessionId = UUID.randomUUID().toString();
        String mainTrace1 = UUID.randomUUID().toString(); // first main-agent trace
        String mainTrace2 = UUID.randomUUID().toString(); // resume after children done
        Instant t0 = Instant.parse("2026-05-03T10:00:00Z");

        seedSession(sessionId);
        // First main-agent trace: self as root → root_trace_id = mainTrace1
        insertTraceStubWithRoot(mainTrace1, mainTrace1, sessionId, t0);
        // Second main-agent trace within same user message: inherits root = mainTrace1
        insertTraceStubWithRoot(mainTrace2, mainTrace1, sessionId, t0.plusSeconds(30));

        // Both traces returned by the "tree by root" query
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT trace_id, root_trace_id FROM t_llm_trace "
                             + "WHERE root_trace_id = ? ORDER BY started_at")) {
            ps.setString(1, mainTrace1);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("trace_id")).isEqualTo(mainTrace1);
                assertThat(rs.getString("root_trace_id")).isEqualTo(mainTrace1);
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("trace_id")).isEqualTo(mainTrace2);
                assertThat(rs.getString("root_trace_id"))
                        .as("2nd main-agent trace inherits 1st trace's root (INV-3)")
                        .isEqualTo(mainTrace1);
                assertThat(rs.next()).as("exactly 2 traces under this root").isFalse();
            }
        }
    }

    @Test
    @DisplayName("session_message rows with NULL trace_id (legacy) coexist with new rows that have trace_id")
    void backwardCompatNullAndPopulatedRowsCoexist() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        String trace1 = UUID.randomUUID().toString();
        Instant now = Instant.parse("2026-05-02T10:00:00Z");

        seedSession(sessionId);
        // Row 0: legacy / pre-OBS-2 (trace_id = NULL)
        insertSessionMessage(sessionId, 0L, "user", null, now);
        // Row 1: live OBS-2 (trace_id populated)
        insertSessionMessage(sessionId, 1L, "user", trace1, now.plusSeconds(60));

        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT seq_no, trace_id FROM t_session_message "
                             + "WHERE session_id = ? ORDER BY seq_no")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("seq_no")).isEqualTo(0L);
                assertThat(rs.getString("trace_id")).as("legacy row preserves NULL").isNull();

                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("seq_no")).isEqualTo(1L);
                assertThat(rs.getString("trace_id")).isEqualTo(trace1);
            }
        }
    }

    // -----------------------------------------------------------------------
    // helpers — minimal session_message / trace / span insertion contract
    // -----------------------------------------------------------------------

    private void seedSession(String sessionId) throws Exception {
        // t_session has many NOT NULL columns; we use the minimal column set the FK
        // implied by t_session_message.session_id requires. The tests don't depend on
        // the full session shape; we just need the row to satisfy any FK to t_session.
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

    private void insertSessionMessage(String sessionId, long seqNo, String role,
                                       String traceId, Instant createdAt) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO t_session_message ("
                             + " session_id, seq_no, role, msg_type, message_type, "
                             + " content_json, trace_id, created_at"
                             + ") VALUES (?, ?, ?, 'normal', 'normal', '{}', ?, ?)")) {
            ps.setString(1, sessionId);
            ps.setLong(2, seqNo);
            ps.setString(3, role);
            if (traceId == null) ps.setNull(4, Types.VARCHAR);
            else ps.setString(4, traceId);
            ps.setTimestamp(5, Timestamp.from(createdAt));
            ps.executeUpdate();
        }
    }

    private void insertTraceStub(String traceId, String sessionId, Instant startedAt) throws Exception {
        // OBS-4: root_trace_id is NOT NULL (V46) — set to trace_id (self as root, matches V45 backfill).
        insertTraceStubWithRoot(traceId, traceId, sessionId, startedAt);
    }

    /** OBS-4: variant that sets explicit root_trace_id (for INV-3 scenarios). */
    private void insertTraceStubWithRoot(String traceId, String rootTraceId, String sessionId,
                                          Instant startedAt) throws Exception {
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
            ps.setString(2, rootTraceId);
            ps.setString(3, sessionId);
            ps.setTimestamp(4, Timestamp.from(startedAt));
            ps.executeUpdate();
        }
    }

    private void insertLlmSpan(String spanId, String traceId, String sessionId,
                                String kind, String name, Instant startedAt) throws Exception {
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
                             + " ?, ?, 0, false,"
                             + " 0, 0,"
                             + " 100, ?,"
                             + " 'live', now(), 'ok')")) {
            ps.setString(1, spanId);
            ps.setString(2, traceId);
            ps.setString(3, traceId); // parent = trace root
            ps.setString(4, sessionId);
            ps.setString(5, kind);
            ps.setString(6, name);
            ps.setTimestamp(7, Timestamp.from(startedAt));
            ps.executeUpdate();
        }
    }
}
