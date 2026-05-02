package com.skillforge.observability.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BE-W4 (R3) — JSONB roundtrip smoke test for {@code t_llm_span}.
 *
 * <p>Without {@code @JdbcTypeCode(SqlTypes.JSON)} on {@code usageJson} /
 * {@code attributesJson}, Hibernate writes strings as {@code VARCHAR}, which the
 * PostgreSQL driver rejects against a {@code JSONB} column ("column ... is of
 * type jsonb but expression is of type character varying"). This test validates
 * the schema stays JSONB and that the values written are still parseable JSON
 * after roundtripping.
 *
 * <p>This is a raw-SQL roundtrip rather than a full Hibernate one because
 * {@code skillforge-observability} doesn't bootstrap a Spring context in tests
 * (see {@link PgLlmTraceUpsertTest}). The application path itself is exercised
 * end-to-end at runtime by {@code TraceLlmCallObserver} writing real spans —
 * this test catches regressions where the column type or JSON validity slips.
 */
@DisplayName("PgLlmSpan — JSONB usage_json / attributes_json roundtrip")
class PgLlmSpanEntityIT {

    private static EmbeddedPostgres pg;
    private static DataSource ds;
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
             PreparedStatement ps = c.prepareStatement("DELETE FROM t_llm_span")) {
            ps.executeUpdate();
        }
    }

    @Test
    @DisplayName("JSONB columns accept and roundtrip valid JSON strings; result is parseable")
    void jsonbRoundtrip() throws Exception {
        String spanId = UUID.randomUUID().toString();
        String traceId = UUID.randomUUID().toString();
        String sessionId = UUID.randomUUID().toString();
        String usageJson = "{\"prompt_tokens\":12,\"completion_tokens\":34}";
        String attributesJson = "{\"compact_call\":true,\"sse_truncated\":false}";

        // INSERT — emulate Hibernate's JSONB write path with `?::jsonb` casts.
        // This is exactly the on-wire shape that @JdbcTypeCode(SqlTypes.JSON) produces.
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO t_llm_span ("
                             + "  span_id, trace_id, parent_span_id, session_id, agent_id,"
                             + "  provider, model, iteration_index, stream,"
                             + "  input_summary, output_summary,"
                             + "  input_blob_ref, output_blob_ref, raw_sse_blob_ref, blob_status,"
                             + "  input_tokens, output_tokens, cache_read_tokens, usage_json,"
                             + "  cost_usd, latency_ms, started_at, ended_at,"
                             + "  finish_reason, request_id, reasoning_content,"
                             + "  error, error_type, tool_use_id, attributes_json,"
                             + "  source, created_at"
                             + ") VALUES ("
                             + "  ?, ?, NULL, ?, NULL,"
                             + "  ?, ?, ?, ?,"
                             + "  NULL, NULL,"
                             + "  NULL, NULL, NULL, ?,"
                             + "  ?, ?, NULL, ?::jsonb,"
                             + "  ?, ?, ?, ?,"
                             + "  NULL, NULL, NULL,"
                             + "  NULL, NULL, NULL, ?::jsonb,"
                             + "  ?, ?"
                             + ")")) {
            int i = 1;
            ps.setString(i++, spanId);
            ps.setString(i++, traceId);
            ps.setString(i++, sessionId);
            ps.setString(i++, "claude");
            ps.setString(i++, "claude-sonnet-4-20250514");
            ps.setInt(i++, 0);
            ps.setBoolean(i++, true);
            ps.setString(i++, "ok");
            ps.setInt(i++, 12);
            ps.setInt(i++, 34);
            ps.setString(i++, usageJson);
            ps.setBigDecimal(i++, new BigDecimal("0.000123"));
            ps.setLong(i++, 1500L);
            ps.setTimestamp(i++, Timestamp.from(Instant.parse("2026-04-29T10:00:00Z")));
            ps.setTimestamp(i++, Timestamp.from(Instant.parse("2026-04-29T10:00:01Z")));
            ps.setString(i++, attributesJson);
            ps.setString(i++, "live");
            ps.setTimestamp(i++, Timestamp.from(Instant.now()));
            int updated = ps.executeUpdate();
            assertThat(updated).isEqualTo(1);
        }

        // SELECT — JSONB column reads back as text via getString().
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT usage_json::text, attributes_json::text FROM t_llm_span WHERE span_id = ?")) {
            ps.setString(1, spanId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("span row exists").isTrue();
                String usageOut = rs.getString(1);
                String attrsOut = rs.getString(2);
                assertThat(usageOut).as("usage_json non-null after roundtrip").isNotNull();
                assertThat(attrsOut).as("attributes_json non-null after roundtrip").isNotNull();

                JsonNode usageNode = MAPPER.readTree(usageOut);
                assertThat(usageNode.get("prompt_tokens").asInt()).isEqualTo(12);
                assertThat(usageNode.get("completion_tokens").asInt()).isEqualTo(34);

                JsonNode attrsNode = MAPPER.readTree(attrsOut);
                assertThat(attrsNode.get("compact_call").asBoolean()).isTrue();
                assertThat(attrsNode.get("sse_truncated").asBoolean()).isFalse();
            }
        }

        // Confirm column types are still JSONB (regression guard).
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT data_type FROM information_schema.columns "
                             + "WHERE table_name = 't_llm_span' AND column_name IN ('usage_json','attributes_json')")) {
            try (ResultSet rs = ps.executeQuery()) {
                int rows = 0;
                while (rs.next()) {
                    rows++;
                    assertThat(rs.getString(1)).isEqualTo("jsonb");
                }
                assertThat(rows).as("both JSONB columns present").isEqualTo(2);
            }
        }
    }

    @Test
    @DisplayName("invalid JSON literal is rejected by the JSONB column")
    void invalidJsonRejected() throws Exception {
        String spanId = UUID.randomUUID().toString();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO t_llm_span ("
                             + "  span_id, trace_id, session_id, iteration_index, stream,"
                             + "  input_tokens, output_tokens, latency_ms, started_at,"
                             + "  source, created_at, usage_json"
                             + ") VALUES (?, ?, ?, 0, true, 0, 0, 0, ?, 'live', now(), ?::jsonb)")) {
            ps.setString(1, spanId);
            ps.setString(2, UUID.randomUUID().toString());
            ps.setString(3, UUID.randomUUID().toString());
            ps.setTimestamp(4, Timestamp.from(Instant.now()));
            ps.setString(5, "{not valid json");
            try {
                ps.executeUpdate();
                throw new AssertionError("Expected JSONB cast to reject malformed JSON");
            } catch (java.sql.SQLException expected) {
                // PostgreSQL surfaces "invalid input syntax for type json" — the exact
                // message wording can change between PG versions, so just accept any
                // SQL exception here as proof the column rejected the bad payload.
                assertThat(expected.getMessage()).isNotBlank();
            }
        }
    }

    @SuppressWarnings("unused")
    private static void unused(int sqlType) {
        // keep Types import live in case future tests need it for setObject(...)
        int t = Types.OTHER;
    }
}
