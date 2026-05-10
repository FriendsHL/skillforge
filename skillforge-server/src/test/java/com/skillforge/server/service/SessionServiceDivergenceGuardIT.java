package com.skillforge.server.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.Message;
import com.skillforge.server.AbstractPostgresIT;
import com.skillforge.server.config.SessionMessageStoreProperties;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionMessageEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.SessionMessageRepository;
import com.skillforge.server.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-05-10 W2 regression coverage for the mid-prefix divergence guard added to
 * {@link SessionService#updateSessionMessages(String, java.util.List, long, long, String)}.
 *
 * <p>Before the fix, the guard only triggered on the all-or-nothing
 * {@code prefixLen == 0} case; mid-prefix divergence (engine state diverged from
 * DB at some inner index but matched at index 0) silently appended the engine's
 * suffix as delta — combined with row-store dedup-by-id this produced duplicate
 * user-message rows (Q2 commit bdb0453). This IT pins:
 *
 * <ul>
 *   <li>Case 1: mid-prefix shape divergence triggers a full rewrite (no dup-append),
 *       and the diagnostic {@code log.warn} carries content type info.</li>
 *   <li>Case 2: perfect-prefix append-delta path is unchanged — only new rows are
 *       written, existing rows keep their original {@code created_at}.</li>
 * </ul>
 */
@DisplayName("SessionService mid-prefix divergence guard")
class SessionServiceDivergenceGuardIT extends AbstractPostgresIT {

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private SessionMessageRepository sessionMessageRepository;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private SessionService sessionService;

    @BeforeEach
    void setUp() {
        sessionMessageRepository.deleteAll();
        sessionRepository.deleteAll();
        sessionService = new SessionService(
                sessionRepository,
                sessionMessageRepository,
                agentRepository,
                new SessionMessageStoreProperties(), // defaults: rowWrite/rowRead 均 true
                new ObjectMapper(),
                transactionManager
        );
    }

    private String newSession() {
        SessionEntity s = new SessionEntity();
        s.setId(UUID.randomUUID().toString());
        s.setUserId(1L);
        s.setAgentId(10L);
        s.setTitle("divergence-guard-it");
        s.setStatus("active");
        s.setRuntimeStatus("idle");
        sessionRepository.save(s);
        return s.getId();
    }

    private List<SessionMessageEntity> rowsAsc(String sid) {
        return sessionMessageRepository
                .findBySessionIdOrderBySeqNoAsc(sid, PageRequest.of(0, 100))
                .getContent();
    }

    @Test
    @DisplayName("mid-prefix divergence triggers full rewrite and emits diagnostic warn")
    void updateSessionMessages_midPrefixDivergence_triggersRewrite() {
        // Arrange: seed 3 rows at seq 0,1,2 — mimics chat history persisted by
        // ChatService before the engine loop runs.
        String sid = newSession();
        sessionService.appendNormalMessages(sid, List.of(
                Message.user("hello"),
                Message.user("world"),
                Message.user("foo")
        ), "trace-seed");

        // Capture logs to assert the diagnostic warn fires with content type info.
        Logger svcLogger = (Logger) LoggerFactory.getLogger(SessionService.class);
        ListAppender<ILoggingEvent> logCapture = new ListAppender<>();
        logCapture.start();
        svcLogger.addAppender(logCapture);

        try {
            // Engine view diverges at index 1 (same role+size, different content) and
            // appends a fresh delta — pre-fix this would have silently dup-appended
            // rows starting at seq 3, since prefixLen=1 > 0 missed the original guard.
            List<Message> engineMessages = List.of(
                    Message.user("hello"),
                    Message.user("WORLD-MUTATED"),
                    Message.user("foo"),
                    Message.assistant("new-assistant")
            );
            sessionService.updateSessionMessages(sid, engineMessages, 100, 50, "trace-x");

            // Assert: rewrite happened — DB has exactly 4 rows (engine view), not 7
            // (3 original + 4 dup-appended delta).
            List<SessionMessageEntity> rows = rowsAsc(sid);
            assertThat(rows).hasSize(4);
            assertThat(rows.get(0).getSeqNo()).isEqualTo(0L);
            assertThat(rows.get(1).getSeqNo()).isEqualTo(1L);
            assertThat(rows.get(2).getSeqNo()).isEqualTo(2L);
            assertThat(rows.get(3).getSeqNo()).isEqualTo(3L);

            // Content of the divergent row (seq 1) and the new delta row (seq 3)
            // both reflect the engine view post-rewrite.
            List<SessionService.StoredMessage> after = sessionService.getFullHistoryRecords(sid);
            assertThat(after).hasSize(4);
            assertThat(after.get(1).message().getTextContent()).isEqualTo("WORLD-MUTATED");
            assertThat(after.get(3).message().getTextContent()).isEqualTo("new-assistant");

            // Diagnostic warn was emitted with the divergence index + content shape.
            // No PII (raw content) leaked — only content class simple name.
            assertThat(logCapture.list)
                    .as("expected mid-prefix divergence warn to be emitted")
                    .anySatisfy(event -> {
                        String msg = event.getFormattedMessage();
                        assertThat(msg).contains("mid-prefix divergence");
                        assertThat(msg).contains("divergeAt=1");
                        assertThat(msg).contains("persistedSize=3");
                        assertThat(msg).contains("engineSize=4");
                        assertThat(msg).contains("persistContentType=");
                        assertThat(msg).contains("engineContentType=");
                    });
        } finally {
            svcLogger.detachAppender(logCapture);
        }
    }

    @Test
    @DisplayName("perfect prefix appends delta only and preserves existing rows' created_at")
    void updateSessionMessages_perfectPrefix_appendsDeltaOnly() throws InterruptedException {
        // Arrange: seed 3 rows. Sleep briefly so any rewrite would produce a
        // strictly later created_at — the assertion below would catch a regression
        // where perfect-prefix accidentally takes a rewrite branch.
        String sid = newSession();
        sessionService.appendNormalMessages(sid, List.of(
                Message.user("hello"),
                Message.user("world"),
                Message.user("foo")
        ), "trace-seed");

        List<SessionMessageEntity> seeded = rowsAsc(sid);
        assertThat(seeded).hasSize(3);
        Instant seq0CreatedAt = seeded.get(0).getCreatedAt();
        Instant seq1CreatedAt = seeded.get(1).getCreatedAt();
        Instant seq2CreatedAt = seeded.get(2).getCreatedAt();
        Thread.sleep(20L); // ensure any rewrite would yield strictly later instants

        // Engine view: same prefix + 1 new assistant delta — this is the canonical
        // happy-path each chat turn takes after the loop completes.
        List<Message> engineMessages = List.of(
                Message.user("hello"),
                Message.user("world"),
                Message.user("foo"),
                Message.assistant("new-assistant")
        );
        sessionService.updateSessionMessages(sid, engineMessages, 100, 50, "trace-x");

        // Assert: 4 rows, only the new tail is appended (seq 3); the original 3
        // rows keep their created_at — proves we took the append-delta branch and
        // did NOT invoke rewriteRowsInNewTransaction.
        List<SessionMessageEntity> after = rowsAsc(sid);
        assertThat(after).hasSize(4);
        assertThat(after.get(0).getCreatedAt()).isEqualTo(seq0CreatedAt);
        assertThat(after.get(1).getCreatedAt()).isEqualTo(seq1CreatedAt);
        assertThat(after.get(2).getCreatedAt()).isEqualTo(seq2CreatedAt);
        // Delta row is strictly newer.
        assertThat(after.get(3).getCreatedAt()).isAfter(seq2CreatedAt);

        // And the new content actually landed at seq 3 with the new traceId.
        List<SessionService.StoredMessage> records = sessionService.getFullHistoryRecords(sid);
        assertThat(records).hasSize(4);
        assertThat(records.get(3).message().getTextContent()).isEqualTo("new-assistant");
        assertThat(records.get(3).traceId()).isEqualTo("trace-x");
        // Existing rows' traceId untouched.
        assertThat(records.get(0).traceId()).isEqualTo("trace-seed");
        assertThat(records.get(1).traceId()).isEqualTo("trace-seed");
        assertThat(records.get(2).traceId()).isEqualTo("trace-seed");
    }
}
