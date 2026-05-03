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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OBS-2 M3 r2 B-1 — verifies the GROUP BY shape used by
 * {@code TracesController.listTraces} to eliminate the N+1 LLM-span count.
 *
 * <p>The {@code countByTraceIdsAndKind} repository query expands to a single
 * {@code SELECT trace_id, count(*) FROM t_llm_span WHERE trace_id IN (...) AND kind=? GROUP BY trace_id}.
 * This IT runs the same SQL through raw JDBC against {@code EmbeddedPostgres} +
 * Flyway and checks: (a) one row per trace that has at least one span of the
 * given kind, (b) traces with zero spans of that kind are absent from the
 * result, (c) counts are correct.
 *
 * <p>Pattern mirrors {@code PgLlmTraceLifecycleIT} (no Spring boot required).
 */
@DisplayName("OBS-2 M3 r2 B-1 — GROUP BY count(t_llm_span) by trace_id + kind")
class LlmSpanCountByTraceIdIT {

    private static final String COUNT_SQL =
            "SELECT trace_id, count(*) FROM t_llm_span "
                    + "WHERE trace_id = ANY(?) AND kind = ? "
                    + "GROUP BY trace_id";

    private static EmbeddedPostgres pg;
    private static DataSource ds;

    @BeforeAll
    static void startPg() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        ds = pg.getPostgresDatabase();
        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .placeholders(Map.of(
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
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM t_session")) {
                ps.executeUpdate();
            }
        }
    }

    @Test
    @DisplayName("Single GROUP BY returns one row per trace_id with correct llm-kind counts")
    void groupByCounts() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        seedSession(sessionId);

        // trace A: 3 llm spans + 2 tool spans
        String traceA = UUID.randomUUID().toString();
        seedTrace(traceA, sessionId);
        for (int i = 0; i < 3; i++) {
            insertSpan(UUID.randomUUID().toString(), traceA, sessionId, "llm",
                    Instant.parse("2026-05-02T10:00:00Z").plusSeconds(i));
        }
        for (int i = 0; i < 2; i++) {
            insertSpan(UUID.randomUUID().toString(), traceA, sessionId, "tool",
                    Instant.parse("2026-05-02T10:00:10Z").plusSeconds(i));
        }

        // trace B: 1 llm span only
        String traceB = UUID.randomUUID().toString();
        seedTrace(traceB, sessionId);
        insertSpan(UUID.randomUUID().toString(), traceB, sessionId, "llm",
                Instant.parse("2026-05-02T10:01:00Z"));

        // trace C: 0 llm spans (only tool + event) → must NOT appear in the result
        String traceC = UUID.randomUUID().toString();
        seedTrace(traceC, sessionId);
        insertSpan(UUID.randomUUID().toString(), traceC, sessionId, "tool",
                Instant.parse("2026-05-02T10:02:00Z"));
        insertSpan(UUID.randomUUID().toString(), traceC, sessionId, "event",
                Instant.parse("2026-05-02T10:02:01Z"));

        Map<String, Long> counts = runCount(List.of(traceA, traceB, traceC), "llm");

        assertThat(counts).hasSize(2)
                .containsEntry(traceA, 3L)
                .containsEntry(traceB, 1L)
                .doesNotContainKey(traceC);
    }

    @Test
    @DisplayName("Empty traceIds returns empty result without error")
    void emptyTraceIds() throws Exception {
        // Defensive: production code skips the query when traceIds is empty, but the
        // SQL itself should still tolerate an empty array if invoked.
        Map<String, Long> counts = runCount(List.of(), "llm");
        assertThat(counts).isEmpty();
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private Map<String, Long> runCount(List<String> traceIds, String kind) throws Exception {
        Map<String, Long> result = new LinkedHashMap<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(COUNT_SQL)) {
            ps.setArray(1, c.createArrayOf("VARCHAR", traceIds.toArray()));
            ps.setString(2, kind);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString(1), rs.getLong(2));
                }
            }
        }
        return result;
    }

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

    private void seedTrace(String traceId, String sessionId) throws Exception {
        // OBS-4: root_trace_id NOT NULL after V46 — set self-as-root.
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO t_llm_trace ("
                             + " trace_id, root_trace_id, session_id, agent_name, root_name,"
                             + " status, started_at, total_input_tokens, total_output_tokens,"
                             + " total_duration_ms, tool_call_count, event_count,"
                             + " source, created_at"
                             + ") VALUES ("
                             + " ?, ?, ?, 'agent', 'agent',"
                             + " 'running', now(), 0, 0,"
                             + " 0, 0, 0,"
                             + " 'live', now())")) {
            ps.setString(1, traceId);
            ps.setString(2, traceId); // root_trace_id = self
            ps.setString(3, sessionId);
            ps.executeUpdate();
        }
    }

    private void insertSpan(String spanId, String traceId, String sessionId,
                             String kind, Instant startedAt) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO t_llm_span ("
                             + " span_id, trace_id, parent_span_id, session_id,"
                             + " kind, iteration_index, stream,"
                             + " input_tokens, output_tokens,"
                             + " latency_ms, started_at,"
                             + " source, created_at, blob_status"
                             + ") VALUES ("
                             + " ?, ?, ?, ?,"
                             + " ?, 0, false,"
                             + " 0, 0,"
                             + " 100, ?,"
                             + " 'live', now(), 'ok')")) {
            ps.setString(1, spanId);
            ps.setString(2, traceId);
            ps.setString(3, traceId);
            ps.setString(4, sessionId);
            ps.setString(5, kind);
            ps.setTimestamp(6, Timestamp.from(startedAt));
            ps.executeUpdate();
        }
    }

    @SuppressWarnings("unused")
    private static Map<String, Object> emptyMap() {
        return new HashMap<>();
    }
}
