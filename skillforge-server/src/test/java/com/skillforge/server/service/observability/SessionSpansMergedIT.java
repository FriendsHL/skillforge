package com.skillforge.server.service.observability;

import com.skillforge.observability.api.LlmTraceStore;
import com.skillforge.observability.domain.LlmSpan;
import com.skillforge.observability.domain.LlmSpanSource;
import com.skillforge.server.controller.observability.dto.LlmSpanSummaryDto;
import com.skillforge.server.controller.observability.dto.SpanSummaryDto;
import com.skillforge.server.controller.observability.dto.ToolSpanSummaryDto;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.TraceSpanEntity;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.TraceSpanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Plan §7.2 / Judge R3 — merged session spans must surface both LLM and TOOL_CALL rows
 * with correct {@code kind} discriminator and {@code startedAt} ordering.
 *
 * <p>Approach: unit-test the {@link SessionSpansService} directly with mocked stores —
 * captures the merge contract without spinning up a full Spring + PG context. The DB
 * round-trip is already covered by {@link com.skillforge.observability.store.PgLlmTraceUpsertTest}
 * and {@code TraceSpanRepository}'s implicit JPA wiring.
 */
@DisplayName("SessionSpansService — merged LLM + TOOL_CALL output")
class SessionSpansMergedIT {

    private static final Long USER_ID = 1L;

    private LlmTraceStore traceStore;
    private TraceSpanRepository traceSpanRepository;
    private SessionRepository sessionRepository;
    private SessionSpansService service;

    @BeforeEach
    void setUp() {
        traceStore = mock(LlmTraceStore.class);
        traceSpanRepository = mock(TraceSpanRepository.class);
        sessionRepository = mock(SessionRepository.class);
        service = new SessionSpansService(traceStore, traceSpanRepository, sessionRepository);
    }

    /** Stub the session ownership lookup that lives at the top of {@code listMergedSpans}. */
    private void stubOwnedSession(String sessionId) {
        SessionEntity session = new SessionEntity();
        session.setId(sessionId);
        session.setUserId(USER_ID);
        when(sessionRepository.findById(eq(sessionId))).thenReturn(Optional.of(session));
    }

    @Test
    @DisplayName("seed 1 LLM span + 1 TOOL_CALL span → returns 2 spans ordered by startedAt with kind set")
    void mergesLlmAndToolByStartedAt() {
        String sessionId = "session-merged-1";
        Instant t0 = Instant.parse("2026-04-29T10:00:00Z");
        Instant t1 = Instant.parse("2026-04-29T10:00:05Z");

        // LLM span starts FIRST.
        LlmSpan llm = new LlmSpan(
                "llm-span-1", "trace-1", null, sessionId,
                1L, "claude", "claude-sonnet-4-20250514",
                0, true,
                null, null, null, null, null,
                "ok",
                100, 50, null, null,
                null, 5_000L,
                t0, t1,
                "stop", null, null,
                null, null, null,
                Collections.emptyMap(),
                LlmSpanSource.LIVE);

        // TOOL_CALL span starts AFTER (t1).
        TraceSpanEntity tool = new TraceSpanEntity();
        tool.setId("tool-span-1");
        tool.setSessionId(sessionId);
        tool.setParentSpanId("agent-loop-root-1");
        tool.setSpanType("TOOL_CALL");
        tool.setName("FileRead");
        tool.setToolUseId("tool_use_abc");
        tool.setSuccess(true);
        tool.setInput("{\"path\":\"/tmp/foo\"}");
        tool.setOutput("read 100 bytes");
        tool.setStartTime(t1);
        tool.setEndTime(Instant.parse("2026-04-29T10:00:06Z"));
        tool.setDurationMs(1_000L);
        tool.setIterationIndex(0);

        stubOwnedSession(sessionId);
        when(traceStore.listSpansBySession(eq(sessionId), any(), anyInt()))
                .thenReturn(List.of(llm));
        when(traceSpanRepository.findBySessionIdAndSpanTypeOrderByStartTimeAsc(
                eq(sessionId), eq("TOOL_CALL"))).thenReturn(List.of(tool));

        // Act
        List<SpanSummaryDto> merged = service.listMergedSpans(sessionId, USER_ID, null, 200, null);

        // Assert
        assertThat(merged).hasSize(2);
        assertThat(merged.get(0).kind()).isEqualTo("llm");
        assertThat(merged.get(0).spanId()).isEqualTo("llm-span-1");
        assertThat(merged.get(1).kind()).isEqualTo("tool");
        assertThat(merged.get(1).spanId()).isEqualTo("tool-span-1");
        // Strictly ordered by startedAt
        assertThat(merged.get(0).startedAt()).isBefore(merged.get(1).startedAt());

        assertThat(merged.get(0)).isInstanceOf(LlmSpanSummaryDto.class);
        assertThat(merged.get(1)).isInstanceOf(ToolSpanSummaryDto.class);
    }

    @Test
    @DisplayName("kinds=[llm] filters out tool spans (and never queries TraceSpanRepository)")
    void kindsFilterOnlyLlm() {
        String sessionId = "session-llm-only";
        Instant t0 = Instant.parse("2026-04-29T10:00:00Z");

        LlmSpan llm = new LlmSpan(
                "llm-only", "trace-only", null, sessionId,
                1L, "openai", "gpt-4o",
                0, false,
                null, null, null, null, null,
                "ok",
                10, 5, null, null,
                null, 100L, t0, t0,
                "stop", null, null, null, null, null,
                Collections.emptyMap(),
                LlmSpanSource.LIVE);
        stubOwnedSession(sessionId);
        when(traceStore.listSpansBySession(eq(sessionId), any(), anyInt()))
                .thenReturn(List.of(llm));

        List<SpanSummaryDto> merged = service.listMergedSpans(
                sessionId, USER_ID, null, 200, java.util.Set.of("llm"));

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).kind()).isEqualTo("llm");
        org.mockito.Mockito.verify(traceSpanRepository, org.mockito.Mockito.never())
                .findBySessionIdAndSpanTypeOrderByStartTimeAsc(anyString(), anyString());
    }

    @Test
    @DisplayName("kinds=[tool] filters out llm spans (and never queries LlmTraceStore)")
    void kindsFilterOnlyTool() {
        String sessionId = "session-tool-only";
        Instant t0 = Instant.parse("2026-04-29T10:00:00Z");

        TraceSpanEntity tool = new TraceSpanEntity();
        tool.setId("tool-only");
        tool.setSessionId(sessionId);
        tool.setParentSpanId("root");
        tool.setSpanType("TOOL_CALL");
        tool.setName("Bash");
        tool.setStartTime(t0);
        tool.setEndTime(t0);
        tool.setSuccess(true);
        tool.setDurationMs(0L);

        stubOwnedSession(sessionId);
        when(traceSpanRepository.findBySessionIdAndSpanTypeOrderByStartTimeAsc(
                eq(sessionId), eq("TOOL_CALL"))).thenReturn(List.of(tool));

        List<SpanSummaryDto> merged = service.listMergedSpans(
                sessionId, USER_ID, null, 200, java.util.Set.of("tool"));

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).kind()).isEqualTo("tool");
        org.mockito.Mockito.verify(traceStore, org.mockito.Mockito.never())
                .listSpansBySession(anyString(), any(), anyInt());
    }

    @SuppressWarnings("unused")
    private static Map<String, Object> emptyMap() {
        return Map.of();
    }
}
