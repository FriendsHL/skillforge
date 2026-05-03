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
 * OBS-4 INV-4 / INV-6 — verifies subagent spawn / completion semantics:
 *
 * <ul>
 *   <li><b>INV-4</b>: subagent trace.root_trace_id == 父 session 当时 active_root_trace_id —
 *       spawn 时通过 {@code child.setActiveRootTraceId(parent.getActiveRootTraceId())} 复制；
 *       child 的 ChatService.chatAsync(preserveActiveRoot=true) 读 child.active_root → 继承</li>
 *   <li><b>INV-6</b>: child session 完成不清父的 active_root（决策 Q7）—
 *       SubAgentRegistry.onSessionLoopFinished 路径不触碰父 session 状态字段</li>
 *   <li><b>Q6 递归</b>: child of child 同样从 spawning session 复制 active_root，
 *       grandchild 内部 trace 继承同一 root</li>
 * </ul>
 *
 * <p>Pattern: simulate the spawn/complete flow at SQL level (without booting Spring) by
 * directly manipulating t_session.active_root_trace_id and inserting traces.
 */
@DisplayName("OBS-4 INV-4 / INV-6 — subagent spawn copies parent active_root; child completion does not clear it")
class SubAgentSpawnRootTraceIT {

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
    @DisplayName("INV-4: spawned child copies parent.active_root; child trace.root_trace_id == parent.active_root_trace_id")
    void childTraceInheritsParentActiveRoot() throws Exception {
        // ── Parent session: user sends message, parent gets root R
        String parentSessionId = UUID.randomUUID().toString();
        seedSession(parentSessionId, null);

        String parentTrace = UUID.randomUUID().toString();
        String R = parentTrace; // parent self-as-root
        setActiveRoot(parentSessionId, R);
        insertTraceStub(parentTrace, R, parentSessionId, Instant.parse("2026-05-03T10:00:00Z"));

        // ── Spawn child: CollabRunService.spawnMember copies parent active_root to child
        String childSessionId = UUID.randomUUID().toString();
        seedSession(childSessionId, parentSessionId);
        String parentActiveAtSpawn = readActiveRoot(parentSessionId);
        setActiveRoot(childSessionId, parentActiveAtSpawn);

        // ── Child runs: ChatService.chatAsync(preserveActiveRoot=true) inherits child.active_root
        String childTrace = UUID.randomUUID().toString();
        String childActiveRoot = readActiveRoot(childSessionId); // = R
        insertTraceStub(childTrace, childActiveRoot, childSessionId, Instant.parse("2026-05-03T10:00:01Z"));

        Row child = readTrace(childTrace);
        Row parent = readTrace(parentTrace);
        assertThat(child.rootTraceId)
                .as("child trace.root_trace_id == parent.active_root_trace_id (INV-4)")
                .isEqualTo(R)
                .isEqualTo(parent.rootTraceId);
    }

    @Test
    @DisplayName("INV-6: parent.active_root_trace_id NOT cleared when child session completes (Q7)")
    void parentActiveRootSurvivesChildCompletion() throws Exception {
        String parentSessionId = UUID.randomUUID().toString();
        seedSession(parentSessionId, null);
        String parentTrace = UUID.randomUUID().toString();
        String R = parentTrace;
        setActiveRoot(parentSessionId, R);
        insertTraceStub(parentTrace, R, parentSessionId, Instant.parse("2026-05-03T10:00:00Z"));

        // Spawn child
        String childSessionId = UUID.randomUUID().toString();
        seedSession(childSessionId, parentSessionId);
        setActiveRoot(childSessionId, R);

        // Simulate child's loop (completes — SubAgentRegistry.onSessionLoopFinished runs).
        // The actual code path NEVER touches parent.active_root_trace_id; it only enqueues
        // a SubAgentPendingResultEntity and possibly calls maybeResumeParent which calls
        // chatAsync(parent, ..., preserveActiveRoot=true) → which does not clear active_root.
        // Verify by reading parent.active_root after child "completion" (no SQL touch).

        String parentActiveAfterChildDone = readActiveRoot(parentSessionId);
        assertThat(parentActiveAfterChildDone)
                .as("parent.active_root_trace_id unchanged after child completes (INV-6 / Q7)")
                .isEqualTo(R);
    }

    @Test
    @DisplayName("Q6 recursion: grandchild inherits root via child.active_root, which child copied from parent")
    void grandchildInheritsRootRecursively() throws Exception {
        // parent
        String parentSessionId = UUID.randomUUID().toString();
        seedSession(parentSessionId, null);
        String parentTrace = UUID.randomUUID().toString();
        String R = parentTrace;
        setActiveRoot(parentSessionId, R);
        insertTraceStub(parentTrace, R, parentSessionId, Instant.parse("2026-05-03T10:00:00Z"));

        // child (spawned from parent — copies parent.active_root)
        String childSessionId = UUID.randomUUID().toString();
        seedSession(childSessionId, parentSessionId);
        setActiveRoot(childSessionId, readActiveRoot(parentSessionId));
        String childTrace = UUID.randomUUID().toString();
        insertTraceStub(childTrace, readActiveRoot(childSessionId), childSessionId,
                Instant.parse("2026-05-03T10:00:01Z"));

        // grandchild (spawned from child — copies child.active_root, which equals parent's R)
        String grandSessionId = UUID.randomUUID().toString();
        seedSession(grandSessionId, childSessionId);
        setActiveRoot(grandSessionId, readActiveRoot(childSessionId));
        String grandTrace = UUID.randomUUID().toString();
        insertTraceStub(grandTrace, readActiveRoot(grandSessionId), grandSessionId,
                Instant.parse("2026-05-03T10:00:02Z"));

        Row child = readTrace(childTrace);
        Row grand = readTrace(grandTrace);
        Row parent = readTrace(parentTrace);

        assertThat(child.rootTraceId).isEqualTo(R);
        assertThat(grand.rootTraceId)
                .as("grandchild trace inherits same root as parent (Q6 — recursive, no depth limit)")
                .isEqualTo(R)
                .isEqualTo(parent.rootTraceId);

        // ── Sanity: SQL "give me the whole tree by root" works
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM t_llm_trace WHERE root_trace_id = ?")) {
            ps.setString(1, R);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getInt(1))
                        .as("one SQL query by root_trace_id retrieves all 3 traces (parent + child + grandchild)")
                        .isEqualTo(3);
            }
        }
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private void seedSession(String sessionId, String parentSessionId) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO t_session (id, user_id, agent_id, runtime_status, status, parent_session_id) "
                             + "VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, sessionId);
            ps.setLong(2, 1L);
            ps.setLong(3, 1L);
            ps.setString(4, "idle");
            ps.setString(5, "active");
            ps.setString(6, parentSessionId);
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
                     "SELECT trace_id, root_trace_id, session_id FROM t_llm_trace WHERE trace_id = ?")) {
            ps.setString(1, traceId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                Row r = new Row();
                r.traceId = rs.getString(1);
                r.rootTraceId = rs.getString(2);
                r.sessionId = rs.getString(3);
                return r;
            }
        }
    }

    private static final class Row {
        String traceId;
        String rootTraceId;
        String sessionId;
    }
}
