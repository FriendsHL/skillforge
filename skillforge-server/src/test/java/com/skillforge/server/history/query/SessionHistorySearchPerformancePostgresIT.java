package com.skillforge.server.history.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.server.AbstractPostgresIT;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.history.CurrentSessionHistoryScope;
import com.skillforge.server.history.HistoryCanonicalSelector;
import com.skillforge.server.history.HistoryCursorCodec;
import com.skillforge.server.history.HistoryRefCodec;
import com.skillforge.server.history.SessionHistorySearchInput;
import com.skillforge.server.history.SessionHistorySearchResponse;
import com.skillforge.server.repository.SessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(JpaHistoryQueryStore.class)
class SessionHistorySearchPerformancePostgresIT extends AbstractPostgresIT {

    private static final int ROW_COUNT = 100_000;
    private static final int WARMUPS = 2;
    private static final int SAMPLES = 20;
    private static final long P95_GATE_MILLIS = 500;

    @Autowired private JpaHistoryQueryStore store;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private SessionRepository sessionRepository;

    @Test
    void search_selectiveKeywordAcross100kRows_p95AtMost500msAndExplainIsCaptured() {
        String sessionId = createSession();
        seedMessages(sessionId);
        jdbcTemplate.execute("ANALYZE t_session");
        jdbcTemplate.execute("ANALYZE t_session_message");
        long maxMessageId = jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM t_session_message WHERE session_id = ?", Long.class,
                sessionId);
        CurrentSessionHistoryScope scope = scope(sessionId, maxMessageId);
        SessionHistoryQueryService service = queryService();
        SessionHistorySearchInput input = new SessionHistorySearchInput(
                "needle-evaluation-100000", null, null, null, null,
                null, null, null, null, 8, null);

        for (int index = 0; index < WARMUPS; index++) {
            assertSingleHit(service.search(scope, input));
        }
        List<Long> samples = new ArrayList<>();
        for (int index = 0; index < SAMPLES; index++) {
            long started = System.nanoTime();
            assertSingleHit(service.search(scope, input));
            samples.add((System.nanoTime() - started) / 1_000_000);
        }
        Collections.sort(samples);
        long p95 = samples.get((int) Math.ceil(samples.size() * 0.95) - 1);
        List<String> plan = explainCurrentMessageScan(sessionId, maxMessageId);

        System.out.printf("SESSION_HISTORY_SEARCH_100K rows=%d samples_ms=%s p95_ms=%d%n",
                ROW_COUNT, samples, p95);
        System.out.println("SESSION_HISTORY_SEARCH_100K_EXPLAIN " + String.join(" | ", plan));
        assertThat(plan).isNotEmpty();
        assertThat(p95).as("100K selective Search p95 samples=%s plan=%s", samples, plan)
                .isLessThanOrEqualTo(P95_GATE_MILLIS);
    }

    private SessionHistoryQueryService queryService() {
        ObjectMapper objectMapper = new ObjectMapper();
        HistoryRefCodec refCodec = new HistoryRefCodec();
        HistoryEvidenceMaterializer materializer = new HistoryEvidenceMaterializer(
                objectMapper, new HistoryAuthorizedProjection(objectMapper), refCodec,
                new FailClosedHistoryCanonicalArchiveResolver());
        return new SessionHistoryQueryService(
                store, materializer, new HistoryCanonicalSelector(objectMapper),
                new HistoryCursorCodec(objectMapper), refCodec);
    }

    private String createSession() {
        SessionEntity session = new SessionEntity();
        session.setId(UUID.randomUUID().toString());
        session.setUserId(9090L);
        session.setAgentId(1L);
        session.setHistoryEpoch(0);
        session.setMessagesJson("[]");
        return sessionRepository.saveAndFlush(session).getId();
    }

    private void seedMessages(String sessionId) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO t_session_message
                    (session_id, seq_no, role, msg_type, content_json, metadata_json,
                     message_type, created_at)
                SELECT ?, series - 1, 'user', 'NORMAL',
                       to_json((CASE WHEN series = ?
                           THEN 'needle-evaluation-100000'
                           ELSE 'ordinary-history-' || series END)::text)::text,
                       '{}', 'normal', clock_timestamp()
                FROM generate_series(1, ?) AS series
                """, sessionId, ROW_COUNT, ROW_COUNT);
        assertThat(inserted).isEqualTo(ROW_COUNT);
    }

    private List<String> explainCurrentMessageScan(String sessionId, long maxMessageId) {
        return jdbcTemplate.queryForList("""
                EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
                SELECT m.id, m.seq_no, m.role, m.msg_type, m.content_json, m.message_type,
                       m.control_id, m.compacted_by_summary_id, m.created_at
                FROM t_session_message m, t_session s
                WHERE m.session_id = s.id
                  AND s.id = ? AND s.user_id = ? AND s.history_epoch = ?
                  AND m.id <= ? AND m.seq_no <= ? AND m.pruned_at IS NULL
                ORDER BY m.seq_no DESC, m.created_at DESC, m.id DESC
                LIMIT 100001
                """, String.class, sessionId, 9090L, 0L, maxMessageId, ROW_COUNT - 1L);
    }

    private static CurrentSessionHistoryScope scope(String sessionId, long maxMessageId) {
        SkillContext context = new SkillContext();
        context.setSessionId(sessionId);
        context.setUserId(9090L);
        context.setToolUseId("toolu_perf_history");
        return CurrentSessionHistoryScope.from(
                context, 0, maxMessageId, ROW_COUNT - 1L);
    }

    private static void assertSingleHit(SessionHistorySearchResponse response) {
        assertThat(response.locators()).singleElement()
                .satisfies(locator -> assertThat(locator.preview())
                        .isEqualTo("needle-evaluation-100000"));
    }
}
