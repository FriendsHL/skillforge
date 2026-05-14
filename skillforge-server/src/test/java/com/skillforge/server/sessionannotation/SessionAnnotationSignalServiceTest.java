package com.skillforge.server.sessionannotation;

import com.skillforge.observability.entity.LlmSpanEntity;
import com.skillforge.observability.entity.LlmTraceEntity;
import com.skillforge.observability.repository.LlmSpanRepository;
import com.skillforge.observability.repository.LlmTraceRepository;
import com.skillforge.server.entity.SessionAnnotationEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.SessionAnnotationRepository;
import com.skillforge.server.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PROD-LABEL-CLUSTER V1 (Phase 1.2): Mockito-style unit tests for the signal
 * pipeline. Mocks repository / trace store layers so the tests run without
 * Docker / a real Postgres — matches Phase 1.1's observation that
 * AbstractPostgresIT tests skip locally without Docker.
 *
 * <p>Coverage:
 * <ul>
 *   <li>tool-error trace → tool_failure + has_tool_calls annotations written</li>
 *   <li>idempotent on re-run (UNIQUE constraint hit caught as DataIntegrityViolation)</li>
 *   <li>only origin='production' sessions are scanned (the query filter is
 *       passed through, this test asserts the wiring)</li>
 *   <li>empty result when no sessions completed in the window</li>
 *   <li>guard: window must be positive</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SessionAnnotationSignalService")
class SessionAnnotationSignalServiceTest {

    @Mock private SessionRepository sessionRepository;
    @Mock private LlmTraceRepository llmTraceRepository;
    @Mock private LlmSpanRepository llmSpanRepository;
    @Mock private SessionAnnotationRepository sessionAnnotationRepository;

    private SessionAnnotationSignalService service;

    @BeforeEach
    void setUp() {
        service = new SessionAnnotationSignalService(
                sessionRepository, llmTraceRepository, llmSpanRepository, sessionAnnotationRepository);
    }

    @Test
    @DisplayName("detectAndPersist writes tool_failure + has_tool_calls when trace has tool error")
    void detectAndPersist_writesAnnotations_whenTraceHasToolError() {
        SessionEntity session = session("sess-1", 42L);
        LlmTraceEntity trace = trace("trace-1", "trace-1", "sess-1", 42L, "ok", 0, 0, 1);
        LlmSpanEntity toolErr = span("span-1", "trace-1", "tool", "ok");
        toolErr.setError("Tool execution failed");

        when(sessionRepository.findCompletedByOriginSince(eq("production"), any(Instant.class)))
                .thenReturn(List.of(session));
        when(llmTraceRepository.findBySessionIdAndOriginOrderByStartedAtDesc("sess-1", "production"))
                .thenReturn(List.of(trace));
        when(llmSpanRepository.findByTraceIdInOrderByStartedAtAsc(List.of("trace-1")))
                .thenReturn(List.of(toolErr));
        when(sessionAnnotationRepository.saveAndFlush(any(SessionAnnotationEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        int written = service.detectAndPersist(Duration.ofHours(1));

        assertThat(written).isEqualTo(2);  // tool_failure + has_tool_calls
        ArgumentCaptor<SessionAnnotationEntity> cap = ArgumentCaptor.forClass(SessionAnnotationEntity.class);
        verify(sessionAnnotationRepository, times(2)).saveAndFlush(cap.capture());
        List<String> reasons = cap.getAllValues().stream().map(SessionAnnotationEntity::getAnnotationType).toList();
        assertThat(reasons).containsExactly("tool_failure", "has_tool_calls");

        // Spot-check first row fields per the §4.3 contract.
        SessionAnnotationEntity first = cap.getAllValues().get(0);
        assertThat(first.getSessionId()).isEqualTo("sess-1");
        assertThat(first.getAnnotationValue()).isEqualTo("true");
        assertThat(first.getSource()).isEqualTo("signal");
        assertThat(first.getConfidence().toPlainString()).isEqualTo("1.00");
        assertThat(first.getReasoning()).isNull();
        assertThat(first.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("detectAndPersist is idempotent on re-run — UNIQUE conflict counts as 0 written")
    void detectAndPersist_isIdempotent_onRerun() {
        SessionEntity session = session("sess-2", 42L);
        LlmTraceEntity trace = trace("trace-2", "trace-2", "sess-2", 42L, "error", 0, 0, 0);
        trace.setError("Agent loop failed");  // → agent_error reason

        when(sessionRepository.findCompletedByOriginSince(eq("production"), any(Instant.class)))
                .thenReturn(List.of(session));
        when(llmTraceRepository.findBySessionIdAndOriginOrderByStartedAtDesc("sess-2", "production"))
                .thenReturn(List.of(trace));
        when(llmSpanRepository.findByTraceIdInOrderByStartedAtAsc(List.of("trace-2")))
                .thenReturn(List.of());
        // Simulate "row already exists" — UNIQUE conflict.
        when(sessionAnnotationRepository.saveAndFlush(any(SessionAnnotationEntity.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"uq_session_annotation\""));

        int written = service.detectAndPersist(Duration.ofHours(1));

        // The single agent_error row would have been written, but UNIQUE catches → 0 net writes.
        assertThat(written).isEqualTo(0);
        verify(sessionAnnotationRepository, times(1)).saveAndFlush(any(SessionAnnotationEntity.class));
    }

    @Test
    @DisplayName("detectAndPersist skips eval sessions — only origin='production' is queried")
    void detectAndPersist_skipsEvalSessions() {
        // SessionRepository query is the authoritative filter; this test asserts the
        // service passes 'production' (not 'eval', not null) when scanning. Stubbing
        // the production filter to return empty proves no fallback scan exists.
        when(sessionRepository.findCompletedByOriginSince(eq("production"), any(Instant.class)))
                .thenReturn(List.of());

        int written = service.detectAndPersist(Duration.ofHours(1));

        assertThat(written).isEqualTo(0);
        // No traces / spans / annotations should be touched.
        verifyNoInteractions(llmTraceRepository);
        verifyNoInteractions(llmSpanRepository);
        verify(sessionAnnotationRepository, never()).saveAndFlush(any(SessionAnnotationEntity.class));
    }

    @Test
    @DisplayName("detectAndPersist returns zero when no matching sessions")
    void detectAndPersist_returnsZero_whenNoMatchingSessions() {
        when(sessionRepository.findCompletedByOriginSince(eq("production"), any(Instant.class)))
                .thenReturn(List.of());

        assertThat(service.detectAndPersist(Duration.ofHours(2))).isEqualTo(0);
    }

    @Test
    @DisplayName("detectAndPersist rejects non-positive window — safety guard")
    void detectAndPersist_rejectsZeroOrNegativeWindow() {
        assertThatThrownBy(() -> service.detectAndPersist(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.detectAndPersist(Duration.ofHours(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.detectAndPersist(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("findSessionsNeedingLlmAnnotation filters out sessions already LLM-annotated")
    void findSessionsNeedingLlmAnnotation_filtersAlreadyLlmAnnotated() {
        SessionAnnotationEntity sigA = signalRow("sess-A", "tool_failure");
        SessionAnnotationEntity sigB = signalRow("sess-B", "agent_error");
        SessionAnnotationEntity sigC = signalRow("sess-C", "high_token");

        when(sessionAnnotationRepository.findRecentByLimit(eq("signal"), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(sigA, sigB, sigC));
        when(sessionAnnotationRepository.findSessionIdsWithSource(
                org.mockito.ArgumentMatchers.anyCollection(), eq("llm")))
                .thenReturn(List.of("sess-B"));  // sess-B already LLM-annotated

        SessionEntity sA = session("sess-A", 42L);
        SessionEntity sC = session("sess-C", 7L);
        when(sessionRepository.findAllById(org.mockito.ArgumentMatchers.anyIterable()))
                .thenReturn(new ArrayList<>(List.of(sA, sC)));

        List<SessionAnnotationSignalService.SessionNeedingLlmDto> queue =
                service.findSessionsNeedingLlmAnnotation(10);

        assertThat(queue).hasSize(2);
        assertThat(queue.stream().map(SessionAnnotationSignalService.SessionNeedingLlmDto::sessionId))
                .containsExactly("sess-A", "sess-C");
        assertThat(queue.get(0).agentName()).isEqualTo("agent#42");
        assertThat(queue.get(0).signalReasons()).contains("tool_failure");
    }

    // ────────────────────────────────────────────────────────────────────────
    // helpers
    // ────────────────────────────────────────────────────────────────────────

    private static SessionEntity session(String id, Long agentId) {
        SessionEntity s = new SessionEntity();
        s.setId(id);
        s.setAgentId(agentId);
        s.setOrigin("production");
        s.setCompletedAt(Instant.parse("2026-05-14T08:00:00Z"));
        return s;
    }

    private static LlmTraceEntity trace(String traceId, String rootTraceId, String sessionId, Long agentId,
                                        String status, int inputTokens, int outputTokens, int toolCalls) {
        LlmTraceEntity t = new LlmTraceEntity();
        t.setTraceId(traceId);
        t.setRootTraceId(rootTraceId);
        t.setSessionId(sessionId);
        t.setAgentId(agentId);
        t.setStatus(status);
        t.setStartedAt(Instant.parse("2026-05-14T07:00:00Z"));
        t.setTotalInputTokens(inputTokens);
        t.setTotalOutputTokens(outputTokens);
        t.setToolCallCount(toolCalls);
        return t;
    }

    private static LlmSpanEntity span(String spanId, String traceId, String kind, String finishReason) {
        LlmSpanEntity sp = new LlmSpanEntity();
        sp.setSpanId(spanId);
        sp.setTraceId(traceId);
        sp.setKind(kind);
        sp.setFinishReason(finishReason);
        sp.setStartedAt(Instant.parse("2026-05-14T07:00:01Z"));
        return sp;
    }

    private static SessionAnnotationEntity signalRow(String sessionId, String reason) {
        SessionAnnotationEntity a = new SessionAnnotationEntity();
        a.setSessionId(sessionId);
        a.setAnnotationType(reason);
        a.setAnnotationValue("true");
        a.setSource("signal");
        a.setCreatedAt(Instant.now());
        return a;
    }
}
