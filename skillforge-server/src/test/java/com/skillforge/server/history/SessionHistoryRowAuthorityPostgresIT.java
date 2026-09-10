package com.skillforge.server.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.AbstractPostgresIT;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.SessionMessageRepository;
import com.skillforge.server.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SessionHistoryRowAuthorityPostgresIT extends AbstractPostgresIT {
    @Autowired private SessionRepository sessions;
    @Autowired private SessionMessageRepository messages;
    @Autowired private JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    private SessionEntity session;

    @BeforeEach
    void createSession() {
        session = new SessionEntity();
        session.setId(UUID.randomUUID().toString());
        session.setUserId(1201L);
        session.setAgentId(1202L);
        session.setStatus("active");
        session.setRuntimeStatus("idle");
        sessions.saveAndFlush(session);
    }

    @Test
    void fullPrefixAllowsAppendsButDetectsSameEpochMutationAndMissingRows() {
        append(0, "\"exact fact\"");
        append(1, "\"later\"");
        session.setMessagesJson("[{\"role\":\"user\",\"content\":\"exact fact\"}]");
        assertThat(verified()).isTrue();
        jdbc.update("UPDATE t_session_message SET content_json = ? WHERE session_id = ? AND seq_no = 0",
                "\"changed\"", session.getId());
        assertThat(verified()).isFalse();
        jdbc.update("DELETE FROM t_session_message WHERE session_id = ? AND seq_no = 0", session.getId());
        assertThat(verified()).isFalse();
    }

    @Test
    void prunedProjectionPreservesOtherBlocksAndRequiresActualPrunedMarker() {
        String raw = "[{\"type\":\"text\",\"text\":\"keep exact text\"},"
                + "{\"type\":\"tool_result\",\"tool_use_id\":\"call\",\"content\":\"raw fact\",\"is_error\":false}]";
        append(0, raw);
        String projected = raw.replace("raw fact", "[TOOL OUTPUT PRUNED]");
        session.setMessagesJson("[{\"role\":\"user\",\"content\":" + projected + "}]");
        assertThat(verified()).isFalse();
        jdbc.update("UPDATE t_session_message SET pruned_at = CURRENT_TIMESTAMP WHERE session_id = ?", session.getId());
        assertThat(verified()).isTrue();
        session.setMessagesJson(session.getMessagesJson().replace("keep exact text", "changed text"));
        assertThat(verified()).isFalse();
        session.setMessagesJson("[{\"role\":\"user\",\"content\":" + raw + "}]");
        assertThat(verified()).isTrue();
    }

    @Test
    void rejectsPartialBackfillAndReasoningMismatch() {
        append(0, "\"exact fact\"");
        session.setMessagesJson("[{\"role\":\"user\",\"content\":\"exact fact\"},"
                + "{\"role\":\"user\",\"content\":\"missing\"}]");
        assertThat(verified()).isFalse();
        session.setMessagesJson("[{\"role\":\"user\",\"content\":\"exact fact\",\"reasoning_content\":\"missing reasoning\"}]");
        assertThat(verified()).isFalse();
    }

    @Test
    void verifies100000RowMirrorInOneStatementAndDetectsMiddleGap() {
        jdbc.update("""
                INSERT INTO t_session_message
                    (session_id, seq_no, role, content_json, msg_type, message_type, created_at)
                SELECT ?, n, 'user', CAST(to_jsonb('fact ' || n) AS text), 'NORMAL', 'normal', CURRENT_TIMESTAMP
                FROM generate_series(0, 99999) AS n
                """, session.getId());
        String mirror = jdbc.queryForObject("""
                SELECT CAST(jsonb_agg(jsonb_build_object('role', role, 'content', CAST(content_json AS jsonb))
                                     ORDER BY seq_no) AS text)
                FROM t_session_message WHERE session_id = ?
                """, String.class, session.getId());
        session.setMessagesJson(mirror);
        long started = System.nanoTime();
        assertThat(verified()).isTrue();
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        System.out.println("Legacy 100K prefix verification ms=" + elapsedMs);
        jdbc.update("DELETE FROM t_session_message WHERE session_id = ? AND seq_no = 50000", session.getId());
        assertThat(verified()).isFalse();
    }

    private boolean verified() {
        return SessionHistoryRowAuthority.isVerified(session, messages, mapper);
    }

    private void append(long seq, String content) {
        jdbc.update("""
                INSERT INTO t_session_message
                    (session_id, seq_no, role, content_json, msg_type, message_type, created_at)
                VALUES (?, ?, 'user', ?, 'NORMAL', 'normal', CURRENT_TIMESTAMP)
                """, session.getId(), seq, content);
    }
}
