package com.skillforge.server.service.observability;

import com.skillforge.observability.api.LlmTraceStore;
import com.skillforge.observability.domain.LlmSpan;
import com.skillforge.observability.domain.LlmSpanSource;
import com.skillforge.server.controller.observability.dto.EventSpanSummaryDto;
import com.skillforge.server.controller.observability.dto.LlmSpanSummaryDto;
import com.skillforge.server.controller.observability.dto.SpanSummaryDto;
import com.skillforge.server.controller.observability.dto.ToolSpanSummaryDto;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OBS-2 M3 — single-table session spans output (kind={llm,tool,event}).
 *
 * <p>The pre-M3 merged-table implementation is gone — listMergedSpans now reads
 * {@code t_llm_span} via {@link LlmTraceStore#listSpansBySession(String, Set, Instant, int)}
 * (with kind filtering pushed into the SQL layer). The traceId branch routes through
 * {@link LlmTraceStore#listSpansByTrace}.
 *
 * <p>r2 W-1: SessionSpansService depends on the {@link LlmTraceStore} interface, not the
 * concrete {@code PgLlmTraceStore} class — this test mocks the interface accordingly.
 */
@DisplayName("SessionSpansService — single-table output (M3)")
class SessionSpansMergedIT {

    private static final Long USER_ID = 1L;

    private LlmTraceStore traceStore;
    private SessionRepository sessionRepository;
    private SubagentSessionResolver subagentResolver;
    private SessionSpansService service;

    @BeforeEach
    void setUp() {
        traceStore = mock(LlmTraceStore.class);
        sessionRepository = mock(SessionRepository.class);
        subagentResolver = mock(SubagentSessionResolver.class);
        service = new SessionSpansService(traceStore, sessionRepository, subagentResolver);
    }

    /** Stub the session ownership lookup that lives at the top of {@code listMergedSpans}. */
    private void stubOwnedSession(String sessionId) {
        SessionEntity session = new SessionEntity();
        session.setId(sessionId);
        session.setUserId(USER_ID);
        when(sessionRepository.findById(eq(sessionId))).thenReturn(Optional.of(session));
    }

    private LlmSpan llmSpan(String spanId, String sessionId, Instant t) {
        return new LlmSpan(
                spanId, "trace-1", null, sessionId,
                1L, "claude", "claude-sonnet-4-20250514",
                0, true,
                null, null, null, null, null,
                "ok",
                100, 50, null, null,
                null, 5_000L, t, t,
                "stop", null, null,
                null, null, null,
                Collections.emptyMap(),
                LlmSpanSource.LIVE,
                "llm", null, null);
    }

    private LlmSpan toolSpan(String spanId, String sessionId, Instant t, String name) {
        return new LlmSpan(
                spanId, "trace-1", "trace-1", sessionId,
                1L, null, null,
                0, false,
                "{\"path\":\"/tmp\"}", "read", null, null, null,
                "ok",
                0, 0, null, null,
                null, 1_000L, t, t,
                null, null, null,
                null, null, "tu_x",
                Collections.emptyMap(),
                LlmSpanSource.LIVE,
                "tool", null, name);
    }

    private LlmSpan eventSpan(String spanId, String sessionId, Instant t, String eventType) {
        return new LlmSpan(
                spanId, "trace-1", "trace-1", sessionId,
                1L, null, null,
                0, false,
                "in", "out", null, null, null,
                "ok",
                0, 0, null, null,
                null, 250L, t, t,
                null, null, null,
                null, null, null,
                Collections.emptyMap(),
                LlmSpanSource.LIVE,
                "event", eventType, eventType);
    }

    @Test
    @DisplayName("returns all 3 kinds via single-table query, ordered by startedAt")
    void returnsAllKinds() {
        String sessionId = "session-merged-1";
        Instant t0 = Instant.parse("2026-04-29T10:00:00Z");
        Instant t1 = Instant.parse("2026-04-29T10:00:05Z");
        Instant t2 = Instant.parse("2026-04-29T10:00:10Z");

        stubOwnedSession(sessionId);
        when(traceStore.listSpansBySession(eq(sessionId), any(), any(), anyInt()))
                .thenReturn(List.of(
                        llmSpan("llm-1", sessionId, t0),
                        toolSpan("tool-1", sessionId, t1, "Read"),
                        eventSpan("event-1", sessionId, t2, "ask_user")));

        List<SpanSummaryDto> merged = service.listMergedSpans(
                sessionId, USER_ID, null, null, 200, null);

        assertThat(merged).hasSize(3);
        assertThat(merged.get(0)).isInstanceOf(LlmSpanSummaryDto.class);
        assertThat(merged.get(1)).isInstanceOf(ToolSpanSummaryDto.class);
        assertThat(merged.get(2)).isInstanceOf(EventSpanSummaryDto.class);
        assertThat(merged.get(2).kind()).isEqualTo("event");
    }

    @Test
    @DisplayName("kinds=[llm] forwards filter to LlmTraceStore.listSpansBySession")
    void kindsFilterPushedDown() {
        String sessionId = "session-llm-only";
        Instant t0 = Instant.parse("2026-04-29T10:00:00Z");

        stubOwnedSession(sessionId);
        when(traceStore.listSpansBySession(eq(sessionId), eq(Set.of("llm")), any(), anyInt()))
                .thenReturn(List.of(llmSpan("llm-only", sessionId, t0)));

        List<SpanSummaryDto> merged = service.listMergedSpans(
                sessionId, USER_ID, null, null, 200, Set.of("llm"));

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).kind()).isEqualTo("llm");
    }

    @Test
    @DisplayName("traceId param routes through traceStore.listSpansByTrace and bypasses session query")
    void traceIdRoutesThroughTraceStore() {
        String sessionId = "session-trace-route";
        Instant t0 = Instant.parse("2026-04-29T10:00:00Z");

        stubOwnedSession(sessionId);
        when(traceStore.listSpansByTrace(eq("trace-1"), any(), anyInt()))
                .thenReturn(List.of(llmSpan("llm-1", sessionId, t0)));

        List<SpanSummaryDto> merged = service.listMergedSpans(
                sessionId, USER_ID, "trace-1", null, 200, null);

        assertThat(merged).hasSize(1);
        verify(traceStore, never()).listSpansBySession(anyString(), any(), any(), anyInt());
        verify(traceStore).listSpansByTrace(eq("trace-1"), any(), anyInt());
    }

    @Test
    @DisplayName("traceId branch drops cross-session spans (defensive)")
    void traceIdDropsCrossSession() {
        String sessionId = "session-A";
        Instant t0 = Instant.parse("2026-04-29T10:00:00Z");

        stubOwnedSession(sessionId);
        // Trace returns a span whose sessionId belongs to a different session — should be dropped.
        when(traceStore.listSpansByTrace(eq("trace-1"), any(), anyInt()))
                .thenReturn(List.of(llmSpan("llm-1", "session-B", t0)));

        List<SpanSummaryDto> merged = service.listMergedSpans(
                sessionId, USER_ID, "trace-1", null, 200, null);

        assertThat(merged).isEmpty();
    }

    @Test
    @DisplayName("null kinds defaults to [llm,tool,event] passed down to LlmTraceStore (W-4)")
    void nullKindsDefaultsToAllThree() {
        String sessionId = "session-default-kinds";
        stubOwnedSession(sessionId);
        when(traceStore.listSpansBySession(eq(sessionId), any(), any(), anyInt()))
                .thenReturn(List.of());

        service.listMergedSpans(sessionId, USER_ID, null, null, 200, null);

        org.mockito.ArgumentCaptor<Set<String>> kindsCaptor =
                org.mockito.ArgumentCaptor.forClass(Set.class);
        verify(traceStore).listSpansBySession(eq(sessionId), kindsCaptor.capture(), any(), anyInt());
        assertThat(kindsCaptor.getValue())
                .as("null kinds expanded to all three at the service layer")
                .containsExactlyInAnyOrder("llm", "tool", "event");
    }

    @Test
    @DisplayName("empty kinds defaults to [llm,tool,event] passed down to LlmTraceStore (W-4)")
    void emptyKindsDefaultsToAllThree() {
        String sessionId = "session-empty-kinds";
        stubOwnedSession(sessionId);
        when(traceStore.listSpansBySession(eq(sessionId), any(), any(), anyInt()))
                .thenReturn(List.of());

        service.listMergedSpans(sessionId, USER_ID, null, null, 200, Set.of());

        org.mockito.ArgumentCaptor<Set<String>> kindsCaptor =
                org.mockito.ArgumentCaptor.forClass(Set.class);
        verify(traceStore).listSpansBySession(eq(sessionId), kindsCaptor.capture(), any(), anyInt());
        assertThat(kindsCaptor.getValue())
                .containsExactlyInAnyOrder("llm", "tool", "event");
    }

    @Test
    @DisplayName("ownership mismatch returns empty (defense in depth)")
    void ownershipMismatchEmpty() {
        String sessionId = "session-other";
        SessionEntity session = new SessionEntity();
        session.setId(sessionId);
        session.setUserId(99L);  // different from USER_ID
        when(sessionRepository.findById(eq(sessionId))).thenReturn(Optional.of(session));

        List<SpanSummaryDto> merged = service.listMergedSpans(
                sessionId, USER_ID, null, null, 200, null);

        assertThat(merged).isEmpty();
        verify(traceStore, never()).listSpansBySession(anyString(), any(), any(), anyInt());
    }
}
