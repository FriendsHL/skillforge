package com.skillforge.observability.store;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan §3.1 R2-B3 — verifies the {@code ON CONFLICT (trace_id) DO UPDATE} contract
 * encoded in {@link PgLlmTraceStore}'s native SQL:
 *
 * <ul>
 *   <li>token columns accumulate via {@code t_llm_trace.col + EXCLUDED.col}</li>
 *   <li>{@code ended_at = GREATEST(t_llm_trace.ended_at, EXCLUDED.ended_at)}</li>
 *   <li>{@code started_at} / {@code root_name} are NOT overwritten on conflict
 *       (the {@code DO UPDATE SET} clause omits them)</li>
 * </ul>
 */
@DisplayName("PgLlmTraceStore — upsert trace SQL semantics")
class PgLlmTraceUpsertTest {

    /** Mirrors {@code PgLlmTraceStore.UPSERT_TRACE_SQL} verbatim. */
    private static final String UPSERT_TRACE_SQL =
            "INSERT INTO t_llm_trace (\n"
                    + "  trace_id, session_id, agent_id, user_id, root_name,\n"
                    + "  started_at, ended_at, total_input_tokens, total_output_tokens, total_cost_usd,\n"
                    + "  source, created_at\n"
                    + ") VALUES (\n"
                    + "  ?, ?, ?, ?, ?,\n"
                    + "  ?, ?, ?, ?, ?,\n"
                    + "  ?, now()\n"
                    + ")\n"
                    + "ON CONFLICT (trace_id) DO UPDATE SET\n"
                    + "  ended_at            = GREATEST(t_llm_trace.ended_at, EXCLUDED.ended_at),\n"
                    + "  total_input_tokens  = t_llm_trace.total_input_tokens  + EXCLUDED.total_input_tokens,\n"
                    + "  total_output_tokens = t_llm_trace.total_output_tokens + EXCLUDED.total_output_tokens,\n"
                    + "  total_cost_usd      = COALESCE(t_llm_trace.total_cost_usd, 0) + COALESCE(EXCLUDED.total_cost_usd, 0)";

    private static EmbeddedPostgres pg;
    private static DataSource ds;

    @BeforeAll
    static void startPg() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        ds = pg.getPostgresDatabase();
        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .placeholders(java.util.Map.of("etl_mode", "off"))
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
    @DisplayName("two writes accumulate tokens, advance ended_at via GREATEST, preserve started_at/root_name")
    void twoWritesAccumulate() throws Exception {
        String traceId = UUID.randomUUID().toString();
        String sessionId = UUID.randomUUID().toString();
        Instant started1 = Instant.parse("2026-04-29T10:00:00Z");
        Instant ended1 = Instant.parse("2026-04-29T10:00:05Z");
        Instant started2 = Instant.parse("2026-04-29T10:00:10Z"); // should NOT overwrite started_at
        Instant ended2 = Instant.parse("2026-04-29T10:00:12Z");

        upsert(traceId, sessionId, "AGENT_LOOP", started1, ended1, 100, 50, new BigDecimal("0.10"));
        upsert(traceId, sessionId, "OVERRIDE_NAME", started2, ended2, 200, 100, new BigDecimal("0.05"));

        Row r = readRow(traceId);
        assertThat(r.totalInputTokens).as("input tokens accumulate").isEqualTo(300);
        assertThat(r.totalOutputTokens).as("output tokens accumulate").isEqualTo(150);
        assertThat(r.totalCostUsd).isNotNull();
        assertThat(r.totalCostUsd.compareTo(new BigDecimal("0.15"))).as("cost accumulates").isZero();
        assertThat(r.startedAt.truncatedTo(ChronoUnit.MILLIS))
                .as("started_at must NOT be overwritten on conflict")
                .isEqualTo(started1.truncatedTo(ChronoUnit.MILLIS));
        assertThat(r.endedAt.truncatedTo(ChronoUnit.MILLIS))
                .as("ended_at advances to GREATEST")
                .isEqualTo(ended2.truncatedTo(ChronoUnit.MILLIS));
        assertThat(r.rootName)
                .as("root_name must NOT be overwritten on conflict")
                .isEqualTo("AGENT_LOOP");
    }

    @Test
    @DisplayName("out-of-order arrival: later-ended write first, earlier-ended write second → ended_at stays at later")
    void outOfOrderArrivalGreatestStable() throws Exception {
        String traceId = UUID.randomUUID().toString();
        String sessionId = UUID.randomUUID().toString();
        Instant t1 = Instant.parse("2026-04-29T10:00:00Z");
        Instant t2 = Instant.parse("2026-04-29T10:00:30Z"); // later

        // First arrival: ended_at = t2 (later)
        upsert(traceId, sessionId, "AGENT_LOOP", t1, t2, 10, 5, BigDecimal.ZERO);
        // Second arrival: ended_at = t1 (earlier) — GREATEST must keep t2
        upsert(traceId, sessionId, "AGENT_LOOP", t1, t1, 20, 10, BigDecimal.ZERO);

        Row r = readRow(traceId);
        assertThat(r.endedAt.truncatedTo(ChronoUnit.MILLIS))
                .as("GREATEST should hold the later timestamp regardless of arrival order")
                .isEqualTo(t2.truncatedTo(ChronoUnit.MILLIS));
        assertThat(r.totalInputTokens).isEqualTo(30);
        assertThat(r.totalOutputTokens).isEqualTo(15);
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private void upsert(String traceId, String sessionId, String rootName,
                        Instant startedAt, Instant endedAt,
                        int inDelta, int outDelta, BigDecimal costDelta) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(UPSERT_TRACE_SQL)) {
            ps.setString(1, traceId);
            ps.setString(2, sessionId);
            ps.setNull(3, Types.BIGINT);
            ps.setNull(4, Types.BIGINT);
            ps.setString(5, rootName);
            ps.setTimestamp(6, Timestamp.from(startedAt));
            ps.setTimestamp(7, Timestamp.from(endedAt));
            ps.setInt(8, inDelta);
            ps.setInt(9, outDelta);
            ps.setBigDecimal(10, costDelta);
            ps.setString(11, "live");
            ps.executeUpdate();
        }
    }

    private Row readRow(String traceId) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT root_name, started_at, ended_at, total_input_tokens, "
                             + "total_output_tokens, total_cost_usd FROM t_llm_trace WHERE trace_id = ?")) {
            ps.setString(1, traceId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("trace row exists").isTrue();
                Row r = new Row();
                r.rootName = rs.getString(1);
                r.startedAt = rs.getTimestamp(2).toInstant();
                r.endedAt = rs.getTimestamp(3).toInstant();
                r.totalInputTokens = rs.getInt(4);
                r.totalOutputTokens = rs.getInt(5);
                r.totalCostUsd = rs.getBigDecimal(6);
                return r;
            }
        }
    }

    private static final class Row {
        String rootName;
        Instant startedAt;
        Instant endedAt;
        int totalInputTokens;
        int totalOutputTokens;
        BigDecimal totalCostUsd;
    }
}
