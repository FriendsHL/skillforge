package com.skillforge.server.compact;

import com.skillforge.server.AbstractPostgresIT;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionMessageEntity;
import com.skillforge.server.entity.SessionSummaryEntity;
import com.skillforge.server.repository.SessionMessageRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SessionSummaryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * COMPACT-IDEMPOTENCY-BOUNDARY-FIX storage redesign P1 — verifies the V157 migration applies
 * (t_session_summary + t_session_message.compacted_by_summary_id) and the new repository queries
 * behave: active/latest lookup, supersede UPDATE, and the targeted row marker UPDATE.
 */
@DisplayName("SessionSummary repository + V157 migration (range model P1)")
class SessionSummaryRepositoryIT extends AbstractPostgresIT {

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private SessionSummaryRepository summaryRepository;

    @Autowired
    private SessionMessageRepository messageRepository;

    private String sessionId;

    @BeforeEach
    void setUp() {
        summaryRepository.deleteAll();
        messageRepository.deleteAll();
        sessionRepository.deleteAll();
        sessionId = newSession();
    }

    private String newSession() {
        SessionEntity s = new SessionEntity();
        s.setId(UUID.randomUUID().toString());
        s.setUserId(1L);
        s.setAgentId(10L);
        s.setTitle("summary-repo-it");
        s.setStatus("active");
        s.setRuntimeStatus("idle");
        sessionRepository.save(s);
        return s.getId();
    }

    private SessionSummaryEntity newSummary(long startSeq, long endSeq) {
        SessionSummaryEntity e = new SessionSummaryEntity();
        e.setSessionId(sessionId);
        e.setStartSeq(startSeq);
        e.setEndSeq(endSeq);
        e.setSummaryText("summary " + startSeq + "-" + endSeq);
        e.setLevel("full");
        e.setSource("engine-hard");
        e.setTokensBefore(1000);
        e.setTokensAfter(200);
        e.setCompactedMessageCount((int) (endSeq - startSeq + 1));
        e.setRecoveryPayload("<system-reminder>recovery</system-reminder>");
        return summaryRepository.save(e);
    }

    @Test
    @DisplayName("CRUD + fields round-trip through t_session_summary")
    void summaryCrudRoundTrips() {
        SessionSummaryEntity saved = newSummary(0, 8);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();

        Optional<SessionSummaryEntity> loaded = summaryRepository.findById(saved.getId());
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getStartSeq()).isEqualTo(0L);
        assertThat(loaded.get().getEndSeq()).isEqualTo(8L);
        assertThat(loaded.get().getRecoveryPayload()).contains("recovery");
        assertThat(loaded.get().getSupersededBy()).isNull();
    }

    @Test
    @DisplayName("active lookup excludes superseded; latest returns highest start_seq")
    void activeAndLatestQueries() {
        SessionSummaryEntity first = newSummary(0, 8);
        SessionSummaryEntity second = newSummary(0, 18);

        // Both active initially → ordered by start_seq (both 0 here, so size 2).
        List<SessionSummaryEntity> active =
                summaryRepository.findBySessionIdAndSupersededByIsNullOrderByStartSeqAsc(sessionId);
        assertThat(active).hasSize(2);

        // Supersede the first.
        int rows = summaryRepository.markSuperseded(first.getId(), second.getId());
        assertThat(rows).isEqualTo(1);

        List<SessionSummaryEntity> afterMerge =
                summaryRepository.findBySessionIdAndSupersededByIsNullOrderByStartSeqAsc(sessionId);
        assertThat(afterMerge).hasSize(1);
        assertThat(afterMerge.get(0).getId()).isEqualTo(second.getId());

        Optional<SessionSummaryEntity> latest =
                summaryRepository.findTopBySessionIdAndSupersededByIsNullOrderByStartSeqDesc(sessionId);
        assertThat(latest).isPresent();
        assertThat(latest.get().getId()).isEqualTo(second.getId());
    }

    @Test
    @DisplayName("markCompactedBySummary stamps only unmarked rows in range")
    void markCompactedBySummaryTargetsRange() {
        // Seed 5 message rows seq 0..4.
        for (int i = 0; i < 5; i++) {
            SessionMessageEntity m = new SessionMessageEntity();
            m.setSessionId(sessionId);
            m.setSeqNo(i);
            m.setRole("user");
            m.setMsgType("NORMAL");
            m.setMessageType("normal");
            m.setContentJson("\"msg " + i + "\"");
            messageRepository.save(m);
        }
        SessionSummaryEntity summary = newSummary(0, 2);

        int marked = messageRepository.markCompactedBySummary(sessionId, 0, 2, summary.getId());
        assertThat(marked).isEqualTo(3); // rows 0,1,2

        // Re-running marks 0 (already marked).
        int again = messageRepository.markCompactedBySummary(sessionId, 0, 2, summary.getId());
        assertThat(again).isEqualTo(0);

        // Verify only seq 0..2 carry the id; 3,4 stay null.
        List<SessionMessageEntity> all = messageRepository.findAll();
        for (SessionMessageEntity m : all) {
            if (m.getSeqNo() <= 2) {
                assertThat(m.getCompactedBySummaryId()).isEqualTo(summary.getId());
            } else {
                assertThat(m.getCompactedBySummaryId()).isNull();
            }
        }
    }
}
