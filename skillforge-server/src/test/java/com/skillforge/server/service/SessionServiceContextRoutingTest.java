package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.Message;
import com.skillforge.server.config.SessionMessageStoreProperties;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionMessageEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.SessionMessageRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SessionSummaryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * COMPACT-IDEMPOTENCY-BOUNDARY-FIX storage redesign P2b-2a — per-session dual-read routing in
 * {@link SessionService#getContextMessages(String)}.
 *
 * <p>This is a Mockito unit test (no DB) so the migration-safety gate runs in environments where
 * Docker / Testcontainers is unavailable. The full-DB pins live in
 * {@code SessionServiceDerivedContextIT} (Testcontainers; skipped without Docker). The KEY pin —
 * an OLD-model session (physical COMPACT_BOUNDARY row, NO active range summary) under flag-ON must
 * return the legacy post-boundary slice and NOT the whole history (the bug) — is asserted here.
 */
@DisplayName("SessionService.getContextMessages per-session dual-read routing (P2b-2a)")
class SessionServiceContextRoutingTest {

    private SessionRepository sessionRepository;
    private SessionMessageRepository sessionMessageRepository;
    private SessionSummaryRepository sessionSummaryRepository;
    private SessionService sessionService;

    private static final String SID = "sess-routing";

    @BeforeEach
    void setUp() {
        sessionRepository = mock(SessionRepository.class);
        sessionMessageRepository = mock(SessionMessageRepository.class);
        sessionSummaryRepository = mock(SessionSummaryRepository.class);
        AgentRepository agentRepository = mock(AgentRepository.class);

        // Real TransactionTemplate over a mock manager that just runs the callback.
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        sessionService = new SessionService(
                sessionRepository,
                sessionMessageRepository,
                agentRepository,
                new SessionMessageStoreProperties(), // rowReadEnabled=true, dualReadVerify=false
                new ObjectMapper(),
                txManager);
        sessionService.setSessionSummaryRepository(sessionSummaryRepository);

        // getFullHistoryRecords falls back to readLegacyMessagesJson when row records are empty;
        // it always calls getSession(id) for the legacy path, so stub a session with empty legacy.
        SessionEntity entity = new SessionEntity();
        entity.setId(SID);
        entity.setMessagesJson("[]");
        when(sessionRepository.findById(SID)).thenReturn(Optional.of(entity));
    }

    /** Stub the row-store read so loadAllMessageRows returns exactly these rows (single page). */
    private void stubRows(List<SessionMessageEntity> rows) {
        Page<SessionMessageEntity> page = new PageImpl<>(rows, PageRequest.of(0, 500), rows.size());
        when(sessionMessageRepository.findBySessionIdOrderBySeqNoAsc(eq(SID), any()))
                .thenReturn(page);
    }

    private SessionMessageEntity row(long seq, String msgType, String role, String text) {
        SessionMessageEntity e = new SessionMessageEntity();
        e.setSessionId(SID);
        e.setSeqNo(seq);
        e.setMsgType(msgType);
        e.setMessageType(SessionService.MESSAGE_TYPE_NORMAL);
        e.setRole(role);
        e.setContentJson("\"" + text + "\""); // JSON string content
        return e;
    }

    @Test
    @DisplayName("(a) REGRESSION GUARD: old-model session (boundary row, NO active summary), flag ON "
            + "→ legacy post-boundary slice, NOT full history")
    void oldModelSession_flagOn_returnsBoundarySliceNotFullHistory() {
        sessionService.setRangeModelEnabled(true);
        // No active summary for this session → it is NOT a new-model session.
        when(sessionSummaryRepository.existsBySessionIdAndSupersededByIsNull(SID)).thenReturn(false);

        List<SessionMessageEntity> rows = new ArrayList<>();
        rows.add(row(0, SessionService.MSG_TYPE_NORMAL, "user", "PRE-BOUNDARY old gen 0"));
        rows.add(row(1, SessionService.MSG_TYPE_NORMAL, "user", "PRE-BOUNDARY old gen 1"));
        rows.add(row(2, SessionService.MSG_TYPE_COMPACT_BOUNDARY, "user", "=== boundary summary ==="));
        rows.add(row(3, SessionService.MSG_TYPE_NORMAL, "user", "post-boundary young 0"));
        rows.add(row(4, SessionService.MSG_TYPE_NORMAL, "user", "post-boundary young 1"));
        stubRows(rows);

        List<Message> ctx = sessionService.getContextMessages(SID);

        // Must be the legacy post-boundary slice (seq 3,4 only); boundary row excluded.
        assertThat(ctx).hasSize(2);
        assertThat(ctx.get(0).getTextContent()).isEqualTo("post-boundary young 0");
        assertThat(ctx.get(1).getTextContent()).isEqualTo("post-boundary young 1");
        // THE BUG GUARD: pre-boundary rows must NOT leak into the LLM context.
        assertThat(ctx).noneMatch(m -> "PRE-BOUNDARY old gen 0".equals(m.getTextContent()));
        assertThat(ctx).noneMatch(m -> "PRE-BOUNDARY old gen 1".equals(m.getTextContent()));
        assertThat(ctx).noneMatch(m -> "=== boundary summary ===".equals(m.getTextContent()));

        // The derive path must NOT have been consulted (no active-summary lookup for the run-collapse).
        verify(sessionSummaryRepository, never())
                .findBySessionIdAndSupersededByIsNullOrderByStartSeqAsc(anyString());
    }

    @Test
    @DisplayName("(b) new-model session (active summary present), flag ON → derive branch is taken")
    void newModelSession_flagOn_takesDeriveBranch() {
        sessionService.setRangeModelEnabled(true);
        // Active summary present → new-model session → derive.
        when(sessionSummaryRepository.existsBySessionIdAndSupersededByIsNull(SID)).thenReturn(true);
        // The derive path calls findBySessionIdAndSupersededByIsNullOrderByStartSeqAsc; with no
        // entities returned, every row is "uncovered" and emitted verbatim — which is enough to
        // assert that the DERIVE branch (not the legacy boundary slice) ran.
        when(sessionSummaryRepository.findBySessionIdAndSupersededByIsNullOrderByStartSeqAsc(SID))
                .thenReturn(List.of());

        List<SessionMessageEntity> rows = new ArrayList<>();
        rows.add(row(0, SessionService.MSG_TYPE_NORMAL, "user", "pre 0"));
        rows.add(row(1, SessionService.MSG_TYPE_COMPACT_BOUNDARY, "user", "boundary"));
        rows.add(row(2, SessionService.MSG_TYPE_NORMAL, "user", "post 0"));
        stubRows(rows);

        List<Message> ctx = sessionService.getContextMessages(SID);

        // Derive view ignores COMPACT_BOUNDARY slicing: it emits all non-SYSTEM_EVENT rows verbatim
        // (no active summaries → all uncovered). The boundary row is a NORMAL-shaped message row in
        // the derived view, so all 3 rows surface — distinct from the legacy 1-row post-boundary slice.
        assertThat(ctx).hasSize(3);
        assertThat(ctx.get(0).getTextContent()).isEqualTo("pre 0");
        // Real derive-vs-legacy differentiator: the derived view emits the COMPACT_BOUNDARY row's
        // content verbatim (it does NOT slice at the boundary). The legacy slice would have dropped
        // every row up to and including the boundary, so the boundary text surfacing here proves the
        // derive branch ran rather than the legacy boundary slice.
        assertThat(ctx).anyMatch(m -> "boundary".equals(m.getTextContent()));
        // Confirms the derive branch ran (it consults active summaries; the legacy slice never does).
        verify(sessionSummaryRepository)
                .findBySessionIdAndSupersededByIsNullOrderByStartSeqAsc(SID);
    }

    @Test
    @DisplayName("(c) fresh session (no boundary, no summary), flag ON → all rows via legacy path")
    void freshSession_flagOn_returnsAllRows() {
        sessionService.setRangeModelEnabled(true);
        when(sessionSummaryRepository.existsBySessionIdAndSupersededByIsNull(SID)).thenReturn(false);

        List<SessionMessageEntity> rows = new ArrayList<>();
        rows.add(row(0, SessionService.MSG_TYPE_NORMAL, "user", "turn 0"));
        rows.add(row(1, SessionService.MSG_TYPE_NORMAL, "user", "turn 1"));
        rows.add(row(2, SessionService.MSG_TYPE_NORMAL, "user", "turn 2"));
        stubRows(rows);

        List<Message> ctx = sessionService.getContextMessages(SID);

        assertThat(ctx).hasSize(3);
        assertThat(ctx.get(0).getTextContent()).isEqualTo("turn 0");
        assertThat(ctx.get(2).getTextContent()).isEqualTo("turn 2");
    }

    @Test
    @DisplayName("(d) flag OFF → legacy boundary slice; existence check never consulted")
    void flagOff_legacySlice_existenceCheckSkipped() {
        sessionService.setRangeModelEnabled(false);

        List<SessionMessageEntity> rows = new ArrayList<>();
        rows.add(row(0, SessionService.MSG_TYPE_NORMAL, "user", "pre 0"));
        rows.add(row(1, SessionService.MSG_TYPE_COMPACT_BOUNDARY, "user", "boundary"));
        rows.add(row(2, SessionService.MSG_TYPE_NORMAL, "user", "post 0"));
        stubRows(rows);

        List<Message> ctx = sessionService.getContextMessages(SID);

        // Legacy slice: only the post-boundary row.
        assertThat(ctx).hasSize(1);
        assertThat(ctx.get(0).getTextContent()).isEqualTo("post 0");
        // Flag OFF short-circuits before the existence check — no summary-store query at all.
        verify(sessionSummaryRepository, never()).existsBySessionIdAndSupersededByIsNull(anyString());
    }
}
