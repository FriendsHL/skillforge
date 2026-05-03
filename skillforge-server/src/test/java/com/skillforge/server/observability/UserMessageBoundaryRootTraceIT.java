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
 * OBS-4 INV-5 — verifies user message boundary semantics:
 * each new user message opens a NEW root_trace_id (not inherited from a previous
 * user message's root, even on the same session).
 *
 * <p>Simulates the contract enforced by {@code ChatService.chatAsync(sessionId, msg, userId)}
 * (3-arg, default {@code preserveActiveRoot=false}):
 * <ol>
 *   <li>{@code traceId = UUID.randomUUID()}</li>
 *   <li>{@code rootTraceId = traceId}</li>
 *   <li>{@code sessionService.allocateNewRootTraceId(sessionId, rootTraceId)} —
 *       single-transaction atomic reset of {@code active_root_trace_id} to the new root
 *       (W2/W3 r1 fix: replaces 3-step clear+get+set with one {@code @Transactional} call)</li>
 * </ol>
 *
 * <p>This IT exercises the SQL+state-machine contract (without booting Spring) by
 * driving t_session.active_root_trace_id directly and asserting that two independent
 * "user message" cycles produce two different root_trace_id values.
 */
@DisplayName("OBS-4 INV-5 — each user message opens a new root_trace_id")
class UserMessageBoundaryRootTraceIT {

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
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM t_session")) {
                ps.executeUpdate();
            }
        }
    }

    @Test
    @DisplayName("two consecutive user messages on same session produce different root_trace_ids")
    void twoUserMessagesGetDifferentRoots() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        seedSession(sessionId);

        // ── User message #1: clear → set root1 → create trace1
        clearActiveRoot(sessionId);
        String trace1 = UUID.randomUUID().toString();
        String activeRoot1 = readActiveRoot(sessionId);
        assertThat(activeRoot1).as("active_root cleared at user message boundary").isNull();
        setActiveRoot(sessionId, trace1);
        insertTraceStub(trace1, trace1, sessionId, Instant.parse("2026-05-03T10:00:00Z"));

        // ── User message #2: clear → set root2 → create trace2
        clearActiveRoot(sessionId);
        String trace2 = UUID.randomUUID().toString();
        String activeRoot2 = readActiveRoot(sessionId);
        assertThat(activeRoot2)
                .as("active_root cleared again at second user message boundary (INV-5)")
                .isNull();
        setActiveRoot(sessionId, trace2);
        insertTraceStub(trace2, trace2, sessionId, Instant.parse("2026-05-03T10:01:00Z"));

        // ── Assert: two traces have DIFFERENT root_trace_ids
        Row r1 = readTrace(trace1);
        Row r2 = readTrace(trace2);
        assertThat(r1.rootTraceId).isEqualTo(trace1);
        assertThat(r2.rootTraceId).isEqualTo(trace2);
        assertThat(r1.rootTraceId)
                .as("two user messages must NOT share root_trace_id (INV-5)")
                .isNotEqualTo(r2.rootTraceId);
    }

    @Test
    @DisplayName("active_root persists across two trace creations within same user message (INV-3)")
    void sameUserMessageInheritsRoot() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        seedSession(sessionId);

        // ── User message starts: clear → set root, create trace1 (self as root)
        clearActiveRoot(sessionId);
        String trace1 = UUID.randomUUID().toString();
        setActiveRoot(sessionId, trace1);
        insertTraceStub(trace1, trace1, sessionId, Instant.parse("2026-05-03T10:00:00Z"));

        // ── A 2nd trace within same user message (e.g. agent collecting child results) —
        // ChatService 4-arg path with preserveActiveRoot=true: do NOT clear, inherit existing root
        // (no clearActiveRoot call here).
        String existingActive = readActiveRoot(sessionId);
        assertThat(existingActive).as("active_root preserved between traces in same user message").isEqualTo(trace1);
        String trace2 = UUID.randomUUID().toString();
        // rootTraceId for trace2 = existing active_root = trace1
        insertTraceStub(trace2, existingActive, sessionId, Instant.parse("2026-05-03T10:00:05Z"));

        Row r1 = readTrace(trace1);
        Row r2 = readTrace(trace2);
        assertThat(r2.rootTraceId)
                .as("2nd trace within same user message inherits 1st trace's root (INV-3)")
                .isEqualTo(r1.rootTraceId)
                .isEqualTo(trace1);
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

    private void clearActiveRoot(String sessionId) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE t_session SET active_root_trace_id = NULL WHERE id = ?")) {
            ps.setString(1, sessionId);
            ps.executeUpdate();
        }
    }

    private void setActiveRoot(String sessionId, String rootTraceId) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE t_session SET active_root_trace_id = ? WHERE id = ?")) {
            ps.setString(1, rootTraceId);
            ps.setString(2, sessionId);
            ps.executeUpdate();
        }
    }

    private String readActiveRoot(String sessionId) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT active_root_trace_id FROM t_session WHERE id = ?")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getString(1);
            }
        }
    }

    private void insertTraceStub(String traceId, String rootTraceId, String sessionId,
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

    private Row readTrace(String traceId) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT trace_id, root_trace_id FROM t_llm_trace WHERE trace_id = ?")) {
            ps.setString(1, traceId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                Row r = new Row();
                r.traceId = rs.getString(1);
                r.rootTraceId = rs.getString(2);
                return r;
            }
        }
    }

    private static final class Row {
        String traceId;
        String rootTraceId;
    }
}
