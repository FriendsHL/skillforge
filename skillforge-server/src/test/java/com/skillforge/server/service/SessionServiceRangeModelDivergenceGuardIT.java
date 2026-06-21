package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.Message;
import com.skillforge.server.AbstractPostgresIT;
import com.skillforge.server.config.SessionMessageStoreProperties;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionMessageEntity;
import com.skillforge.server.entity.SessionSummaryEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.SessionMessageRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SessionSummaryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * COMPACT-IDEMPOTENCY-BOUNDARY-FIX storage redesign P2b (B1 STEP 3): when the range model is ON and
 * the divergence guard in {@link SessionService#updateSessionMessages} would otherwise fire, the
 * summary-safe rewrite must NEVER persist an injected summary (the derived {@code Message.user(text)}
 * with provenance -1) as a NORMAL message row (INV-4). The B1 lock normally prevents the mismatch;
 * this pins the belt-and-suspenders backstop even if a mismatch surfaces anyway.
 */
@DisplayName("SessionService range-model divergence guard is summary-safe (P2b B1 STEP 3)")
class SessionServiceRangeModelDivergenceGuardIT extends AbstractPostgresIT {

    @Autowired
    private SessionRepository sessionRepository;
    @Autowired
    private SessionMessageRepository sessionMessageRepository;
    @Autowired
    private SessionSummaryRepository sessionSummaryRepository;
    @Autowired
    private AgentRepository agentRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private SessionService sessionService;

    @BeforeEach
    void setUp() {
        sessionSummaryRepository.deleteAll();
        sessionMessageRepository.deleteAll();
        sessionRepository.deleteAll();
        sessionService = new SessionService(
                sessionRepository, sessionMessageRepository, agentRepository,
                new SessionMessageStoreProperties(), new ObjectMapper(), transactionManager);
        sessionService.setSessionSummaryRepository(sessionSummaryRepository);
        sessionService.setRangeModelEnabled(true);
        // Intentionally NO compaction lock provider — exercise the STEP 3 backstop directly.
    }

    private String newSession() {
        SessionEntity s = new SessionEntity();
        s.setId(UUID.randomUUID().toString());
        s.setUserId(1L);
        s.setAgentId(10L);
        s.setTitle("range-divergence-it");
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
    @DisplayName("prefix mismatch under range model never writes the injected summary as a NORMAL row")
    void divergence_underRangeModel_doesNotPersistSummaryRow() {
        String sid = newSession();
        // 4 real rows seq 0..3.
        for (int i = 0; i < 4; i++) {
            sessionService.appendNormalMessages(sid, List.of(Message.user("turn " + i)));
        }
        // Summary covers [0,1]; rows 2,3 uncovered tail.
        SessionSummaryEntity s = newSummary(sid, 0, 1, "ROLLING SUMMARY TEXT");
        sessionMessageRepository.markCompactedBySummary(sid, 0, 1, s.getId());

        // Derived model view: [summary, turn2, turn3].
        List<Message> derived = sessionService.getContextMessages(sid);
        assertThat(derived.get(0).getContent()).isEqualTo("ROLLING SUMMARY TEXT");

        // Engine view that DIVERGES from the derived view at index 1 (forces prefix mismatch),
        // and STILL carries the injected summary at index 0. A naive rewrite would write
        // "ROLLING SUMMARY TEXT" as a NORMAL row → INV-4 leak.
        List<Message> engineView = List.of(
                Message.user("ROLLING SUMMARY TEXT"), // injected summary (provenance -1)
                Message.user("turn 2 MUTATED"),       // divergence
                Message.user("turn 3"),
                Message.assistant("new turn")
        );

        List<SessionMessageEntity> before = rowsAsc(sid);
        assertThat(before).hasSize(4); // pre-reconcile: 4 real rows seq 0..3

        sessionService.updateSessionMessages(sid, engineView, 10, 5, "trace-x");

        // No NORMAL row may carry the summary text.
        List<SessionMessageEntity> rows = rowsAsc(sid);
        assertThat(rows)
                .as("INV-4: the injected summary must never be persisted as a NORMAL message row")
                .noneSatisfy(r -> assertThat(r.getContentJson()).contains("ROLLING SUMMARY TEXT"));

        // POSITIVE side: the genuinely-new engine tail (everything beyond the matched prefix, minus
        // the injected summary) IS appended as NORMAL rows. prefixLen=1 (index 0 = summary matches,
        // index 1 = "turn 2" vs "turn 2 MUTATED" diverges) → append [turn 2 MUTATED, turn 3, new turn]
        // = 3 new rows. A regression that over-filters / appends nothing would fail here.
        assertThat(rows)
                .as("3 new real turns appended beyond the matched prefix")
                .hasSize(4 + 3);
        assertThat(rows.stream().filter(r -> r.getContentJson().contains("new turn")).count())
                .as("the genuinely-new tail turn must land as a NORMAL row")
                .isEqualTo(1);
        assertThat(rows.stream().filter(r -> r.getContentJson().contains("turn 2 MUTATED")).count())
                .as("the divergent engine tail turn must land as a NORMAL row")
                .isEqualTo(1);

        // The original 4 real rows are intact (append-only history not destroyed).
        assertThat(rows.stream().filter(r -> r.getContentJson().contains("turn 0")).count()).isEqualTo(1);
        assertThat(rows.stream().filter(r -> r.getContentJson().contains("turn 1")).count()).isEqualTo(1);

        // Summary still in its own table, not leaked into messages.
        assertThat(sessionSummaryRepository.findBySessionIdAndSupersededByIsNullOrderByStartSeqAsc(sid))
                .extracting(SessionSummaryEntity::getSummaryText)
                .containsExactly("ROLLING SUMMARY TEXT");
    }

    private SessionSummaryEntity newSummary(String sid, long startSeq, long endSeq, String text) {
        SessionSummaryEntity e = new SessionSummaryEntity();
        e.setSessionId(sid);
        e.setStartSeq(startSeq);
        e.setEndSeq(endSeq);
        e.setSummaryText(text);
        e.setLevel("full");
        e.setSource("engine-hard");
        return sessionSummaryRepository.save(e);
    }
}
