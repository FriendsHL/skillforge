package com.skillforge.server.migration;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PersonalAppLibraryMigrationIT {

    private static EmbeddedPostgres postgres;
    private static Connection connection;
    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        connection = postgres.getPostgresDatabase().getConnection();
        jdbcTemplate = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
    }

    @AfterAll
    static void stopPostgres() throws SQLException, IOException {
        if (connection != null) connection.close();
        if (postgres != null) postgres.close();
    }

    @BeforeEach
    void createBaselineSchema() {
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS public.skillforge_try_parse_jsonb(TEXT)");
        jdbcTemplate.execute("DROP TABLE IF EXISTS t_personal_app_preference");
        jdbcTemplate.execute("DROP TABLE IF EXISTS t_chat_attachment");
        jdbcTemplate.execute("DROP TABLE IF EXISTS t_session_message");
        jdbcTemplate.execute("DROP TABLE IF EXISTS t_session");
        jdbcTemplate.execute("""
                CREATE TABLE t_session (
                    id VARCHAR(36) PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    origin VARCHAR(16) NOT NULL DEFAULT 'production'
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE t_session_message (
                    id BIGSERIAL PRIMARY KEY,
                    session_id VARCHAR(36) NOT NULL,
                    seq_no BIGINT NOT NULL,
                    role VARCHAR(16) NOT NULL,
                    content_json TEXT
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE t_chat_attachment (
                    id VARCHAR(36) PRIMARY KEY,
                    session_id VARCHAR(36) NOT NULL,
                    user_id BIGINT NOT NULL,
                    kind VARCHAR(16) NOT NULL,
                    mime_type VARCHAR(128) NOT NULL,
                    status VARCHAR(16) NOT NULL,
                    origin VARCHAR(24) NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    interactive_manifest_json TEXT,
                    CONSTRAINT fk_chat_attachment_session
                        FOREIGN KEY (session_id) REFERENCES t_session(id) ON DELETE CASCADE
                )
                """);
    }

    @Test
    void v176BackfillsInteractiveRefsRepairsPublishedStatusAndIsRerunnable() {
        insertSession("session-1", 1L, "production");
        insertAttachment("artifact-1", "session-1", 1L, "uploaded");
        insertAttachment("artifact-non-array", "session-1", 1L, "uploaded");
        insertMessage("session-1", 7L, "assistant", """
                [{"type":"interactive_artifact_ref","attachment_id":"artifact-1"}]
                """);
        insertMessage("session-1", 11L, "assistant", """
                [{"type":"interactive_artifact_ref","attachment_id":"artifact-1"}]
                """);
        insertMessage("session-1", 8L, "assistant", "{\"type\":\"interactive_artifact_ref\"}");
        insertMessage("session-1", 9L, "assistant", "\"not-an-array\"");
        insertMessage("session-1", 10L, "assistant", null);
        insertMessage("session-1", 12L, "assistant", "[{malformed-json]");

        runV176();
        runV176();

        assertThat(jdbcTemplate.queryForMap("""
                SELECT source_message_seq, status
                FROM t_chat_attachment WHERE id = 'artifact-1'
                """))
                .containsEntry("source_message_seq", 11L)
                .containsEntry("status", "published");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT source_message_seq FROM t_chat_attachment
                WHERE id = 'artifact-non-array'
                """, Long.class)).isNull();
    }

    @Test
    void v176EnforcesPreferenceUniquenessUserAndCascadeInvariants() {
        insertSession("session-1", 1L, "production");
        insertAttachment("artifact-1", "session-1", 1L, "published");
        runV176();

        jdbcTemplate.update("""
                INSERT INTO t_personal_app_preference
                    (user_id, attachment_id, favorite, last_opened_at)
                VALUES (1, 'artifact-1', TRUE, ?)
                """, OffsetDateTime.parse("2026-07-17T01:00:00Z"));

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO t_personal_app_preference (user_id, attachment_id, favorite)
                VALUES (1, 'artifact-1', FALSE)
                """)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO t_personal_app_preference (user_id, attachment_id, favorite)
                VALUES (0, 'artifact-1', FALSE)
                """)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO t_personal_app_preference (user_id, attachment_id, favorite)
                VALUES (1, 'missing', FALSE)
                """)).isInstanceOf(Exception.class);

        jdbcTemplate.update("DELETE FROM t_session WHERE id = 'session-1'");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_chat_attachment", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_personal_app_preference", Long.class)).isZero();
    }

    @Test
    void v176CreatesSourcePreferenceAndHotPathIndexes() {
        runV176();

        List<Map<String, Object>> indexes = jdbcTemplate.queryForList("""
                SELECT indexname, indexdef
                FROM pg_indexes
                WHERE schemaname = current_schema()
                  AND indexname IN (
                    'idx_chat_attachment_session_source_message',
                    'idx_chat_attachment_personal_apps_hot',
                    'idx_personal_app_preference_user_favorite',
                    'idx_personal_app_preference_user_recent')
                ORDER BY indexname
                """);

        assertThat(indexes).extracting(row -> row.get("indexname"))
                .containsExactly(
                        "idx_chat_attachment_personal_apps_hot",
                        "idx_chat_attachment_session_source_message",
                        "idx_personal_app_preference_user_favorite",
                        "idx_personal_app_preference_user_recent");
        assertThat(indexes.stream()
                .filter(row -> "idx_chat_attachment_personal_apps_hot".equals(row.get("indexname")))
                .findFirst().orElseThrow().get("indexdef").toString())
                .contains("WHERE", "agent_generated", "published", "interactive");
    }

    @Test
    void safeManifestParserRemainsAvailableOnFreshRuntimeConnection() throws SQLException {
        runV176();

        try (Connection runtimeConnection = postgres.getPostgresDatabase().getConnection()) {
            JdbcTemplate runtime = new JdbcTemplate(
                    new SingleConnectionDataSource(runtimeConnection, true));
            assertThat(runtime.queryForObject(
                    "SELECT public.skillforge_try_parse_jsonb(?)::TEXT",
                    String.class,
                    "{\"ok\":true}"))
                    .isEqualTo("{\"ok\": true}");
            assertThat(runtime.queryForObject(
                    "SELECT public.skillforge_try_parse_jsonb(?)::TEXT",
                    String.class,
                    "{malformed"))
                    .isNull();
        }
    }

    private static void insertSession(String id, long userId, String origin) {
        jdbcTemplate.update(
                "INSERT INTO t_session (id, user_id, origin) VALUES (?, ?, ?)",
                id, userId, origin);
    }

    private static void insertAttachment(
            String id, String sessionId, long userId, String status) {
        jdbcTemplate.update("""
                INSERT INTO t_chat_attachment (
                    id, session_id, user_id, kind, mime_type, status, origin,
                    interactive_manifest_json)
                VALUES (?, ?, ?, 'interactive', 'text/html', ?, 'agent_generated',
                    '{"schemaVersion":1,"title":"App","fallback":"Fallback",' ||
                    '"permissions":[],"network":[],"initialData":{},"stateSchema":{}}')
                """, id, sessionId, userId, status);
    }

    private static void insertMessage(
            String sessionId, long seqNo, String role, String contentJson) {
        jdbcTemplate.update("""
                INSERT INTO t_session_message (session_id, seq_no, role, content_json)
                VALUES (?, ?, ?, ?)
                """, sessionId, seqNo, role, contentJson);
    }

    private static void runV176() {
        ScriptUtils.executeSqlScript(connection,
                new ClassPathResource("db/migration/V176__personal_app_library.sql"));
    }
}
