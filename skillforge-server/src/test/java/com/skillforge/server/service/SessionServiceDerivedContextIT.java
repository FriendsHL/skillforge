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
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * COMPACT-IDEMPOTENCY-BOUNDARY-FIX storage redesign P2a — derived model-view read
 * ({@link SessionService#getContextMessagesWithProvenance(String)} + the
 * {@link SessionService#getContextMessages(String)} flag route).
 *
 * <p>Pins:
 * <ul>
 *   <li>single rolling active summary + uncovered tail → {@code [summary] + tail} with provenance
 *       {@code [-1, seq, seq, ...]}</li>
 *   <li>multiple adjacent active summaries → one injected summary per run</li>
 *   <li>no summaries (flag-on session never compacted) → all rows uncovered, provenance = seq_nos</li>
 *   <li>SYSTEM_EVENT rows skipped</li>
 *   <li>a row marked by a SUPERSEDED summary is treated as uncovered (defensive)</li>
 *   <li>flag OFF → legacy boundary-slice {@link SessionService#getContextMessages} unchanged</li>
 * </ul>
 */
@DisplayName("SessionService derived model-view read (range model P2a)")
class SessionServiceDerivedContextIT extends AbstractPostgresIT {

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
                sessionRepository,
                sessionMessageRepository,
                agentRepository,
                new SessionMessageStoreProperties(),
                new ObjectMapper(),
                transactionManager);
        sessionService.setSessionSummaryRepository(sessionSummaryRepository);
        sessionService.setRangeModelEnabled(true);
    }

    private String newSession() {
        SessionEntity s = new SessionEntity();
        s.setId(UUID.randomUUID().toString());
        s.setUserId(1L);
        s.setAgentId(10L);
        s.setTitle("derived-context-it");
        s.setStatus("active");
        s.setRuntimeStatus("idle");
        sessionRepository.save(s);
        return s.getId();
    }

    /** Append a NORMAL message row at the next seq and return its seq_no. */
    private long appendRow(String sid, Message m) {
        return sessionService.appendNormalMessages(sid, List.of(m));
    }

    private void appendSystemEvent(String sid, Message m) {
        sessionService.appendMessages(sid, List.of(
                new SessionService.AppendMessage(m, SessionService.MSG_TYPE_SYSTEM_EVENT,
                        java.util.Collections.emptyMap())));
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

    private void mark(String sid, long start, long end, Long summaryId) {
        sessionMessageRepository.markCompactedBySummary(sid, start, end, summaryId);
    }

    @Test
    @DisplayName("single rolling summary + uncovered tail: [summary] + tail, provenance [-1, seq...]")
    void singleRollingSummary_plusTail() {
        String sid = newSession();
        for (int i = 0; i < 6; i++) {
            appendRow(sid, Message.user("turn " + i)); // seq 0..5
        }
        // Summary covers [0,3]; tail = seq 4,5 uncovered.
        SessionSummaryEntity s = newSummary(sid, 0, 3, "ROLLING SUMMARY", null);
        mark(sid, 0, 3, s.getId());

        SessionService.ContextWithProvenance ctx =
                sessionService.getContextMessagesWithProvenance(sid);

        assertThat(ctx.messages()).hasSize(3); // [summary, turn4, turn5]
        assertThat(ctx.messages().get(0).getRole()).isEqualTo(Message.Role.USER);
        assertThat(ctx.messages().get(0).getContent()).isEqualTo("ROLLING SUMMARY");
        assertThat(ctx.messages().get(1).getTextContent()).isEqualTo("turn 4");
        assertThat(ctx.messages().get(2).getTextContent()).isEqualTo("turn 5");

        assertThat(ctx.provenance()).containsExactly(SessionService.PROVENANCE_SUMMARY, 4L, 5L);

        // getContextMessages (flag ON) returns the same messages.
        assertThat(sessionService.getContextMessages(sid)).hasSize(3);
        assertThat(sessionService.getContextMessages(sid).get(0).getContent()).isEqualTo("ROLLING SUMMARY");
    }

    @Test
    @DisplayName("multiple adjacent active summaries: one injected summary per covered run")
    void multipleAdjacentSummaries() {
        String sid = newSession();
        for (int i = 0; i < 7; i++) {
            appendRow(sid, Message.user("turn " + i)); // seq 0..6
        }
        // Summary A covers [0,2]; Summary B covers [3,4]; tail = seq 5,6 uncovered.
        SessionSummaryEntity a = newSummary(sid, 0, 2, "SUMMARY A", null);
        SessionSummaryEntity b = newSummary(sid, 3, 4, "SUMMARY B", null);
        mark(sid, 0, 2, a.getId());
        mark(sid, 3, 4, b.getId());

        SessionService.ContextWithProvenance ctx =
                sessionService.getContextMessagesWithProvenance(sid);

        assertThat(ctx.messages()).hasSize(4); // [A, B, turn5, turn6]
        assertThat(ctx.messages().get(0).getContent()).isEqualTo("SUMMARY A");
        assertThat(ctx.messages().get(1).getContent()).isEqualTo("SUMMARY B");
        assertThat(ctx.messages().get(2).getTextContent()).isEqualTo("turn 5");
        assertThat(ctx.messages().get(3).getTextContent()).isEqualTo("turn 6");
        assertThat(ctx.provenance())
                .containsExactly(SessionService.PROVENANCE_SUMMARY, SessionService.PROVENANCE_SUMMARY, 5L, 6L);
    }

    @Test
    @DisplayName("no summaries: every row uncovered, provenance = real seq_nos")
    void noSummaries_allUncovered() {
        String sid = newSession();
        for (int i = 0; i < 4; i++) {
            appendRow(sid, Message.user("turn " + i));
        }
        SessionService.ContextWithProvenance ctx =
                sessionService.getContextMessagesWithProvenance(sid);

        assertThat(ctx.messages()).hasSize(4);
        assertThat(ctx.provenance()).containsExactly(0L, 1L, 2L, 3L);
    }

    @Test
    @DisplayName("SYSTEM_EVENT rows are skipped in the derived view")
    void systemEventSkipped() {
        String sid = newSession();
        appendRow(sid, Message.user("turn 0"));            // seq 0
        appendSystemEvent(sid, Message.user("sys event")); // seq 1 (SYSTEM_EVENT)
        appendRow(sid, Message.user("turn 2"));            // seq 2

        SessionService.ContextWithProvenance ctx =
                sessionService.getContextMessagesWithProvenance(sid);

        assertThat(ctx.messages()).hasSize(2);
        assertThat(ctx.messages().get(0).getTextContent()).isEqualTo("turn 0");
        assertThat(ctx.messages().get(1).getTextContent()).isEqualTo("turn 2");
        assertThat(ctx.provenance()).containsExactly(0L, 2L);
    }

    @Test
    @DisplayName("row marked by a SUPERSEDED summary is treated as uncovered (defensive)")
    void supersededMarker_treatedAsUncovered() {
        String sid = newSession();
        for (int i = 0; i < 5; i++) {
            appendRow(sid, Message.user("turn " + i)); // seq 0..4
        }
        // First summary covers [0,1] then gets superseded by a rolling one covering [2,3].
        // The P1 marker for rows 0,1 still points at the OLD (now superseded) summary id.
        SessionSummaryEntity oldS = newSummary(sid, 0, 1, "OLD SUMMARY", null);
        mark(sid, 0, 1, oldS.getId());
        SessionSummaryEntity newS = newSummary(sid, 2, 3, "NEW SUMMARY", null);
        mark(sid, 2, 3, newS.getId());
        // Now supersede the old one.
        sessionSummaryRepository.markSuperseded(oldS.getId(), newS.getId());

        SessionService.ContextWithProvenance ctx =
                sessionService.getContextMessagesWithProvenance(sid);

        // Rows 0,1 carry a superseded marker → emitted as real rows. Rows 2,3 collapse into
        // NEW SUMMARY. Row 4 uncovered tail.
        assertThat(ctx.messages()).hasSize(4); // [turn0, turn1, NEW SUMMARY, turn4]
        assertThat(ctx.messages().get(0).getTextContent()).isEqualTo("turn 0");
        assertThat(ctx.messages().get(1).getTextContent()).isEqualTo("turn 1");
        assertThat(ctx.messages().get(2).getContent()).isEqualTo("NEW SUMMARY");
        assertThat(ctx.messages().get(3).getTextContent()).isEqualTo("turn 4");
        assertThat(ctx.provenance())
                .containsExactly(0L, 1L, SessionService.PROVENANCE_SUMMARY, 4L);
    }

    @Test
    @DisplayName("flag OFF: getContextMessages uses legacy boundary slice, ignores summaries")
    void flagOff_legacyBoundarySlice() {
        // Fresh service with flag OFF.
        SessionService legacySvc = new SessionService(
                sessionRepository, sessionMessageRepository, agentRepository,
                new SessionMessageStoreProperties(), new ObjectMapper(), transactionManager);
        legacySvc.setSessionSummaryRepository(sessionSummaryRepository);
        legacySvc.setRangeModelEnabled(false);

        String sid = newSession();
        for (int i = 0; i < 4; i++) {
            appendRow(sid, Message.user("turn " + i)); // seq 0..3
        }
        // Even if a summary exists + rows are marked, flag-OFF read ignores them entirely.
        SessionSummaryEntity s = newSummary(sid, 0, 1, "SHOULD BE IGNORED", null);
        mark(sid, 0, 1, s.getId());

        List<Message> legacy = legacySvc.getContextMessages(sid);
        // No COMPACT_BOUNDARY row present → legacy slice returns ALL rows verbatim (no summary).
        assertThat(legacy).hasSize(4);
        assertThat(legacy.get(0).getTextContent()).isEqualTo("turn 0");
        assertThat(legacy).noneMatch(m -> "SHOULD BE IGNORED".equals(m.getContent()));
    }
}
