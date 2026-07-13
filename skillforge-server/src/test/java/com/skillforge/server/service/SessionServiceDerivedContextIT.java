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
 *   <li>active summary ranges, not stale markers, are authoritative for model-view collapse</li>
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

    private void appendBoundary(String sid, Message m) {
        sessionService.appendMessages(sid, List.of(
                new SessionService.AppendMessage(m, SessionService.MSG_TYPE_COMPACT_BOUNDARY,
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
    @DisplayName("active summary range is authoritative even when markers point at superseded summaries")
    void activeRangeWinsOverStaleSupersededMarkers() {
        String sid = newSession();
        for (int i = 0; i < 8; i++) {
            appendRow(sid, Message.user("turn " + i)); // seq 0..7
        }
        // Old markers point at a superseded summary for seq 0..3. The active rolling summary covers
        // 0..5, but only rows 4..5 were stamped with the active marker. The model view must still
        // collapse 0..5 exactly once from the active range, otherwise already-covered rows are
        // re-exposed to the LLM.
        SessionSummaryEntity oldS = newSummary(sid, 0, 3, "OLD SUMMARY", null);
        mark(sid, 0, 3, oldS.getId());
        SessionSummaryEntity active = newSummary(sid, 0, 5, "ACTIVE SUMMARY", null);
        sessionSummaryRepository.markSuperseded(oldS.getId(), active.getId());
        mark(sid, 4, 5, active.getId());

        SessionService.ContextWithProvenance ctx =
                sessionService.getContextMessagesWithProvenance(sid);

        assertThat(ctx.messages()).hasSize(3); // [ACTIVE SUMMARY, turn6, turn7]
        assertThat(ctx.messages().get(0).getContent()).isEqualTo("ACTIVE SUMMARY");
        assertThat(ctx.messages().get(1).getTextContent()).isEqualTo("turn 6");
        assertThat(ctx.messages().get(2).getTextContent()).isEqualTo("turn 7");
        assertThat(ctx.provenance()).containsExactly(SessionService.PROVENANCE_SUMMARY, 6L, 7L);
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
        assertThat(legacy).noneMatch(m -> "SHOULD BE IGNORED".equals(m.getTextContent()));
    }

    // ===== P2b-2a per-session dual-read gate (migration safety) =====
    // getContextMessages must derive ONLY when the session has an ACTIVE t_session_summary row.
    // Otherwise (old-model boundary sessions / fresh sessions) it must fall through to the legacy
    // boundary slice even when the global flag is ON.

    @Test
    @DisplayName("(a) REGRESSION GUARD: old-model session (boundary row, NO summary), flag ON → "
            + "legacy post-boundary slice, NOT full history")
    void oldModelSession_flagOn_returnsBoundarySliceNotFullHistory() {
        // sessionService has rangeModelEnabled=true + summaryRepository wired (set up in @BeforeEach).
        String sid = newSession();
        appendRow(sid, Message.user("PRE-BOUNDARY old gen 0"));   // seq 0
        appendRow(sid, Message.user("PRE-BOUNDARY old gen 1"));   // seq 1
        appendBoundary(sid, Message.user("=== boundary summary ==="));   // seq 2 (COMPACT_BOUNDARY)
        appendRow(sid, Message.user("post-boundary young 0"));   // seq 3
        appendRow(sid, Message.user("post-boundary young 1"));   // seq 4
        // NO t_session_summary row exists for this session → it is an old-model session.

        List<Message> ctx = sessionService.getContextMessages(sid);

        // Must be the legacy post-boundary slice (seq 3,4 only) — the boundary row itself is excluded.
        assertThat(ctx).hasSize(2);
        assertThat(ctx.get(0).getTextContent()).isEqualTo("post-boundary young 0");
        assertThat(ctx.get(1).getTextContent()).isEqualTo("post-boundary young 1");
        // THE BUG GUARD: pre-boundary rows must NOT leak into the LLM context.
        assertThat(ctx).noneMatch(m -> "PRE-BOUNDARY old gen 0".equals(m.getTextContent()));
        assertThat(ctx).noneMatch(m -> "PRE-BOUNDARY old gen 1".equals(m.getTextContent()));
        assertThat(ctx).noneMatch(m -> "=== boundary summary ===".equals(m.getTextContent()));
    }

    @Test
    @DisplayName("(b) new-model session (active summary), flag ON → derive [summary] + tail")
    void newModelSession_flagOn_derives() {
        String sid = newSession();
        for (int i = 0; i < 5; i++) {
            appendRow(sid, Message.user("turn " + i)); // seq 0..4
        }
        SessionSummaryEntity s = newSummary(sid, 0, 2, "ACTIVE SUMMARY", null);
        mark(sid, 0, 2, s.getId());

        List<Message> ctx = sessionService.getContextMessages(sid);

        // Derived view: [summary, turn3, turn4].
        assertThat(ctx).hasSize(3);
        assertThat(ctx.get(0).getContent()).isEqualTo("ACTIVE SUMMARY");
        assertThat(ctx.get(1).getTextContent()).isEqualTo("turn 3");
        assertThat(ctx.get(2).getTextContent()).isEqualTo("turn 4");
    }

    @Test
    @DisplayName("(b2) only-superseded summary (no ACTIVE), flag ON → NOT new-model → legacy slice")
    void onlySupersededSummary_flagOn_fallsThroughToLegacy() {
        String sid = newSession();
        for (int i = 0; i < 4; i++) {
            appendRow(sid, Message.user("turn " + i)); // seq 0..3
        }
        // Two summaries exist but BOTH are superseded (point at the other) → zero active →
        // existsBySessionIdAndSupersededByIsNull = false → must NOT derive.
        SessionSummaryEntity a = newSummary(sid, 0, 1, "SUPERSEDED A", null);
        SessionSummaryEntity b = newSummary(sid, 0, 1, "SUPERSEDED B", null);
        sessionSummaryRepository.markSuperseded(a.getId(), b.getId());
        sessionSummaryRepository.markSuperseded(b.getId(), a.getId());

        // Sanity: no active summary remains for this session.
        assertThat(sessionSummaryRepository.existsBySessionIdAndSupersededByIsNull(sid)).isFalse();

        // No boundary row → legacy slice returns all rows verbatim (NOT derived).
        List<Message> ctx = sessionService.getContextMessages(sid);
        assertThat(ctx).hasSize(4);
        assertThat(ctx.get(0).getTextContent()).isEqualTo("turn 0");
        assertThat(ctx).noneMatch(m -> "SUPERSEDED A".equals(m.getTextContent()));
        assertThat(ctx).noneMatch(m -> "SUPERSEDED B".equals(m.getTextContent()));
    }

    @Test
    @DisplayName("(c) fresh session (no boundary, no summary), flag ON → all rows (legacy path)")
    void freshSession_flagOn_returnsAllRows() {
        String sid = newSession();
        for (int i = 0; i < 3; i++) {
            appendRow(sid, Message.user("turn " + i)); // seq 0..2
        }
        // No summary, no boundary → not new-model → legacy slice → all rows.
        List<Message> ctx = sessionService.getContextMessages(sid);
        assertThat(ctx).hasSize(3);
        assertThat(ctx.get(0).getTextContent()).isEqualTo("turn 0");
        assertThat(ctx.get(2).getTextContent()).isEqualTo("turn 2");
    }

    @Test
    @DisplayName("(d) flag OFF → legacy boundary slice regardless of an active summary present")
    void flagOff_withActiveSummary_stillLegacy() {
        SessionService legacySvc = new SessionService(
                sessionRepository, sessionMessageRepository, agentRepository,
                new SessionMessageStoreProperties(), new ObjectMapper(), transactionManager);
        legacySvc.setSessionSummaryRepository(sessionSummaryRepository);
        legacySvc.setRangeModelEnabled(false);

        String sid = newSession();
        for (int i = 0; i < 4; i++) {
            appendRow(sid, Message.user("turn " + i)); // seq 0..3
        }
        // Active summary present, but flag OFF → must NOT derive.
        SessionSummaryEntity s = newSummary(sid, 0, 1, "ACTIVE BUT IGNORED", null);
        mark(sid, 0, 1, s.getId());

        List<Message> ctx = legacySvc.getContextMessages(sid);
        assertThat(ctx).hasSize(4);
        assertThat(ctx.get(0).getTextContent()).isEqualTo("turn 0");
        assertThat(ctx).noneMatch(m -> "ACTIVE BUT IGNORED".equals(m.getTextContent()));
    }
}
