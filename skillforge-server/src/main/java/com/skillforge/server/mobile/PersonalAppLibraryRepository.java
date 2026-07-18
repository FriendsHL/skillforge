package com.skillforge.server.mobile;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Repository
public class PersonalAppLibraryRepository {

    static final int MAX_MANIFEST_BYTES = 65_536;

    static final String LIST_SQL = """
            WITH visible AS (
                SELECT attachment.id AS artifact_id,
                       attachment.session_id,
                       attachment.source_message_seq,
                       attachment.filename,
                       attachment.caption,
                       parsed.manifest_json,
                       session.agent_id,
                       agent.name AS agent_name,
                       session.title AS session_title,
                       attachment.created_at,
                       preference.last_opened_at,
                       COALESCE(preference.favorite, FALSE) AS favorite,
                       CASE WHEN :recentSort
                            THEN COALESCE(preference.last_opened_at, attachment.created_at)
                            ELSE attachment.created_at
                       END AS sort_timestamp
                FROM t_chat_attachment attachment
                JOIN t_session session ON session.id = attachment.session_id
                JOIN t_agent agent ON agent.id = session.agent_id
                LEFT JOIN t_personal_app_preference preference
                  ON preference.attachment_id = attachment.id
                 AND preference.user_id = :userId
                CROSS JOIN LATERAL (
                    SELECT public.skillforge_try_parse_jsonb(
                        CASE
                            WHEN octet_length(attachment.interactive_manifest_json) <= :maxManifestBytes
                                THEN attachment.interactive_manifest_json
                            ELSE NULL
                        END
                    ) AS manifest_json
                ) parsed
                WHERE :userId > 0
                  AND attachment.user_id = :userId
                  AND session.user_id = :userId
                  AND session.origin = 'production'
                  AND attachment.kind = 'interactive'
                  AND attachment.origin = 'agent_generated'
                  AND attachment.status = 'published'
                  AND attachment.source_message_seq IS NOT NULL
                  AND jsonb_typeof(parsed.manifest_json) = 'object'
                  AND (CAST(:agentId AS BIGINT) IS NULL OR session.agent_id = :agentId)
                  AND (CAST(:sessionId AS TEXT) IS NULL OR attachment.session_id = :sessionId)
                  AND (CAST(:favorite AS BOOLEAN) IS NULL
                       OR COALESCE(preference.favorite, FALSE) = :favorite)
                  AND (CAST(:createdAfter AS TIMESTAMPTZ) IS NULL
                       OR attachment.created_at > :createdAfter)
                  AND (CAST(:qPattern AS TEXT) IS NULL OR
                       COALESCE(parsed.manifest_json ->> 'title', '') ILIKE :qPattern ESCAPE '\\' OR
                       COALESCE(NULLIF(BTRIM(attachment.caption), ''),
                                parsed.manifest_json ->> 'fallback', '') ILIKE :qPattern ESCAPE '\\')
            )
            SELECT artifact_id, session_id, source_message_seq, filename, caption,
                   manifest_json::TEXT AS manifest_json,
                   agent_id, agent_name, session_title, created_at, last_opened_at,
                   favorite, sort_timestamp
            FROM visible
            WHERE (CAST(:cursorTimestamp AS TIMESTAMPTZ) IS NULL
                   OR sort_timestamp < :cursorTimestamp
                   OR (sort_timestamp = :cursorTimestamp AND artifact_id < :cursorArtifactId))
            ORDER BY sort_timestamp DESC, artifact_id DESC
            LIMIT :limit
            """;

    static final String SET_FAVORITE_SQL = """
            INSERT INTO t_personal_app_preference
                (user_id, attachment_id, favorite, created_at, updated_at)
            SELECT :userId, attachment.id, :favorite, :now, :now
            FROM t_chat_attachment attachment
            JOIN t_session session ON session.id = attachment.session_id
            JOIN t_agent agent ON agent.id = session.agent_id
            WHERE :userId > 0
              AND attachment.id = :artifactId
              AND attachment.user_id = :userId
              AND session.user_id = :userId
              AND session.origin = 'production'
              AND attachment.kind = 'interactive'
              AND attachment.origin = 'agent_generated'
              AND attachment.status = 'published'
              AND attachment.source_message_seq IS NOT NULL
              AND jsonb_typeof(public.skillforge_try_parse_jsonb(
                    CASE
                        WHEN octet_length(attachment.interactive_manifest_json) <= :maxManifestBytes
                            THEN attachment.interactive_manifest_json
                        ELSE NULL
                    END)) = 'object'
            ON CONFLICT (user_id, attachment_id) DO UPDATE
            SET favorite = EXCLUDED.favorite,
                updated_at = EXCLUDED.updated_at
            RETURNING attachment_id, favorite, last_opened_at
            """;

    static final String MARK_OPENED_SQL = """
            INSERT INTO t_personal_app_preference
                (user_id, attachment_id, favorite, last_opened_at, created_at, updated_at)
            SELECT :userId, attachment.id, FALSE, :now, :now, :now
            FROM t_chat_attachment attachment
            JOIN t_session session ON session.id = attachment.session_id
            JOIN t_agent agent ON agent.id = session.agent_id
            WHERE :userId > 0
              AND attachment.id = :artifactId
              AND attachment.user_id = :userId
              AND session.user_id = :userId
              AND session.origin = 'production'
              AND attachment.kind = 'interactive'
              AND attachment.origin = 'agent_generated'
              AND attachment.status = 'published'
              AND attachment.source_message_seq IS NOT NULL
              AND jsonb_typeof(public.skillforge_try_parse_jsonb(
                    CASE
                        WHEN octet_length(attachment.interactive_manifest_json) <= :maxManifestBytes
                            THEN attachment.interactive_manifest_json
                        ELSE NULL
                    END)) = 'object'
            ON CONFLICT (user_id, attachment_id) DO UPDATE
            SET last_opened_at = GREATEST(
                    COALESCE(t_personal_app_preference.last_opened_at, EXCLUDED.last_opened_at),
                    EXCLUDED.last_opened_at),
                updated_at = GREATEST(
                    COALESCE(t_personal_app_preference.updated_at, EXCLUDED.updated_at),
                    EXCLUDED.updated_at)
            RETURNING attachment_id, favorite, last_opened_at
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public PersonalAppLibraryRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<PersonalAppRow> list(ListQuery query) {
        return jdbcTemplate.query(LIST_SQL, parameters(query), PersonalAppLibraryRepository::mapRow);
    }

    static MapSqlParameterSource parameters(ListQuery query) {
        return new MapSqlParameterSource()
                .addValue("userId", query.userId(), Types.BIGINT)
                .addValue("recentSort", "recent".equals(query.sort()), Types.BOOLEAN)
                .addValue("maxManifestBytes", MAX_MANIFEST_BYTES, Types.INTEGER)
                .addValue("agentId", query.agentId(), Types.BIGINT)
                .addValue("sessionId", query.sessionId(), Types.VARCHAR)
                .addValue("favorite", query.favorite(), Types.BOOLEAN)
                .addValue("createdAfter", sqlTimestamp(query.createdAfter()), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("qPattern", searchPattern(query.q()), Types.VARCHAR)
                .addValue("cursorTimestamp", sqlTimestamp(query.cursorTimestamp()), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("cursorArtifactId", query.cursorArtifactId(), Types.VARCHAR)
                .addValue("limit", query.limit(), Types.INTEGER);
    }

    public Optional<PreferenceRow> setFavorite(
            long userId, String artifactId, boolean favorite, Instant now) {
        return preference(SET_FAVORITE_SQL, userId, artifactId, favorite, now);
    }

    public Optional<PreferenceRow> markOpened(long userId, String artifactId, Instant now) {
        return preference(MARK_OPENED_SQL, userId, artifactId, false, now);
    }

    private Optional<PreferenceRow> preference(
            String sql, long userId, String artifactId, boolean favorite, Instant now) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("userId", userId, Types.BIGINT)
                .addValue("artifactId", artifactId, Types.VARCHAR)
                .addValue("favorite", favorite, Types.BOOLEAN)
                .addValue("now", sqlTimestamp(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("maxManifestBytes", MAX_MANIFEST_BYTES, Types.INTEGER);
        List<PreferenceRow> rows = jdbcTemplate.query(sql, parameters, (rs, rowNum) ->
                new PreferenceRow(
                        rs.getString("attachment_id"),
                        rs.getBoolean("favorite"),
                        instant(rs, "last_opened_at")));
        return rows.stream().findFirst();
    }

    private static PersonalAppRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new PersonalAppRow(
                rs.getString("artifact_id"),
                rs.getString("session_id"),
                rs.getLong("source_message_seq"),
                rs.getString("filename"),
                rs.getString("caption"),
                rs.getString("manifest_json"),
                rs.getLong("agent_id"),
                rs.getString("agent_name"),
                rs.getString("session_title"),
                instant(rs, "created_at"),
                instant(rs, "last_opened_at"),
                rs.getBoolean("favorite"),
                instant(rs, "sort_timestamp"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp timestamp = rs.getTimestamp(column);
        return timestamp != null ? timestamp.toInstant() : null;
    }

    private static String searchPattern(String query) {
        if (query == null) return null;
        return "%" + query
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_") + "%";
    }

    private static OffsetDateTime sqlTimestamp(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    public record ListQuery(
            long userId,
            String sort,
            String q,
            Long agentId,
            String sessionId,
            Boolean favorite,
            Instant createdAfter,
            Instant cursorTimestamp,
            String cursorArtifactId,
            int limit) { }

    public record PersonalAppRow(
            String artifactId,
            String sessionId,
            long sourceMessageSeq,
            String filename,
            String caption,
            String manifestJson,
            long agentId,
            String agentName,
            String sessionTitle,
            Instant createdAt,
            Instant lastOpenedAt,
            boolean favorite,
            Instant sortTimestamp) { }

    public record PreferenceRow(String artifactId, boolean favorite, Instant lastOpenedAt) { }
}
