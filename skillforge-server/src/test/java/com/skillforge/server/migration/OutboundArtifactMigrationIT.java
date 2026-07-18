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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboundArtifactMigrationIT {

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
        jdbcTemplate.execute("DROP TABLE IF EXISTS t_chat_attachment");
        jdbcTemplate.execute("DROP TABLE IF EXISTS t_agent");
        jdbcTemplate.execute("""
                CREATE TABLE t_chat_attachment (
                    id VARCHAR(36) PRIMARY KEY,
                    session_id VARCHAR(36) NOT NULL,
                    seq_no BIGINT,
                    kind VARCHAR(16) NOT NULL DEFAULT 'pdf',
                    mime_type VARCHAR(128) NOT NULL DEFAULT 'application/pdf',
                    status VARCHAR(16) NOT NULL DEFAULT 'uploaded',
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE t_agent (
                    id BIGSERIAL PRIMARY KEY,
                    name VARCHAR(255) NOT NULL,
                    agent_type VARCHAR(32) NOT NULL DEFAULT 'user',
                    status VARCHAR(32) NOT NULL DEFAULT 'active',
                    tool_ids TEXT,
                    updated_at TIMESTAMPTZ
                )
                """);
    }

    @Test
    void v169AddsArtifactColumnsConstraintsAndIndexes() {
        jdbcTemplate.update("""
                INSERT INTO t_chat_attachment (id, session_id, status)
                VALUES ('attachment-1', 'session-1', 'bound')
                """);

        runV169();

        List<String> columns = jdbcTemplate.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 't_chat_attachment'
                  AND column_name IN ('origin', 'source_tool_use_id', 'sha256', 'caption')
                ORDER BY column_name
                """, String.class);
        assertThat(columns).containsExactly("caption", "origin", "sha256", "source_tool_use_id");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT origin FROM t_chat_attachment WHERE id = 'attachment-1'", String.class))
                .isEqualTo("user_upload");

        List<String> indexes = jdbcTemplate.queryForList("""
                SELECT indexname FROM pg_indexes WHERE tablename = 't_chat_attachment'
                """, String.class);
        assertThat(indexes).contains(
                "uq_chat_attachment_session_tool_use",
                "idx_chat_attachment_origin_status_created");

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO t_chat_attachment (id, session_id, status)
                VALUES ('attachment-publishing', 'session-1', 'publishing')
                """)).isInstanceOf(Exception.class);
    }

    @Test
    void v170AddsArtifactCleanupLeaseStatusesAfterV169() {
        runV169();
        runV170();

        jdbcTemplate.update("""
                INSERT INTO t_chat_attachment (id, session_id, status) VALUES
                    ('attachment-publishing', 'session-1', 'publishing'),
                    ('attachment-deleting', 'session-1', 'deleting')
                """);

        assertThat(jdbcTemplate.queryForList(
                "SELECT status FROM t_chat_attachment ORDER BY status", String.class))
                .containsExactly("deleting", "publishing");
    }

    @Test
    void unrestrictedValuesStayUnrestrictedAndExplicitAllowlistAppendsExactlyOnce() {
        jdbcTemplate.update("""
                INSERT INTO t_agent (name, tool_ids) VALUES
                    ('Main Assistant', NULL),
                    ('Main Assistant', '   '),
                    ('Main Assistant', '[]'),
                    ('Main Assistant', '["Bash"]'),
                    ('Other Agent', '["Bash"]')
                """);

        runV169();
        runV169();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_agent WHERE name = 'Main Assistant' AND tool_ids IS NULL",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_agent WHERE name = 'Main Assistant' AND tool_ids = '   '",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_agent WHERE name = 'Main Assistant' AND tool_ids = '[]'",
                Integer.class)).isEqualTo(1);

        String explicitTools = jdbcTemplate.queryForObject("""
                SELECT tool_ids FROM t_agent
                WHERE name = 'Main Assistant' AND tool_ids LIKE '%Bash%'
                """, String.class);
        assertThat(explicitTools).isEqualTo("[\"Bash\", \"PublishChatArtifact\"]");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT tool_ids FROM t_agent WHERE name = 'Other Agent'", String.class))
                .isEqualTo("[\"Bash\"]");
    }

    @Test
    void v172GrantsArtifactPublishingToActiveUserAgentsOnlyAndIsIdempotent() {
        jdbcTemplate.update("""
                INSERT INTO t_agent (name, agent_type, status, tool_ids) VALUES
                    ('Research Agent', 'user', 'active', '["WebSearch","Write"]'),
                    ('Already Enabled', 'user', 'active', '["Write","PublishChatArtifact"]'),
                    ('Unrestricted Null', 'user', 'active', NULL),
                    ('Unrestricted Empty', 'user', 'active', '[]'),
                    ('Inactive User', 'user', 'inactive', '["Write"]'),
                    ('System Agent', 'system', 'active', '["Write"]')
                """);

        runV172();
        runV172();

        assertThat(toolsFor("Research Agent"))
                .isEqualTo("[\"WebSearch\", \"Write\", \"PublishChatArtifact\"]");
        assertThat(toolsFor("Already Enabled"))
                .isEqualTo("[\"Write\",\"PublishChatArtifact\"]");
        assertThat(toolsFor("Unrestricted Null")).isNull();
        assertThat(toolsFor("Unrestricted Empty")).isEqualTo("[]");
        assertThat(toolsFor("Inactive User")).isEqualTo("[\"Write\"]");
        assertThat(toolsFor("System Agent")).isEqualTo("[\"Write\"]");
    }

    @Test
    void v173RequiresManifestOnlyForInteractiveHtml() {
        runV173();

        jdbcTemplate.update("""
                INSERT INTO t_chat_attachment (id, session_id, kind, mime_type, status)
                VALUES ('pdf-1', 'session-1', 'pdf', 'application/pdf', 'uploaded')
                """);
        jdbcTemplate.update("""
                INSERT INTO t_chat_attachment (
                    id, session_id, kind, mime_type, status, interactive_manifest_json)
                VALUES ('app-1', 'session-1', 'interactive', 'text/html', 'uploaded', '{}')
                """);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO t_chat_attachment (id, session_id, kind, mime_type, status)
                VALUES ('app-missing', 'session-1', 'interactive', 'text/html', 'uploaded')
                """)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO t_chat_attachment (
                    id, session_id, kind, mime_type, status, interactive_manifest_json)
                VALUES ('pdf-manifest', 'session-1', 'pdf', 'application/pdf', 'uploaded', '{}')
                """)).isInstanceOf(Exception.class);
    }

    @Test
    void v174GrantsInteractivePublisherOnlyToExplicitActiveUserAllowlists() {
        jdbcTemplate.update("""
                INSERT INTO t_agent (name, agent_type, status, tool_ids) VALUES
                    ('Research Agent', 'user', 'active', '["Write"]'),
                    ('Unrestricted', 'user', 'active', '[]'),
                    ('Inactive', 'user', 'inactive', '["Write"]'),
                    ('System', 'system', 'active', '["Write"]')
                """);

        runV174();
        runV174();

        assertThat(toolsFor("Research Agent"))
                .isEqualTo("[\"Write\", \"PublishInteractiveArtifact\"]");
        assertThat(toolsFor("Unrestricted")).isEqualTo("[]");
        assertThat(toolsFor("Inactive")).isEqualTo("[\"Write\"]");
        assertThat(toolsFor("System")).isEqualTo("[\"Write\"]");
    }

    private static String toolsFor(String name) {
        return jdbcTemplate.queryForObject(
                "SELECT tool_ids FROM t_agent WHERE name = ?", String.class, name);
    }

    private static void runV169() {
        ScriptUtils.executeSqlScript(
                connection,
                new ClassPathResource("db/migration/V169__outbound_artifact_foundation.sql"));
    }

    private static void runV170() {
        ScriptUtils.executeSqlScript(
                connection,
                new ClassPathResource("db/migration/V170__artifact_cleanup_lease_statuses.sql"));
    }

    private static void runV172() {
        ScriptUtils.executeSqlScript(
                connection,
                new ClassPathResource("db/migration/V172__grant_artifact_publish_to_user_agents.sql"));
    }

    private static void runV173() {
        ScriptUtils.executeSqlScript(
                connection,
                new ClassPathResource("db/migration/V173__interactive_artifact_manifest.sql"));
    }

    private static void runV174() {
        ScriptUtils.executeSqlScript(
                connection,
                new ClassPathResource("db/migration/V174__grant_interactive_artifact_publish.sql"));
    }
}
