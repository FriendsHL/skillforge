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
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OBS-2 §M2 — verifies the SQL contracts encoded in
 * {@code R__migrate_legacy_trace_span.sql}:
 *
 * <ul>
 *   <li>Section 1: AGENT_LOOP → t_llm_trace lifecycle/aggregate fields update,
 *       only touches source='legacy' AND status='running' rows (idempotent).</li>
 *   <li>Section 2: TOOL_CALL → t_llm_span (kind='tool', source='legacy') with
 *       full field mapping + ON CONFLICT idempotency.</li>
 *   <li>Section 3: ASK_USER / INSTALL_CONFIRM / COMPACT / AGENT_CONFIRM →
 *       t_llm_span (kind='event', event_type=LOWER(span_type)).</li>
 *   <li>NOT EXISTS guard: pre-existing live span with same (session_id, kind,
 *       iteration_index, ±1s) blocks legacy duplicate insertion.</li>
 *   <li>Mode flip via Flyway placeholder: {@code etl_trace_span_mode=off}
 *       takes the skip branch; {@code etl_trace_span_mode=flyway} runs ETL.</li>
 * </ul>
 *
 * <p>Pattern: mirrors the inner SQL of each section verbatim (without the
 * {@code DO $$ IF '${etl_trace_span_mode}' = 'flyway'} guard) and runs it via
 * JDBC, so we can exercise idempotency / NOT EXISTS guards without needing
 * Flyway to compute fresh hashes between calls. The full DO block (with mode
 * substitution) is also exercised end-to-end in {@link #fullDoBlock_modeOffSkipsETL()}
 * and {@link #fullDoBlock_modeFlywayMigrates()}.
 */
@DisplayName("R__migrate_legacy_trace_span — OBS-2 M2 ETL semantics")
class PgLlmTraceSpanMigrationIT {

    /** Mirrors Section 1 of R__migrate_legacy_trace_span.sql verbatim. */
    private static final String SECTION_1_UPDATE_SQL =
            "UPDATE t_llm_trace lt "
                    + "SET status            = CASE WHEN ts.success THEN 'ok' ELSE 'error' END, "
                    + "    error             = ts.error, "
                    + "    total_duration_ms = ts.duration_ms, "
                    + "    agent_name        = COALESCE(lt.agent_name, ts.name), "
                    + "    tool_call_count   = ( "
                    + "        SELECT count(*) FROM t_trace_span c "
                    + "        WHERE c.parent_span_id = ts.id "
                    + "          AND c.span_type      = 'TOOL_CALL' "
                    + "    ), "
                    + "    event_count       = ( "
                    + "        SELECT count(*) FROM t_trace_span c "
                    + "        WHERE c.parent_span_id = ts.id "
                    + "          AND c.span_type IN ('ASK_USER','INSTALL_CONFIRM','COMPACT','AGENT_CONFIRM') "
                    + "    ) "
                    + "FROM t_trace_span ts "
                    + "WHERE ts.span_type = 'AGENT_LOOP' "
                    + "  AND ts.id        = lt.trace_id "
                    + "  AND lt.source    = 'legacy' "
                    + "  AND lt.status    = 'running'";

    /** Mirrors Section 2 of R__migrate_legacy_trace_span.sql verbatim. */
    private static final String SECTION_2_INSERT_TOOL_SQL =
            "INSERT INTO t_llm_span ( "
                    + "  span_id, trace_id, parent_span_id, session_id, agent_id, "
                    + "  kind, name, tool_use_id, "
                    + "  input_summary, output_summary, blob_status, "
                    + "  iteration_index, latency_ms, started_at, ended_at, "
                    + "  error, source, created_at "
                    + ") "
                    + "SELECT "
                    + "    ts.id, ts.parent_span_id, ts.parent_span_id, ts.session_id, s.agent_id, "
                    + "    'tool', ts.name, ts.tool_use_id, "
                    + "    ts.input, ts.output, 'legacy', "
                    + "    ts.iteration_index, ts.duration_ms, ts.start_time, ts.end_time, "
                    + "    ts.error, 'legacy', ts.start_time "
                    + "FROM t_trace_span ts "
                    + "LEFT JOIN t_session s ON s.id = ts.session_id "
                    + "WHERE ts.span_type     = 'TOOL_CALL' "
                    + "  AND ts.start_time IS NOT NULL "
                    + "  AND ts.parent_span_id IS NOT NULL "
                    + "  AND NOT EXISTS ( "
                    + "      SELECT 1 FROM t_llm_span existing "
                    + "      WHERE existing.session_id      = ts.session_id "
                    + "        AND existing.kind            = 'tool' "
                    + "        AND existing.source          = 'live' "
                    + "        AND existing.iteration_index = ts.iteration_index "
                    + "        AND ABS(EXTRACT(EPOCH FROM (existing.started_at - ts.start_time))) < 1 "
                    + "  ) "
                    + "ON CONFLICT (trace_id, span_id) DO NOTHING";

    /** Mirrors Section 3 of R__migrate_legacy_trace_span.sql verbatim. */
    private static final String SECTION_3_INSERT_EVENT_SQL =
            "INSERT INTO t_llm_span ( "
                    + "  span_id, trace_id, parent_span_id, session_id, agent_id, "
                    + "  kind, event_type, name, tool_use_id, "
                    + "  input_summary, output_summary, blob_status, "
                    + "  iteration_index, latency_ms, started_at, ended_at, "
                    + "  error, source, created_at "
                    + ") "
                    + "SELECT "
                    + "    ts.id, ts.parent_span_id, ts.parent_span_id, ts.session_id, s.agent_id, "
                    + "    'event', LOWER(ts.span_type), ts.name, ts.tool_use_id, "
                    + "    ts.input, ts.output, 'legacy', "
                    + "    ts.iteration_index, ts.duration_ms, ts.start_time, ts.end_time, "
                    + "    ts.error, 'legacy', ts.start_time "
                    + "FROM t_trace_span ts "
                    + "LEFT JOIN t_session s ON s.id = ts.session_id "
                    + "WHERE ts.span_type IN ('ASK_USER','INSTALL_CONFIRM','COMPACT','AGENT_CONFIRM') "
                    + "  AND ts.start_time      IS NOT NULL "
                    + "  AND ts.parent_span_id  IS NOT NULL "
                    + "  AND NOT EXISTS ( "
                    + "      SELECT 1 FROM t_llm_span existing "
                    + "      WHERE existing.session_id      = ts.session_id "
                    + "        AND existing.kind            = 'event' "
                    + "        AND existing.source          = 'live' "
                    + "        AND existing.event_type      = LOWER(ts.span_type) "
                    + "        AND existing.iteration_index = ts.iteration_index "
                    + "        AND ABS(EXTRACT(EPOCH FROM (existing.started_at - ts.start_time))) < 1 "
                    + "  ) "
                    + "ON CONFLICT (trace_id, span_id) DO NOTHING";

    /** Full DO $$ block; "__MODE__" is replaced at test time to simulate placeholder. */
    private static final String FULL_DO_BLOCK_TEMPLATE =
            "DO $$ "
                    + "BEGIN "
                    + "    IF '__MODE__' = 'flyway' THEN "
                    + "        " + SECTION_1_UPDATE_SQL + "; "
                    + "        " + SECTION_2_INSERT_TOOL_SQL + "; "
                    + "        " + SECTION_3_INSERT_EVENT_SQL + "; "
                    + "        RAISE NOTICE 'OBS-2 M2 ETL: imported (test, mode=flyway)'; "
                    + "    ELSE "
                    + "        RAISE NOTICE 'OBS-2 M2 ETL: skipped (test, mode=__MODE__)'; "
                    + "    END IF; "
                    + "END $$";

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
        // The observability module Flyway path only owns t_llm_trace / t_llm_span.
        // R__migrate_legacy_trace_span reads from t_trace_span + t_session, which live
        // in the server module's V1__init.sql. Create lightweight test stubs here so
        // the ETL SQL has its source tables. Columns mirror only what the ETL touches
        // (server's V1 has additional columns we don't need for SQL-semantic tests).
        try (Connection c = ds.getConnection()) {
            execUpdate(c,
                    "CREATE TABLE IF NOT EXISTS t_session ("
                            + "  id VARCHAR(36) PRIMARY KEY,"
                            + "  user_id BIGINT NOT NULL,"
                            + "  agent_id BIGINT NOT NULL"
                            + ")");
            execUpdate(c,
                    "CREATE TABLE IF NOT EXISTS t_trace_span ("
                            + "  id VARCHAR(36) PRIMARY KEY,"
                            + "  session_id VARCHAR(36) NOT NULL,"
                            + "  parent_span_id VARCHAR(36),"
                            + "  span_type VARCHAR(16) NOT NULL,"
                            + "  name VARCHAR(256),"
                            + "  input TEXT,"
                            + "  output TEXT,"
                            + "  start_time TIMESTAMPTZ,"
                            + "  end_time TIMESTAMPTZ,"
                            + "  duration_ms BIGINT NOT NULL DEFAULT 0,"
                            + "  iteration_index INTEGER NOT NULL DEFAULT 0,"
                            + "  success BOOLEAN NOT NULL DEFAULT FALSE,"
                            + "  error TEXT,"
                            + "  tool_use_id VARCHAR(64)"
                            + ")");
        }
    }

    @AfterAll
    static void stopPg() throws Exception {
        if (pg != null) pg.close();
    }

    @BeforeEach
    void clean() throws Exception {
        try (Connection c = ds.getConnection()) {
            execUpdate(c, "DELETE FROM t_llm_span");
            execUpdate(c, "DELETE FROM t_llm_trace");
            execUpdate(c, "DELETE FROM t_trace_span");
            execUpdate(c, "DELETE FROM t_session");
        }
    }

    // -----------------------------------------------------------------------
    // Section 1
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Section 1: legacy t_llm_trace row gets status/error/duration/agent_name + tool/event counts from AGENT_LOOP")
    void section1_updatesLegacyTraceFromAgentLoop() throws Exception {
        long agentId = 42L;
        String sessionId = UUID.randomUUID().toString();
        String traceId = UUID.randomUUID().toString();
        Instant startedAt = Instant.parse("2026-04-29T10:00:00Z");

        insertSession(sessionId, agentId, 7L);
        insertTraceSpan(traceId, sessionId, /*parent*/ null, "AGENT_LOOP", "main-agent",
                /*input*/ null, /*output*/ null, startedAt,
                /*duration*/ 5000L, /*iteration*/ 0,
                /*success*/ true, /*error*/ null, /*toolUseId*/ null);
        // 2 TOOL_CALL children
        insertTraceSpan(UUID.randomUUID().toString(), sessionId, traceId, "TOOL_CALL", "Bash",
                "{\"cmd\":\"ls\"}", "ok", startedAt.plusMillis(100), 50L, 1, true, null, "tool_use_a");
        insertTraceSpan(UUID.randomUUID().toString(), sessionId, traceId, "TOOL_CALL", "Read",
                "{\"path\":\"/x\"}", "data", startedAt.plusMillis(300), 80L, 2, true, null, "tool_use_b");
        // 1 ASK_USER + 1 COMPACT events
        insertTraceSpan(UUID.randomUUID().toString(), sessionId, traceId, "ASK_USER", "ask_user",
                "{\"q\":\"?\"}", "waiting_user:c1", startedAt.plusMillis(1000), 0L, 1, true, null, null);
        insertTraceSpan(UUID.randomUUID().toString(), sessionId, traceId, "COMPACT", "compact_context",
                null, "compacted", startedAt.plusMillis(2000), 200L, 3, true, null, null);

        // Pre-existing legacy trace stub (mimics R__migrate_legacy_llm_call having run).
        insertLegacyTraceStub(traceId, sessionId, agentId, startedAt);

        runUpdate(SECTION_1_UPDATE_SQL);

        TraceRow r = readTraceRow(traceId);
        assertThat(r.status).as("AGENT_LOOP success=true → status='ok'").isEqualTo("ok");
        assertThat(r.error).isNull();
        assertThat(r.totalDurationMs).isEqualTo(5000L);
        assertThat(r.agentName).as("agent_name copies from t_trace_span.name when null").isEqualTo("main-agent");
        assertThat(r.toolCallCount).isEqualTo(2);
        assertThat(r.eventCount).as("ASK_USER + COMPACT counted as events").isEqualTo(2);
    }

    @Test
    @DisplayName("Section 1: live t_llm_trace rows are NOT touched (source filter)")
    void section1_skipsLiveTraceRows() throws Exception {
        long agentId = 1L;
        String sessionId = UUID.randomUUID().toString();
        String traceId = UUID.randomUUID().toString();
        Instant startedAt = Instant.parse("2026-04-29T10:00:00Z");

        insertSession(sessionId, agentId, 1L);
        insertTraceSpan(traceId, sessionId, null, "AGENT_LOOP", "name-from-trace-span",
                null, null, startedAt, 9999L, 0, false, "boom", null);
        insertLiveTraceStubRunning(traceId, sessionId, agentId, "live-name", startedAt);

        runUpdate(SECTION_1_UPDATE_SQL);

        TraceRow r = readTraceRow(traceId);
        assertThat(r.status).as("live row stays 'running'; only legacy rows are migrated").isEqualTo("running");
        assertThat(r.agentName).as("live agent_name preserved").isEqualTo("live-name");
        assertThat(r.totalDurationMs).as("live duration not overwritten").isEqualTo(0L);
    }

    @Test
    @DisplayName("Section 1: re-run is no-op (status='running' guard prevents re-update)")
    void section1_idempotentRerun() throws Exception {
        long agentId = 1L;
        String sessionId = UUID.randomUUID().toString();
        String traceId = UUID.randomUUID().toString();
        Instant startedAt = Instant.parse("2026-04-29T10:00:00Z");

        insertSession(sessionId, agentId, 1L);
        insertTraceSpan(traceId, sessionId, null, "AGENT_LOOP", "agent-x",
                null, null, startedAt, 1234L, 0, true, null, null);
        insertLegacyTraceStub(traceId, sessionId, agentId, startedAt);

        runUpdate(SECTION_1_UPDATE_SQL);
        // Now the legacy row is status='ok'; the second pass must NOT touch it
        // even if the t_trace_span row is mutated to 'error'.
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE t_trace_span SET success=false, error='post-finalize-mutation' WHERE id=?")) {
            ps.setString(1, traceId);
            ps.executeUpdate();
        }
        runUpdate(SECTION_1_UPDATE_SQL);

        TraceRow r = readTraceRow(traceId);
        assertThat(r.status).as("status frozen at 'ok' from first run").isEqualTo("ok");
        assertThat(r.error).as("error not overwritten on re-run").isNull();
    }

    // -----------------------------------------------------------------------
    // Section 2 — TOOL_CALL → kind='tool'
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Section 2: TOOL_CALL → t_llm_span (kind='tool') with full field mapping")
    void section2_insertsToolCallAsToolKind() throws Exception {
        long agentId = 5L;
        String sessionId = UUID.randomUUID().toString();
        String traceId = UUID.randomUUID().toString();
        String toolSpanId = UUID.randomUUID().toString();
        Instant t0 = Instant.parse("2026-04-29T10:00:00Z");

        insertSession(sessionId, agentId, 1L);
        insertTraceSpan(traceId, sessionId, null, "AGENT_LOOP", "main", null, null, t0, 0L, 0, true, null, null);
        insertTraceSpan(toolSpanId, sessionId, traceId, "TOOL_CALL", "Bash",
                "{\"cmd\":\"ls\"}", "stdout-payload", t0.plusMillis(100), 250L, 7, true, null, "use-id-1");

        runUpdate(SECTION_2_INSERT_TOOL_SQL);

        SpanRow s = readSpanRow(toolSpanId);
        assertThat(s.kind).isEqualTo("tool");
        assertThat(s.eventType).as("kind='tool' has no event_type").isNull();
        assertThat(s.name).isEqualTo("Bash");
        assertThat(s.toolUseId).isEqualTo("use-id-1");
        assertThat(s.traceId).isEqualTo(traceId);
        assertThat(s.parentSpanId).isEqualTo(traceId);
        assertThat(s.sessionId).isEqualTo(sessionId);
        assertThat(s.agentId).isEqualTo(agentId);
        assertThat(s.iterationIndex).isEqualTo(7);
        assertThat(s.latencyMs).isEqualTo(250L);
        assertThat(s.inputSummary).isEqualTo("{\"cmd\":\"ls\"}");
        assertThat(s.outputSummary).isEqualTo("stdout-payload");
        assertThat(s.blobStatus).isEqualTo("legacy");
        assertThat(s.source).isEqualTo("legacy");
    }

    @Test
    @DisplayName("Section 2: re-run is no-op (ON CONFLICT (trace_id, span_id) DO NOTHING)")
    void section2_idempotentRerun() throws Exception {
        seedSimpleToolCall();
        runUpdate(SECTION_2_INSERT_TOOL_SQL);
        long firstCount = countSpansByKind("tool");
        runUpdate(SECTION_2_INSERT_TOOL_SQL);
        long secondCount = countSpansByKind("tool");

        assertThat(firstCount).isEqualTo(1L);
        assertThat(secondCount).as("second run inserts 0 new tool spans").isEqualTo(firstCount);
    }

    @Test
    @DisplayName("Section 2 NOT EXISTS guard: pre-existing live tool span (same session+iteration ±1s) blocks legacy insert")
    void section2_notExistsGuardSkipsLiveDuplicate() throws Exception {
        long agentId = 5L;
        String sessionId = UUID.randomUUID().toString();
        String traceId = UUID.randomUUID().toString();
        String legacySpanId = UUID.randomUUID().toString();
        Instant t0 = Instant.parse("2026-04-29T10:00:00Z");

        insertSession(sessionId, agentId, 1L);
        insertTraceSpan(traceId, sessionId, null, "AGENT_LOOP", "m", null, null, t0, 0L, 0, true, null, null);
        insertTraceSpan(legacySpanId, sessionId, traceId, "TOOL_CALL", "Bash",
                "{}", "ok", t0.plusMillis(100), 50L, 3, true, null, "tu-1");

        // Pre-existing live span: same session, kind='tool', iteration=3, started 0.5s away.
        insertLiveSpan(UUID.randomUUID().toString(), traceId, sessionId, "tool", null,
                /*iteration*/ 3, t0.plusMillis(600));

        runUpdate(SECTION_2_INSERT_TOOL_SQL);

        long total = countSpansByKind("tool");
        long legacy = countLegacyToolSpans();
        assertThat(total).as("only the live row remains").isEqualTo(1L);
        assertThat(legacy).as("legacy duplicate of live row was skipped").isEqualTo(0L);
    }

    @Test
    @DisplayName("Section 2 NOT EXISTS guard: live span with different iteration_index does NOT block legacy insert")
    void section2_notExistsGuardOnlyMatchesSameIteration() throws Exception {
        long agentId = 5L;
        String sessionId = UUID.randomUUID().toString();
        String traceId = UUID.randomUUID().toString();
        String legacySpanId = UUID.randomUUID().toString();
        Instant t0 = Instant.parse("2026-04-29T10:00:00Z");

        insertSession(sessionId, agentId, 1L);
        insertTraceSpan(traceId, sessionId, null, "AGENT_LOOP", "m", null, null, t0, 0L, 0, true, null, null);
        insertTraceSpan(legacySpanId, sessionId, traceId, "TOOL_CALL", "Bash",
                "{}", "ok", t0.plusMillis(100), 50L, 3, true, null, "tu-1");

        // Live span at iteration=99 (different) within ±1s — must NOT block legacy.
        insertLiveSpan(UUID.randomUUID().toString(), traceId, sessionId, "tool", null,
                /*iteration*/ 99, t0.plusMillis(600));

        runUpdate(SECTION_2_INSERT_TOOL_SQL);

        assertThat(countSpansByKind("tool")).isEqualTo(2L);
        assertThat(countLegacyToolSpans()).isEqualTo(1L);
    }

    // -----------------------------------------------------------------------
    // Section 3 — events → kind='event'
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Section 3: 4 event types map to kind='event' with correct event_type")
    void section3_insertsAllFourEventTypes() throws Exception {
        long agentId = 1L;
        String sessionId = UUID.randomUUID().toString();
        String traceId = UUID.randomUUID().toString();
        Instant t0 = Instant.parse("2026-04-29T10:00:00Z");

        insertSession(sessionId, agentId, 1L);
        insertTraceSpan(traceId, sessionId, null, "AGENT_LOOP", "m", null, null, t0, 0L, 0, true, null, null);

        String askId = UUID.randomUUID().toString();
        String installId = UUID.randomUUID().toString();
        String compactId = UUID.randomUUID().toString();
        String confirmId = UUID.randomUUID().toString();
        insertTraceSpan(askId, sessionId, traceId, "ASK_USER", "ask_user",
                "{}", "waiting", t0.plusMillis(100), 0L, 1, true, null, null);
        insertTraceSpan(installId, sessionId, traceId, "INSTALL_CONFIRM", "install_confirmation",
                "{\"pkg\":\"x\"}", "ok", t0.plusMillis(200), 30L, 1, true, null, null);
        insertTraceSpan(compactId, sessionId, traceId, "COMPACT", "compact_context",
                null, "compacted", t0.plusMillis(400), 200L, 2, true, null, null);
        insertTraceSpan(confirmId, sessionId, traceId, "AGENT_CONFIRM", "subagent",
                "{}", "approved", t0.plusMillis(700), 10L, 2, true, null, null);

        runUpdate(SECTION_3_INSERT_EVENT_SQL);

        assertThat(readSpanRow(askId).eventType).isEqualTo("ask_user");
        assertThat(readSpanRow(installId).eventType).isEqualTo("install_confirm");
        assertThat(readSpanRow(compactId).eventType).isEqualTo("compact");
        assertThat(readSpanRow(confirmId).eventType).isEqualTo("agent_confirm");
        assertThat(readSpanRow(askId).kind).isEqualTo("event");
        assertThat(readSpanRow(askId).source).isEqualTo("legacy");
        assertThat(readSpanRow(askId).blobStatus).isEqualTo("legacy");
    }

    @Test
    @DisplayName("Section 3: re-run is no-op (ON CONFLICT)")
    void section3_idempotentRerun() throws Exception {
        long agentId = 1L;
        String sessionId = UUID.randomUUID().toString();
        String traceId = UUID.randomUUID().toString();
        Instant t0 = Instant.parse("2026-04-29T10:00:00Z");
        insertSession(sessionId, agentId, 1L);
        insertTraceSpan(traceId, sessionId, null, "AGENT_LOOP", "m", null, null, t0, 0L, 0, true, null, null);
        insertTraceSpan(UUID.randomUUID().toString(), sessionId, traceId, "ASK_USER", "ask_user",
                "{}", "wait", t0.plusMillis(100), 0L, 1, true, null, null);

        runUpdate(SECTION_3_INSERT_EVENT_SQL);
        runUpdate(SECTION_3_INSERT_EVENT_SQL);

        assertThat(countSpansByKind("event")).isEqualTo(1L);
    }

    @Test
    @DisplayName("Section 3 NOT EXISTS guard: live event with same (session, event_type, iteration ±1s) blocks legacy")
    void section3_notExistsGuardMatchesEventType() throws Exception {
        long agentId = 1L;
        String sessionId = UUID.randomUUID().toString();
        String traceId = UUID.randomUUID().toString();
        Instant t0 = Instant.parse("2026-04-29T10:00:00Z");
        insertSession(sessionId, agentId, 1L);
        insertTraceSpan(traceId, sessionId, null, "AGENT_LOOP", "m", null, null, t0, 0L, 0, true, null, null);
        // Legacy ASK_USER at iteration=1 t0+100ms
        insertTraceSpan(UUID.randomUUID().toString(), sessionId, traceId, "ASK_USER", "ask_user",
                "{}", "wait", t0.plusMillis(100), 0L, 1, true, null, null);
        // Pre-existing live ASK_USER at iteration=1, ~0.5s offset → blocks
        insertLiveSpan(UUID.randomUUID().toString(), traceId, sessionId, "event", "ask_user", 1, t0.plusMillis(600));
        // Pre-existing live COMPACT at iteration=1 (different event_type → must not block ASK_USER blocker logic,
        // but in this fixture the ASK_USER live alone is enough; we keep it to confirm event_type matching).
        insertLiveSpan(UUID.randomUUID().toString(), traceId, sessionId, "event", "compact", 1, t0.plusMillis(900));

        runUpdate(SECTION_3_INSERT_EVENT_SQL);

        assertThat(countLegacyEventSpans()).as("legacy ASK_USER blocked by live ASK_USER").isEqualTo(0L);
    }

    // -----------------------------------------------------------------------
    // End-to-end: full DO $$ block with mode placeholder
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Full DO block with mode='off' takes the skip branch (no rows touched)")
    void fullDoBlock_modeOffSkipsETL() throws Exception {
        long agentId = 1L;
        String sessionId = UUID.randomUUID().toString();
        String traceId = UUID.randomUUID().toString();
        Instant t0 = Instant.parse("2026-04-29T10:00:00Z");
        insertSession(sessionId, agentId, 1L);
        insertTraceSpan(traceId, sessionId, null, "AGENT_LOOP", "m", null, null, t0, 1234L, 0, true, null, null);
        insertTraceSpan(UUID.randomUUID().toString(), sessionId, traceId, "TOOL_CALL", "Bash",
                "{}", "ok", t0.plusMillis(100), 50L, 1, true, null, "tu-1");
        insertLegacyTraceStub(traceId, sessionId, agentId, t0);

        runRawSql(FULL_DO_BLOCK_TEMPLATE.replace("__MODE__", "off"));

        TraceRow r = readTraceRow(traceId);
        assertThat(r.status).as("status untouched (mode=off)").isEqualTo("running");
        assertThat(r.totalDurationMs).isEqualTo(0L);
        assertThat(countSpansByKind("tool")).as("no tool spans inserted (mode=off)").isEqualTo(0L);
        assertThat(countSpansByKind("event")).as("no event spans inserted (mode=off)").isEqualTo(0L);
    }

    @Test
    @DisplayName("Full DO block with mode='flyway' migrates AGENT_LOOP + TOOL_CALL + events")
    void fullDoBlock_modeFlywayMigrates() throws Exception {
        long agentId = 1L;
        String sessionId = UUID.randomUUID().toString();
        String traceId = UUID.randomUUID().toString();
        Instant t0 = Instant.parse("2026-04-29T10:00:00Z");
        insertSession(sessionId, agentId, 1L);
        insertTraceSpan(traceId, sessionId, null, "AGENT_LOOP", "m", null, null, t0, 1234L, 0, true, null, null);
        insertTraceSpan(UUID.randomUUID().toString(), sessionId, traceId, "TOOL_CALL", "Bash",
                "{}", "ok", t0.plusMillis(100), 50L, 1, true, null, "tu-1");
        insertTraceSpan(UUID.randomUUID().toString(), sessionId, traceId, "ASK_USER", "ask_user",
                "{}", "wait", t0.plusMillis(200), 0L, 1, true, null, null);
        insertLegacyTraceStub(traceId, sessionId, agentId, t0);

        runRawSql(FULL_DO_BLOCK_TEMPLATE.replace("__MODE__", "flyway"));

        TraceRow r = readTraceRow(traceId);
        assertThat(r.status).isEqualTo("ok");
        assertThat(r.totalDurationMs).isEqualTo(1234L);
        assertThat(r.toolCallCount).isEqualTo(1);
        assertThat(r.eventCount).isEqualTo(1);
        assertThat(countSpansByKind("tool")).isEqualTo(1L);
        assertThat(countSpansByKind("event")).isEqualTo(1L);
    }

    // -----------------------------------------------------------------------
    // helpers — fixture inserts
    // -----------------------------------------------------------------------

    private void seedSimpleToolCall() throws Exception {
        long agentId = 5L;
        String sessionId = UUID.randomUUID().toString();
        String traceId = UUID.randomUUID().toString();
        Instant t0 = Instant.parse("2026-04-29T10:00:00Z");
        insertSession(sessionId, agentId, 1L);
        insertTraceSpan(traceId, sessionId, null, "AGENT_LOOP", "m", null, null, t0, 0L, 0, true, null, null);
        insertTraceSpan(UUID.randomUUID().toString(), sessionId, traceId, "TOOL_CALL", "Bash",
                "{}", "ok", t0.plusMillis(100), 50L, 1, true, null, "tu-1");
    }

    private void insertSession(String sessionId, long agentId, long userId) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO t_session (id, user_id, agent_id) VALUES (?, ?, ?)")) {
            ps.setString(1, sessionId);
            ps.setLong(2, userId);
            ps.setLong(3, agentId);
            ps.executeUpdate();
        }
    }

    /** Pre-existing legacy t_llm_trace row (status defaults to 'running' via V42). */
    private void insertLegacyTraceStub(String traceId, String sessionId, Long agentId, Instant startedAt)
            throws Exception {
        // OBS-4: root_trace_id NOT NULL after V46 — set self-as-root (matches V45 backfill).
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO t_llm_trace (trace_id, root_trace_id, session_id, agent_id, started_at, source) "
                             + "VALUES (?, ?, ?, ?, ?, 'legacy')")) {
            ps.setString(1, traceId);
            ps.setString(2, traceId); // root_trace_id = self
            ps.setString(3, sessionId);
            if (agentId == null) ps.setNull(4, Types.BIGINT); else ps.setLong(4, agentId);
            ps.setTimestamp(5, Timestamp.from(startedAt));
            ps.executeUpdate();
        }
    }

    /** Pre-existing live t_llm_trace row at status='running' with custom agent_name (Section 1 source-filter test). */
    private void insertLiveTraceStubRunning(String traceId, String sessionId, Long agentId,
                                            String agentName, Instant startedAt) throws Exception {
        // OBS-4: root_trace_id NOT NULL after V46 — set self-as-root.
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO t_llm_trace (trace_id, root_trace_id, session_id, agent_id, agent_name, "
                             + "started_at, source, status) "
                             + "VALUES (?, ?, ?, ?, ?, ?, 'live', 'running')")) {
            ps.setString(1, traceId);
            ps.setString(2, traceId); // root_trace_id = self
            ps.setString(3, sessionId);
            if (agentId == null) ps.setNull(4, Types.BIGINT); else ps.setLong(4, agentId);
            ps.setString(5, agentName);
            ps.setTimestamp(6, Timestamp.from(startedAt));
            ps.executeUpdate();
        }
    }

    private void insertTraceSpan(String id, String sessionId, String parentSpanId, String spanType,
                                 String name, String input, String output, Instant startTime,
                                 long durationMs, int iterationIndex, boolean success,
                                 String error, String toolUseId) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO t_trace_span (id, session_id, parent_span_id, span_type, name, "
                             + "input, output, start_time, end_time, duration_ms, iteration_index, "
                             + "success, error, tool_use_id) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, sessionId);
            if (parentSpanId == null) ps.setNull(3, Types.VARCHAR); else ps.setString(3, parentSpanId);
            ps.setString(4, spanType);
            ps.setString(5, name);
            if (input == null) ps.setNull(6, Types.VARCHAR); else ps.setString(6, input);
            if (output == null) ps.setNull(7, Types.VARCHAR); else ps.setString(7, output);
            ps.setTimestamp(8, Timestamp.from(startTime));
            ps.setTimestamp(9, Timestamp.from(startTime.plusMillis(durationMs)));
            ps.setLong(10, durationMs);
            ps.setInt(11, iterationIndex);
            ps.setBoolean(12, success);
            if (error == null) ps.setNull(13, Types.VARCHAR); else ps.setString(13, error);
            if (toolUseId == null) ps.setNull(14, Types.VARCHAR); else ps.setString(14, toolUseId);
            ps.executeUpdate();
        }
    }

    /** Insert a live t_llm_span (source='live') for NOT EXISTS guard tests. */
    private void insertLiveSpan(String spanId, String traceId, String sessionId, String kind,
                                String eventType, int iterationIndex, Instant startedAt) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO t_llm_span (span_id, trace_id, parent_span_id, session_id, "
                             + "kind, event_type, iteration_index, started_at, source) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'live')")) {
            ps.setString(1, spanId);
            ps.setString(2, traceId);
            ps.setString(3, traceId);
            ps.setString(4, sessionId);
            ps.setString(5, kind);
            if (eventType == null) ps.setNull(6, Types.VARCHAR); else ps.setString(6, eventType);
            ps.setInt(7, iterationIndex);
            ps.setTimestamp(8, Timestamp.from(startedAt));
            ps.executeUpdate();
        }
    }

    // -----------------------------------------------------------------------
    // helpers — runners + readers
    // -----------------------------------------------------------------------

    private void runUpdate(String sql) throws Exception {
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement()) {
            st.executeUpdate(sql);
        }
    }

    private void runRawSql(String sql) throws Exception {
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static void execUpdate(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement()) {
            st.executeUpdate(sql);
        }
    }

    private long countSpansByKind(String kind) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM t_llm_span WHERE kind = ?")) {
            ps.setString(1, kind);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long countLegacyToolSpans() throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM t_llm_span WHERE kind='tool' AND source='legacy'")) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long countLegacyEventSpans() throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM t_llm_span WHERE kind='event' AND source='legacy'")) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private TraceRow readTraceRow(String traceId) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT status, error, total_duration_ms, agent_name, "
                             + "tool_call_count, event_count, source "
                             + "FROM t_llm_trace WHERE trace_id = ?")) {
            ps.setString(1, traceId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("trace row exists").isTrue();
                TraceRow r = new TraceRow();
                r.status = rs.getString(1);
                r.error = rs.getString(2);
                r.totalDurationMs = rs.getLong(3);
                r.agentName = rs.getString(4);
                r.toolCallCount = rs.getInt(5);
                r.eventCount = rs.getInt(6);
                r.source = rs.getString(7);
                return r;
            }
        }
    }

    private SpanRow readSpanRow(String spanId) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT span_id, trace_id, parent_span_id, session_id, agent_id, "
                             + "kind, event_type, name, tool_use_id, "
                             + "input_summary, output_summary, blob_status, "
                             + "iteration_index, latency_ms, source "
                             + "FROM t_llm_span WHERE span_id = ?")) {
            ps.setString(1, spanId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("span row exists for spanId=" + spanId).isTrue();
                SpanRow s = new SpanRow();
                s.spanId = rs.getString(1);
                s.traceId = rs.getString(2);
                s.parentSpanId = rs.getString(3);
                s.sessionId = rs.getString(4);
                long aid = rs.getLong(5);
                s.agentId = rs.wasNull() ? null : aid;
                s.kind = rs.getString(6);
                s.eventType = rs.getString(7);
                s.name = rs.getString(8);
                s.toolUseId = rs.getString(9);
                s.inputSummary = rs.getString(10);
                s.outputSummary = rs.getString(11);
                s.blobStatus = rs.getString(12);
                s.iterationIndex = rs.getInt(13);
                s.latencyMs = rs.getLong(14);
                s.source = rs.getString(15);
                return s;
            }
        }
    }

    private static final class TraceRow {
        String status;
        String error;
        long totalDurationMs;
        String agentName;
        int toolCallCount;
        int eventCount;
        String source;
    }

    private static final class SpanRow {
        String spanId;
        String traceId;
        String parentSpanId;
        String sessionId;
        Long agentId;
        String kind;
        String eventType;
        String name;
        String toolUseId;
        String inputSummary;
        String outputSummary;
        String blobStatus;
        int iterationIndex;
        long latencyMs;
        String source;
    }
}
