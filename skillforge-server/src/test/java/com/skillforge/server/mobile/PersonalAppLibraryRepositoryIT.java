package com.skillforge.server.mobile;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PersonalAppLibraryRepositoryIT {

    private static final Instant BASE_TIME = Instant.parse("2026-07-17T00:00:00Z");
    private static final String TOKEN = "repository-it-device-token";

    private static EmbeddedPostgres postgres;
    private static Connection connection;
    private static JdbcTemplate jdbc;

    private CountingDataSource countingDataSource;
    private PersonalAppLibraryRepository repository;
    private PersonalAppLibraryService service;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        connection = postgres.getPostgresDatabase().getConnection();
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
    }

    @AfterAll
    static void stopPostgres() throws SQLException, IOException {
        if (connection != null) connection.close();
        if (postgres != null) postgres.close();
    }

    @BeforeEach
    void setUpSchema() {
        jdbc.execute("DROP TABLE IF EXISTS t_personal_app_preference");
        jdbc.execute("DROP TABLE IF EXISTS t_chat_attachment");
        jdbc.execute("DROP TABLE IF EXISTS t_session_message");
        jdbc.execute("DROP TABLE IF EXISTS t_session");
        jdbc.execute("DROP TABLE IF EXISTS t_agent");
        jdbc.execute("DROP FUNCTION IF EXISTS public.skillforge_try_parse_jsonb(TEXT)");
        jdbc.execute("CREATE TABLE t_agent (id BIGINT PRIMARY KEY, name VARCHAR(255) NOT NULL)");
        jdbc.execute("""
                CREATE TABLE t_session (
                    id VARCHAR(36) PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    agent_id BIGINT NOT NULL REFERENCES t_agent(id),
                    title VARCHAR(255),
                    parent_session_id VARCHAR(36),
                    origin VARCHAR(16) NOT NULL DEFAULT 'production'
                )
                """);
        jdbc.execute("""
                CREATE TABLE t_session_message (
                    id BIGSERIAL PRIMARY KEY,
                    session_id VARCHAR(36) NOT NULL,
                    seq_no BIGINT NOT NULL,
                    role VARCHAR(16) NOT NULL,
                    content_json TEXT
                )
                """);
        jdbc.execute("""
                CREATE TABLE t_chat_attachment (
                    id VARCHAR(36) PRIMARY KEY,
                    session_id VARCHAR(36) NOT NULL REFERENCES t_session(id) ON DELETE CASCADE,
                    user_id BIGINT NOT NULL,
                    kind VARCHAR(16) NOT NULL,
                    mime_type VARCHAR(128) NOT NULL,
                    filename VARCHAR(255) NOT NULL,
                    size_bytes BIGINT NOT NULL,
                    storage_path TEXT NOT NULL,
                    status VARCHAR(16) NOT NULL,
                    origin VARCHAR(24) NOT NULL,
                    caption VARCHAR(1000),
                    interactive_manifest_json TEXT,
                    created_at TIMESTAMPTZ NOT NULL
                )
                """);
        ScriptUtils.executeSqlScript(connection,
                new ClassPathResource("db/migration/V176__personal_app_library.sql"));

        countingDataSource = new CountingDataSource(
                new SingleConnectionDataSource(connection, true));
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(countingDataSource);
        repository = new PersonalAppLibraryRepository(named);
        ObjectMapper mapper = new ObjectMapper();
        service = new PersonalAppLibraryService(
                repository,
                mapper,
                new PersonalAppCursorCodec(mapper),
                Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void paginatesMoreThanFiftyEqualTimestampRowsWithoutDuplicatesOrVisibilityLeaks() {
        List<String> visible = seedVisiblePopulation();
        seedInvisibleVariants();
        countingDataSource.reset();

        Set<String> actual = new LinkedHashSet<>();
        String cursor = null;
        int pageCount = 0;
        do {
            MobilePersonalAppListResponse page = service.list(1L, TOKEN,
                    new PersonalAppListRequest(
                            cursor, "17", "created", null, null, null, null, null));
            pageCount++;
            assertThat(page.items()).allMatch(item -> "available".equals(item.availability()));
            assertThat(page.items()).allMatch(item -> item.sourceMessageSeq() >= 0);
            for (MobilePersonalAppItemResponse item : page.items()) {
                assertThat(actual.add(item.artifactId())).isTrue();
            }
            cursor = page.nextCursor();
        } while (cursor != null);

        assertThat(pageCount).isEqualTo(4);
        assertThat(countingDataSource.preparedStatementCount()).isEqualTo(pageCount);
        assertThat(actual).containsExactlyElementsOf(
                visible.stream().sorted(Comparator.reverseOrder()).toList());
        assertThat(actual).hasSize(56);
        assertThat(PersonalAppLibraryRepository.LIST_SQL.toLowerCase())
                .doesNotContain("t_session_message", "content_json")
                .doesNotContain("attachment.filename ilike", "session.title, '') ilike");
    }

    @Test
    void filtersMatchWireFieldsAndPreferencesAreIdempotentAndGuarded() {
        seedAgentsAndSessions();
        insertApp("q-title", "session-main", 1L, "published", "interactive",
                "agent_generated", 1L, "ordinary.html", "ordinary summary",
                manifest("Zebra Title", "ordinary fallback"), BASE_TIME.plusSeconds(1));
        insertApp("q-caption", "session-main", 1L, "published", "interactive",
                "agent_generated", 2L, "ordinary-2.html", "Zebra Summary",
                manifest("Ordinary title", "ordinary fallback"), BASE_TIME.plusSeconds(2));
        insertApp("q-filename", "session-main", 1L, "published", "interactive",
                "agent_generated", 3L, "zebra-only.html", "ordinary summary",
                manifest("Ordinary title", "ordinary fallback"), BASE_TIME.plusSeconds(3));
        insertApp("q-session", "session-zebra", 1L, "published", "interactive",
                "agent_generated", 4L, "ordinary-3.html", "ordinary summary",
                manifest("Ordinary title", "ordinary fallback"), BASE_TIME.plusSeconds(4));
        insertApp("child-app", "session-child", 1L, "published", "interactive",
                "agent_generated", 5L, "child.html", null,
                manifest("Child", "Child fallback"), BASE_TIME.plusSeconds(5));
        insertApp("eval-app", "session-eval", 1L, "published", "interactive",
                "agent_generated", 6L, "eval.html", null,
                manifest("Eval", "Eval fallback"), BASE_TIME.plusSeconds(6));

        assertThat(list(query(1L, "recent", "zebra", null, null, null, null)))
                .extracting(PersonalAppLibraryRepository.PersonalAppRow::artifactId)
                .containsExactly("q-caption", "q-title");
        assertThat(list(query(1L, "recent", null, 2L, null, null, null)))
                .extracting(PersonalAppLibraryRepository.PersonalAppRow::artifactId)
                .containsExactly("child-app");
        assertThat(list(query(1L, "recent", null, null, "session-child", null, null)))
                .extracting(PersonalAppLibraryRepository.PersonalAppRow::artifactId)
                .containsExactly("child-app");
        assertThat(list(query(
                1L, "created", null, null, null, null, BASE_TIME.plusSeconds(3))))
                .extracting(PersonalAppLibraryRepository.PersonalAppRow::artifactId)
                .containsExactly("child-app", "q-session");

        Instant opened = Instant.parse("2026-07-20T00:00:00Z");
        assertThat(repository.setFavorite(1L, "q-title", true, opened)).isPresent();
        assertThat(repository.setFavorite(1L, "q-title", true, opened.plusSeconds(1))).isPresent();
        assertThat(repository.markOpened(1L, "q-title", opened.plusSeconds(2)))
                .get().satisfies(row -> {
                    assertThat(row.favorite()).isTrue();
                    assertThat(row.lastOpenedAt()).isEqualTo(opened.plusSeconds(2));
                });
        assertThat(repository.markOpened(1L, "q-title", opened.plusSeconds(3))).isPresent();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM t_personal_app_preference
                WHERE user_id = 1 AND attachment_id = 'q-title'
                """, Long.class)).isOne();
        assertThat(list(query(1L, "recent", null, null, null, true, null)))
                .extracting(PersonalAppLibraryRepository.PersonalAppRow::artifactId)
                .containsExactly("q-title");
        assertThat(list(query(1L, "recent", null, null, null, null, null)).get(0).artifactId())
                .isEqualTo("q-title");

        assertThat(repository.setFavorite(1L, "eval-app", true, opened)).isEmpty();
        assertThat(repository.setFavorite(2L, "q-title", true, opened)).isEmpty();
        assertThat(repository.markOpened(1L, "missing", opened)).isEmpty();
    }

    @Test
    void markOpenedKeepsTimestampsMonotonicWhenAnOlderRequestCommitsLast() {
        seedAgentsAndSessions();
        insertApp("opened-app", "session-main", 1L, "published", "interactive",
                "agent_generated", 1L, "opened.html", null,
                manifest("Opened", "Opened fallback"), BASE_TIME);

        Instant favoriteAt = Instant.parse("2026-07-20T00:00:00Z");
        Instant olderOpen = favoriteAt.plusSeconds(1);
        Instant newerOpen = favoriteAt.plusSeconds(2);
        assertThat(repository.setFavorite(1L, "opened-app", true, favoriteAt)).isPresent();
        assertThat(repository.markOpened(1L, "opened-app", newerOpen))
                .get()
                .satisfies(row -> {
                    assertThat(row.favorite()).isTrue();
                    assertThat(row.lastOpenedAt()).isEqualTo(newerOpen);
                });

        assertThat(repository.markOpened(1L, "opened-app", olderOpen))
                .get()
                .satisfies(row -> {
                    assertThat(row.favorite()).isTrue();
                    assertThat(row.lastOpenedAt()).isEqualTo(newerOpen);
                });

        PreferenceSnapshot persisted = jdbc.queryForObject("""
                SELECT favorite, last_opened_at, updated_at
                FROM t_personal_app_preference
                WHERE user_id = 1 AND attachment_id = 'opened-app'
                """, (rs, rowNum) -> new PreferenceSnapshot(
                rs.getBoolean("favorite"),
                rs.getTimestamp("last_opened_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()));
        assertThat(persisted).isEqualTo(new PreferenceSnapshot(true, newerOpen, newerOpen));
    }

    @Test
    void malformedAndOversizedLegacyManifestsCannotFailOrEnterPage() {
        seedAgentsAndSessions();
        insertApp("valid-app", "session-main", 1L, "published", "interactive",
                "agent_generated", 1L, "valid.html", null,
                manifest("Valid", "Valid fallback"), BASE_TIME);
        insertApp("malformed-app", "session-main", 1L, "published", "interactive",
                "agent_generated", 2L, "malformed.html", null,
                "{malformed", BASE_TIME.plusSeconds(1));
        String oversized = "{\"schemaVersion\":1,\"title\":\"Oversized\","
                + "\"fallback\":\"Fallback\",\"permissions\":[],\"network\":[],"
                + "\"initialData\":{},\"stateSchema\":{\"type\":\"object\"},\"padding\":\""
                + "x".repeat(PersonalAppLibraryRepository.MAX_MANIFEST_BYTES) + "\"}";
        insertApp("oversized-app", "session-main", 1L, "published", "interactive",
                "agent_generated", 3L, "oversized.html", null, oversized, BASE_TIME.plusSeconds(2));

        assertThat(list(query(1L, "recent", null, null, null, null, null)))
                .extracting(PersonalAppLibraryRepository.PersonalAppRow::artifactId)
                .containsExactly("valid-app");
    }

    @Test
    void explainUsesPersonalAppHotPathIndex() {
        seedVisiblePopulation();
        jdbc.execute("SET enable_seqscan = off");
        try {
            PersonalAppLibraryRepository.ListQuery query =
                    query(1L, "created", null, null, null, null, null);
            NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(
                    new SingleConnectionDataSource(connection, true));
            List<String> plan = named.query(
                    "EXPLAIN " + PersonalAppLibraryRepository.LIST_SQL,
                    PersonalAppLibraryRepository.parameters(query),
                    (rs, rowNum) -> rs.getString(1));
            assertThat(String.join("\n", plan))
                    .contains("idx_chat_attachment_personal_apps_hot");
        } finally {
            jdbc.execute("RESET enable_seqscan");
        }
    }

    private List<String> seedVisiblePopulation() {
        seedAgentsAndSessions();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 55; i++) {
            String id = "app-" + String.format("%03d", i);
            ids.add(id);
            insertApp(id, "session-main", 1L, "published", "interactive",
                    "agent_generated", (long) i, id + ".html", "Summary " + i,
                    manifest("App " + i, "Fallback " + i), BASE_TIME);
        }
        ids.add("app-child");
        insertApp("app-child", "session-child", 1L, "published", "interactive",
                "agent_generated", 55L, "child.html", null,
                manifest("Child App", "Child fallback"), BASE_TIME);
        return ids;
    }

    private void seedAgentsAndSessions() {
        jdbc.update("INSERT INTO t_agent (id, name) VALUES (1, 'Main Assistant'), (2, 'Child Agent')");
        insertSession("session-main", 1L, 1L, "Visible main", null, "production");
        insertSession("session-child", 1L, 2L, "Visible child", "session-main", "production");
        insertSession("session-eval", 1L, 1L, "Eval", null, "eval");
        insertSession("session-user0", 0L, 1L, "System", null, "production");
        insertSession("session-other", 2L, 1L, "Other", null, "production");
        insertSession("session-zebra", 1L, 1L, "Zebra Session Only", null, "production");
    }

    private void seedInvisibleVariants() {
        insertApp("hidden-eval", "session-eval", 1L, "published", "interactive",
                "agent_generated", 1L, "eval.html", null, manifest("Eval", "Eval"), BASE_TIME);
        insertApp("hidden-user0", "session-user0", 0L, "published", "interactive",
                "agent_generated", 1L, "system.html", null, manifest("System", "System"), BASE_TIME);
        insertApp("hidden-other", "session-other", 2L, "published", "interactive",
                "agent_generated", 1L, "other.html", null, manifest("Other", "Other"), BASE_TIME);
        insertApp("hidden-session-owner", "session-other", 1L, "published", "interactive",
                "agent_generated", 2L, "mismatch.html", null, manifest("Mismatch", "Mismatch"), BASE_TIME);
        insertApp("hidden-attachment-owner", "session-main", 2L, "published", "interactive",
                "agent_generated", 3L, "mismatch2.html", null, manifest("Mismatch", "Mismatch"), BASE_TIME);
        insertApp("hidden-uploaded", "session-main", 1L, "uploaded", "interactive",
                "agent_generated", 4L, "uploaded.html", null, manifest("Uploaded", "Uploaded"), BASE_TIME);
        insertApp("hidden-kind", "session-main", 1L, "published", "pdf",
                "agent_generated", 5L, "pdf.html", null, manifest("PDF", "PDF"), BASE_TIME);
        insertApp("hidden-origin", "session-main", 1L, "published", "interactive",
                "user_upload", 6L, "origin.html", null, manifest("Origin", "Origin"), BASE_TIME);
        insertApp("hidden-no-source", "session-main", 1L, "published", "interactive",
                "agent_generated", null, "source.html", null, manifest("No Source", "No Source"), BASE_TIME);
    }

    private static void insertSession(
            String id, long userId, long agentId, String title, String parentId, String origin) {
        jdbc.update("""
                INSERT INTO t_session (id, user_id, agent_id, title, parent_session_id, origin)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, userId, agentId, title, parentId, origin);
    }

    private static void insertApp(
            String id,
            String sessionId,
            long userId,
            String status,
            String kind,
            String origin,
            Long sourceSeq,
            String filename,
            String caption,
            String manifest,
            Instant createdAt) {
        jdbc.update("""
                INSERT INTO t_chat_attachment (
                    id, session_id, user_id, source_message_seq, kind, mime_type,
                    filename, size_bytes, storage_path, status, origin, caption,
                    interactive_manifest_json, created_at)
                VALUES (?, ?, ?, ?, ?, 'text/html', ?, 100, '/tmp/test', ?, ?, ?, ?, ?)
                """,
                id, sessionId, userId, sourceSeq, kind, filename, status, origin, caption,
                manifest, OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
    }

    private static String manifest(String title, String fallback) {
        return "{\"schemaVersion\":1,\"title\":\"" + title + "\","
                + "\"fallback\":\"" + fallback + "\",\"permissions\":[],\"network\":[],"
                + "\"initialData\":{},\"stateSchema\":{\"type\":\"object\"}}";
    }

    private List<PersonalAppLibraryRepository.PersonalAppRow> list(
            PersonalAppLibraryRepository.ListQuery query) {
        return repository.list(query);
    }

    private static PersonalAppLibraryRepository.ListQuery query(
            long userId,
            String sort,
            String q,
            Long agentId,
            String sessionId,
            Boolean favorite,
            Instant createdAfter) {
        return new PersonalAppLibraryRepository.ListQuery(
                userId, sort, q, agentId, sessionId, favorite, createdAfter,
                null, null, 100);
    }

    private static final class CountingDataSource extends DelegatingDataSource {
        private final AtomicInteger preparedStatements = new AtomicInteger();

        private CountingDataSource(DataSource targetDataSource) {
            super(targetDataSource);
        }

        @Override
        public Connection getConnection() throws SQLException {
            return counting(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return counting(super.getConnection(username, password));
        }

        private Connection counting(Connection delegate) {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        if (method.getName().startsWith("prepareStatement")
                                || method.getName().startsWith("prepareCall")) {
                            preparedStatements.incrementAndGet();
                        }
                        try {
                            return method.invoke(delegate, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }

        private void reset() {
            preparedStatements.set(0);
        }

        private int preparedStatementCount() {
            return preparedStatements.get();
        }
    }

    private record PreferenceSnapshot(
            boolean favorite,
            Instant lastOpenedAt,
            Instant updatedAt) { }
}
