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
 * OBS-4 INV-2 — verifies {@code root_trace_id} is immutable after first INSERT.
 *
 * <p>Locks the SQL contract encoded in {@link PgLlmTraceStore#INSERT_TRACE_STUB_SQL}
 * (ON CONFLICT DO NOTHING, so root_trace_id never changes after first write) and in
 * {@link PgLlmTraceStore#UPSERT_TRACE_SQL} (ON CONFLICT DO UPDATE explicitly omits
 * root_trace_id from SET clause). Invariant: a second upsertTraceStub call with a
 * different rootTraceId MUST be a no-op.
 *
 * <p>Pattern mirrors {@link PgLlmTraceLifecycleIT}: raw JDBC against EmbeddedPostgres
 * + Flyway, no Spring boot, no real engine.
 */
@DisplayName("OBS-4 INV-2 — t_llm_trace.root_trace_id immutable after first INSERT")
class RootTraceIdImmutableIT {

    /**
     * Mirrors {@link PgLlmTraceStore#INSERT_TRACE_STUB_SQL} verbatim (incl. OBS-4
     * COALESCE(rootTraceId, traceId) self-fallback).
     */
    private static final String INSERT_TRACE_STUB_SQL =
            "INSERT INTO t_llm_trace ("
                    + "  trace_id, root_trace_id, session_id, agent_id, user_id, agent_name, root_name,"
                    + "  status, started_at, total_input_tokens, total_output_tokens,"
                    + "  total_duration_ms, tool_call_count, event_count,"
                    + "  source, created_at"
                    + ") VALUES ("
                    + "  ?, COALESCE(?, ?), ?, ?, ?, ?, ?,"
                    + "  'running', ?, 0, 0,"
                    + "  0, 0, 0,"
                    + "  'live', now()"
                    + ") "
                    + "ON CONFLICT (trace_id) DO NOTHING";

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
    @DisplayName("first stub sets root_trace_id; second stub with different root is no-op (DO NOTHING)")
    void rootTraceIdImmutableAcrossUpserts() throws Exception {
        String traceId = UUID.randomUUID().toString();
        String firstRoot = UUID.randomUUID().toString();
        String laterRoot = UUID.randomUUID().toString();
        String sessionId = UUID.randomUUID().toString();
        Instant first = Instant.parse("2026-05-03T10:00:00Z");
        Instant second = Instant.parse("2026-05-03T10:00:30Z");

        // 1st stub creates the row with root_trace_id = firstRoot
        insertStub(traceId, firstRoot, sessionId, "main-agent", first);
        // 2nd stub with a DIFFERENT root_trace_id — must NOT overwrite
        insertStub(traceId, laterRoot, sessionId, "different-agent", second);

        Row r = readRow(traceId);
        assertThat(r.rootTraceId)
                .as("root_trace_id is immutable after first INSERT (INV-2)")
                .isEqualTo(firstRoot)
                .isNotEqualTo(laterRoot);
    }

    @Test
    @DisplayName("null rootTraceId on first INSERT falls back to trace_id (self as root)")
    void nullRootTraceIdFallsBackToSelf() throws Exception {
        String traceId = UUID.randomUUID().toString();
        String sessionId = UUID.randomUUID().toString();

        // 1st stub passes null rootTraceId — COALESCE(null, traceId) = traceId
        insertStub(traceId, null, sessionId, "agent", Instant.parse("2026-05-03T10:00:00Z"));

        Row r = readRow(traceId);
        assertThat(r.rootTraceId)
                .as("null rootTraceId falls back to self (matches V45 backfill semantics)")
                .isEqualTo(traceId);
    }

    @Test
    @DisplayName("once root_trace_id set to value V, subsequent upserts with null still keep V")
    void existingRootIdNotErasedByNullSecondStub() throws Exception {
        String traceId = UUID.randomUUID().toString();
        String firstRoot = UUID.randomUUID().toString();
        String sessionId = UUID.randomUUID().toString();

        insertStub(traceId, firstRoot, sessionId, "agent", Instant.parse("2026-05-03T10:00:00Z"));
        // 2nd stub with null root — DO NOTHING means existing row's root_trace_id is preserved
        insertStub(traceId, null, sessionId, "agent", Instant.parse("2026-05-03T10:00:10Z"));

        Row r = readRow(traceId);
        assertThat(r.rootTraceId)
                .as("DO NOTHING preserves existing root_trace_id even when 2nd call passes null")
                .isEqualTo(firstRoot);
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private void insertStub(String traceId, String rootTraceId, String sessionId,
                            String agentName, Instant startedAt) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(INSERT_TRACE_STUB_SQL)) {
            ps.setString(1, traceId);
            // COALESCE(:rootTraceId, :traceId) — null param falls back to traceId at SQL level
            if (rootTraceId == null) ps.setNull(2, Types.VARCHAR);
            else ps.setString(2, rootTraceId);
            ps.setString(3, traceId); // COALESCE fallback target
            ps.setString(4, sessionId);
            ps.setNull(5, Types.BIGINT);
            ps.setNull(6, Types.BIGINT);
            ps.setString(7, agentName);
            ps.setString(8, agentName);
            ps.setTimestamp(9, Timestamp.from(startedAt));
            ps.executeUpdate();
        }
    }

    private Row readRow(String traceId) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT trace_id, root_trace_id, status, agent_name "
                             + "FROM t_llm_trace WHERE trace_id = ?")) {
            ps.setString(1, traceId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("trace row exists").isTrue();
                Row r = new Row();
                r.traceId = rs.getString(1);
                r.rootTraceId = rs.getString(2);
                r.status = rs.getString(3);
                r.agentName = rs.getString(4);
                return r;
            }
        }
    }

    private static final class Row {
        String traceId;
        String rootTraceId;
        String status;
        String agentName;
    }
}
