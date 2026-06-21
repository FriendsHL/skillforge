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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * COMPACT-IDEMPOTENCY-BOUNDARY-FIX storage redesign P2b (B2): {@code compacted_by_summary_id} marker
 * recompute after a rewrite. Without recompute, the DELETE+INSERT rewrite in
 * {@link SessionService#rewriteMessages} (and the divergence-guard rewrite, restore, branch — all of
 * which re-insert rows via AppendMessage that carries no marker) would wipe the markers → the derived
 * model view would stop collapsing covered rows AND a future summary could re-cover them (double-show).
 *
 * <p>Pins:
 * <ul>
 *   <li>rewriteMessages on a session with an active summary → markers re-derived from the range,
 *       derived model view identical, no double-show.</li>
 *   <li>recomputeCompactedMarkers restamps from active ranges, ignores superseded summaries.</li>
 *   <li>flag OFF → recompute is a no-op (never touches markers).</li>
 * </ul>
 */
@DisplayName("SessionService compacted-marker preservation on rewrite (range model P2b B2)")
class SessionServiceCompactedMarkerPreservationIT extends AbstractPostgresIT {

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
        sessionService = newService(true);
    }

    private SessionService newService(boolean rangeModelEnabled) {
        SessionService svc = new SessionService(
                sessionRepository,
                sessionMessageRepository,
                agentRepository,
                new SessionMessageStoreProperties(),
                new ObjectMapper(),
                transactionManager);
        svc.setSessionSummaryRepository(sessionSummaryRepository);
        svc.setRangeModelEnabled(rangeModelEnabled);
        return svc;
    }

    private String newSession() {
        SessionEntity s = new SessionEntity();
        s.setId(UUID.randomUUID().toString());
        s.setUserId(1L);
        s.setAgentId(10L);
        s.setTitle("marker-preservation-it");
        s.setStatus("active");
        s.setRuntimeStatus("idle");
        sessionRepository.save(s);
        return s.getId();
    }

    private SessionSummaryEntity newSummary(String sid, long startSeq, long endSeq, String text,
                                            Long supersededBy) {
        SessionSummaryEntity e = new SessionSummaryEntity();
        e.setSessionId(sid);
        e.setStartSeq(startSeq);
        e.setEndSeq(endSeq);
        e.setSummaryText(text);
        e.setLevel("full");
        e.setSource("engine-hard");
        e.setSupersededBy(supersededBy);
        return sessionSummaryRepository.save(e);
    }

    private List<SessionMessageEntity> rowsAsc(String sid) {
        return sessionMessageRepository
                .findBySessionIdOrderBySeqNoAsc(sid, PageRequest.of(0, 100))
                .getContent();
    }

    @Test
    @DisplayName("rewriteMessages recomputes markers from active range; derived view unchanged, no double-show")
    void rewriteMessages_recomputesMarkers_derivedViewUnchanged() {
        String sid = newSession();
        for (int i = 0; i < 6; i++) {
            sessionService.appendNormalMessages(sid, List.of(Message.user("turn " + i))); // seq 0..5
        }
        SessionSummaryEntity s = newSummary(sid, 0, 3, "ROLLING SUMMARY", null);
        sessionMessageRepository.markCompactedBySummary(sid, 0, 3, s.getId());

        // Sanity: derived view before rewrite is [summary, turn4, turn5].
        SessionService.ContextWithProvenance before =
                sessionService.getContextMessagesWithProvenance(sid);
        assertThat(before.messages()).hasSize(3);
        assertThat(before.messages().get(0).getContent()).isEqualTo("ROLLING SUMMARY");

        // Simulate a rewrite (divergence-guard / restore semantics) replaying the SAME 6 real rows.
        List<SessionService.AppendMessage> replay = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            replay.add(new SessionService.AppendMessage(
                    Message.user("turn " + i), SessionService.MSG_TYPE_NORMAL,
                    java.util.Collections.emptyMap()));
        }
        sessionService.rewriteMessages(sid, replay);

        // Markers must be restored from the active summary range [0,3].
        List<SessionMessageEntity> rows = rowsAsc(sid);
        assertThat(rows).hasSize(6);
        for (int i = 0; i <= 3; i++) {
            assertThat(rows.get(i).getCompactedBySummaryId())
                    .as("row %s should be marked by active summary after rewrite", i)
                    .isEqualTo(s.getId());
        }
        assertThat(rows.get(4).getCompactedBySummaryId()).isNull();
        assertThat(rows.get(5).getCompactedBySummaryId()).isNull();

        // Derived model view identical → no double-show (covered rows still collapse to one summary).
        SessionService.ContextWithProvenance after =
                sessionService.getContextMessagesWithProvenance(sid);
        assertThat(after.messages()).hasSize(3);
        assertThat(after.messages().get(0).getContent()).isEqualTo("ROLLING SUMMARY");
        assertThat(after.messages().get(1).getTextContent()).isEqualTo("turn 4");
        assertThat(after.messages().get(2).getTextContent()).isEqualTo("turn 5");
        assertThat(after.provenance()).containsExactly(SessionService.PROVENANCE_SUMMARY, 4L, 5L);
    }

    @Test
    @DisplayName("recomputeCompactedMarkers restamps active ranges and ignores superseded summaries")
    void recompute_ignoresSupersededSummaries() {
        String sid = newSession();
        for (int i = 0; i < 5; i++) {
            sessionService.appendNormalMessages(sid, List.of(Message.user("turn " + i))); // seq 0..4
        }
        // Old summary [0,1] superseded by new summary [0,3]. Only the active one should restamp.
        SessionSummaryEntity oldS = newSummary(sid, 0, 1, "OLD", null);
        SessionSummaryEntity newS = newSummary(sid, 0, 3, "NEW ROLLING", null);
        sessionSummaryRepository.markSuperseded(oldS.getId(), newS.getId());
        // Pre-seed deliberately WRONG markers to prove recompute clears + restamps.
        sessionMessageRepository.markCompactedBySummary(sid, 0, 1, oldS.getId());

        sessionService.recomputeCompactedMarkers(sid);

        List<SessionMessageEntity> rows = rowsAsc(sid);
        for (int i = 0; i <= 3; i++) {
            assertThat(rows.get(i).getCompactedBySummaryId())
                    .as("row %s marked by ACTIVE summary", i)
                    .isEqualTo(newS.getId());
        }
        assertThat(rows.get(4).getCompactedBySummaryId()).isNull();
    }

    @Test
    @DisplayName("flag OFF: recomputeCompactedMarkers is a no-op (markers untouched)")
    void flagOff_recomputeIsNoOp() {
        String sid = newSession();
        for (int i = 0; i < 3; i++) {
            sessionService.appendNormalMessages(sid, List.of(Message.user("turn " + i)));
        }
        SessionSummaryEntity s = newSummary(sid, 0, 1, "SUMMARY", null);
        sessionMessageRepository.markCompactedBySummary(sid, 0, 1, s.getId());

        SessionService offSvc = newService(false);
        offSvc.recomputeCompactedMarkers(sid); // flag OFF → must not clear existing markers

        List<SessionMessageEntity> rows = rowsAsc(sid);
        assertThat(rows.get(0).getCompactedBySummaryId()).isEqualTo(s.getId());
        assertThat(rows.get(1).getCompactedBySummaryId()).isEqualTo(s.getId());
        assertThat(rows.get(2).getCompactedBySummaryId()).isNull();
    }
}
