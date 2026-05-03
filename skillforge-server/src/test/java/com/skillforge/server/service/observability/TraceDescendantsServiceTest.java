package com.skillforge.server.service.observability;

import com.skillforge.observability.api.LlmTraceStore;
import com.skillforge.observability.domain.LlmSpan;
import com.skillforge.observability.domain.LlmSpanSource;
import com.skillforge.observability.domain.LlmTrace;
import com.skillforge.server.controller.observability.ObservabilityOwnershipGuard;
import com.skillforge.server.controller.observability.dto.DescendantTraceDto;
import com.skillforge.server.controller.observability.dto.TraceWithDescendantsDto;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TraceDescendantsServiceTest {

    @Mock
    private LlmTraceStore traceStore;

    @Mock
    private SessionRepository sessionRepository;

    @Mock
    private ObservabilityOwnershipGuard ownershipGuard;

    @Mock
    private SubagentSessionResolver subagentResolver;

    private TraceDescendantsService service;

    private static final Long USER_ID = 42L;
    private static final Instant T0 = Instant.parse("2026-05-03T10:00:00Z");

    @BeforeEach
    void setUp() {
        service = new TraceDescendantsService(traceStore, sessionRepository,
                ownershipGuard, subagentResolver);
        // Tolerate unused stubbing across tests (each scenario only exercises a subset).
        lenient().when(subagentResolver.resolve(any(LlmSpan.class))).thenReturn(null);
    }

    private LlmTrace trace(String traceId, String sessionId, String agentName, Instant startedAt,
                           String status) {
        return new LlmTrace(
                traceId, sessionId, 1L, USER_ID, agentName,
                startedAt, startedAt.plusSeconds(1),
                0, 0, BigDecimal.ZERO, LlmSpanSource.LIVE,
                status, null, 1000L, 0, 0, agentName);
    }

    private SessionEntity session(String id, String parentId, Instant createdAt) {
        SessionEntity s = new SessionEntity();
        s.setId(id);
        s.setUserId(USER_ID);
        s.setParentSessionId(parentId);
        s.setCreatedAt(createdAt.atZone(java.time.ZoneId.systemDefault()).toLocalDateTime());
        return s;
    }

    private LlmSpan toolSpan(String spanId, String traceId, String name, String output,
                             Instant startedAt) {
        return new LlmSpan(
                spanId, traceId, null, "session-x", 1L,
                "skillforge", null, 0, false,
                "input", output,
                null, null, null, "ok",
                0, 0, null, null, BigDecimal.ZERO, 100L,
                startedAt, startedAt.plusMillis(100),
                null, null, null, null, null,
                "tu_" + spanId, Map.of(), LlmSpanSource.LIVE,
                "tool", null, name);
        }

    private LlmSpan llmSpan(String spanId, String traceId, Instant startedAt) {
        return new LlmSpan(
                spanId, traceId, null, "session-x", 1L,
                "skillforge", "claude-sonnet", 0, false,
                "input", "output",
                null, null, null, "ok",
                10, 5, null, null, BigDecimal.ONE, 200L,
                startedAt, startedAt.plusMillis(200),
                "stop", null, null, null, null,
                null, Map.of(), LlmSpanSource.LIVE,
                "llm", null, null);
    }

    // ============== DFS depth tests ==============

    @Test
    @DisplayName("fetch returns depth=0 only when root has no children (1 layer)")
    void fetch_singleLayer_returnsRootOnly() {
        LlmTrace root = trace("t-root", "s-root", "agent", T0, "ok");
        when(traceStore.readByTraceId("t-root"))
                .thenReturn(java.util.Optional.of(new LlmTraceStore.TraceWithSpans(root,
                        List.of(llmSpan("sp-1", "t-root", T0)))));
        when(sessionRepository.findByParentSessionIdOrderByCreatedAtAsc("s-root"))
                .thenReturn(List.of());

        TraceWithDescendantsDto dto = service.fetch("t-root", 3, 20, USER_ID);

        assertThat(dto.descendants()).isEmpty();
        assertThat(dto.spans()).hasSize(1);
        assertThat(dto.spans().get(0).depth()).isEqualTo(0);
        assertThat(dto.spans().get(0).parentTraceId()).isNull();
        assertThat(dto.truncated()).isFalse();
        assertThat(dto.rootTrace().traceId()).isEqualTo("t-root");
    }

    @Test
    @DisplayName("fetch returns depth=1 children when root has 2 child sessions")
    void fetch_twoLayers_includesChildren() {
        LlmTrace root = trace("t-root", "s-root", "main", T0, "ok");
        LlmTrace c1 = trace("t-c1", "s-c1", "agent-a", T0.plusSeconds(2), "ok");
        LlmTrace c2 = trace("t-c2", "s-c2", "agent-b", T0.plusSeconds(3), "running");

        when(traceStore.readByTraceId("t-root"))
                .thenReturn(java.util.Optional.of(new LlmTraceStore.TraceWithSpans(root,
                        List.of(toolSpan("sp-dispatch-1", "t-root", "TeamCreate",
                                        "  childSessionId: s-c1\n  runId: r1\n", T0.plusSeconds(1)),
                                toolSpan("sp-dispatch-2", "t-root", "TeamCreate",
                                        "  childSessionId: s-c2\n  runId: r2\n", T0.plusMillis(1500))))));
        when(sessionRepository.findByParentSessionIdOrderByCreatedAtAsc("s-root"))
                .thenReturn(List.of(session("s-c1", "s-root", T0.plusSeconds(2)),
                                    session("s-c2", "s-root", T0.plusSeconds(3))));
        when(sessionRepository.findByParentSessionIdOrderByCreatedAtAsc("s-c1")).thenReturn(List.of());
        when(sessionRepository.findByParentSessionIdOrderByCreatedAtAsc("s-c2")).thenReturn(List.of());

        when(traceStore.listTracesBySessionAsc("s-c1", 1)).thenReturn(List.of(c1));
        when(traceStore.listTracesBySessionAsc("s-c2", 1)).thenReturn(List.of(c2));

        // resolveDispatchSpan filters tool kind only.
        when(traceStore.listSpansByTrace(eq("t-root"), eq(Set.of("tool")), anyInt()))
                .thenReturn(List.of(
                        toolSpan("sp-dispatch-1", "t-root", "TeamCreate",
                                "  childSessionId: s-c1\n  runId: r1\n", T0.plusSeconds(1)),
                        toolSpan("sp-dispatch-2", "t-root", "TeamCreate",
                                "  childSessionId: s-c2\n  runId: r2\n", T0.plusMillis(1500))));

        // child trace spans
        when(traceStore.listSpansByTrace(eq("t-c1"), any(), anyInt()))
                .thenReturn(List.of(llmSpan("sp-c1-1", "t-c1", T0.plusSeconds(2))));
        when(traceStore.listSpansByTrace(eq("t-c2"), any(), anyInt()))
                .thenReturn(List.of(llmSpan("sp-c2-1", "t-c2", T0.plusSeconds(3))));

        TraceWithDescendantsDto dto = service.fetch("t-root", 3, 20, USER_ID);

        assertThat(dto.descendants()).hasSize(2);
        assertThat(dto.descendants()).extracting(DescendantTraceDto::depth).containsExactly(1, 1);
        assertThat(dto.descendants()).extracting(DescendantTraceDto::parentTraceId)
                .containsExactly("t-root", "t-root");
        assertThat(dto.descendants().get(0).parentSpanId()).isEqualTo("sp-dispatch-1");
        assertThat(dto.descendants().get(1).parentSpanId()).isEqualTo("sp-dispatch-2");
        // 2 dispatch tool spans (depth 0) + 2 child llm spans (depth 1) = 4
        assertThat(dto.spans()).hasSize(4);
        // sorted by startedAt: sp-dispatch-1 (1s) → sp-dispatch-2 (1.5s) → sp-c1-1 (2s) → sp-c2-1 (3s)
        assertThat(dto.spans()).extracting(s -> s.span().spanId())
                .containsExactly("sp-dispatch-1", "sp-dispatch-2", "sp-c1-1", "sp-c2-1");
        assertThat(dto.truncated()).isFalse();
    }

    @Test
    @DisplayName("fetch returns depth=2 grandchild trace under direct child")
    void fetch_threeLayers_grandchildVisible() {
        LlmTrace root = trace("t-root", "s-root", "main", T0, "ok");
        LlmTrace c1 = trace("t-c1", "s-c1", "agent-a", T0.plusSeconds(2), "ok");
        LlmTrace gc1 = trace("t-gc1", "s-gc1", "agent-aa", T0.plusSeconds(4), "ok");

        when(traceStore.readByTraceId("t-root"))
                .thenReturn(java.util.Optional.of(new LlmTraceStore.TraceWithSpans(root, List.of())));
        when(sessionRepository.findByParentSessionIdOrderByCreatedAtAsc("s-root"))
                .thenReturn(List.of(session("s-c1", "s-root", T0.plusSeconds(2))));
        when(sessionRepository.findByParentSessionIdOrderByCreatedAtAsc("s-c1"))
                .thenReturn(List.of(session("s-gc1", "s-c1", T0.plusSeconds(4))));
        when(sessionRepository.findByParentSessionIdOrderByCreatedAtAsc("s-gc1")).thenReturn(List.of());

        when(traceStore.listTracesBySessionAsc("s-c1", 1)).thenReturn(List.of(c1));
        when(traceStore.listTracesBySessionAsc("s-gc1", 1)).thenReturn(List.of(gc1));

        when(traceStore.listSpansByTrace(any(), any(), anyInt())).thenReturn(List.of());

        TraceWithDescendantsDto dto = service.fetch("t-root", 3, 20, USER_ID);

        assertThat(dto.descendants()).hasSize(2);
        assertThat(dto.descendants().get(0).traceId()).isEqualTo("t-c1");
        assertThat(dto.descendants().get(0).depth()).isEqualTo(1);
        assertThat(dto.descendants().get(0).parentTraceId()).isEqualTo("t-root");
        assertThat(dto.descendants().get(1).traceId()).isEqualTo("t-gc1");
        assertThat(dto.descendants().get(1).depth()).isEqualTo(2);
        assertThat(dto.descendants().get(1).parentTraceId()).isEqualTo("t-c1");
    }

    // ============== max_depth cutoff ==============

    @Test
    @DisplayName("max_depth=1 prunes grandchildren even when they exist")
    void fetch_maxDepth1_prunesGrandchildren() {
        LlmTrace root = trace("t-root", "s-root", "main", T0, "ok");
        LlmTrace c1 = trace("t-c1", "s-c1", "agent-a", T0.plusSeconds(2), "ok");

        when(traceStore.readByTraceId("t-root"))
                .thenReturn(java.util.Optional.of(new LlmTraceStore.TraceWithSpans(root, List.of())));
        when(sessionRepository.findByParentSessionIdOrderByCreatedAtAsc("s-root"))
                .thenReturn(List.of(session("s-c1", "s-root", T0.plusSeconds(2))));
        when(traceStore.listTracesBySessionAsc("s-c1", 1)).thenReturn(List.of(c1));
        when(traceStore.listSpansByTrace(any(), any(), anyInt())).thenReturn(List.of());

        TraceWithDescendantsDto dto = service.fetch("t-root", 1, 20, USER_ID);

        assertThat(dto.descendants()).hasSize(1);
        assertThat(dto.descendants().get(0).depth()).isEqualTo(1);
        // sessionRepository.findByParentSessionIdOrderByCreatedAtAsc("s-c1") never called
        // because depth=2 exceeds maxDepth=1; no need to mock it.
    }

    // ============== max_descendants truncate ==============

    @Test
    @DisplayName("max_descendants=2 truncates after 2 children")
    void fetch_maxDescendantsTruncates() {
        LlmTrace root = trace("t-root", "s-root", "main", T0, "ok");
        LlmTrace c1 = trace("t-c1", "s-c1", "agent-a", T0.plusSeconds(1), "ok");
        LlmTrace c2 = trace("t-c2", "s-c2", "agent-b", T0.plusSeconds(2), "ok");
        LlmTrace c3 = trace("t-c3", "s-c3", "agent-c", T0.plusSeconds(3), "ok");

        when(traceStore.readByTraceId("t-root"))
                .thenReturn(java.util.Optional.of(new LlmTraceStore.TraceWithSpans(root, List.of())));
        when(sessionRepository.findByParentSessionIdOrderByCreatedAtAsc("s-root"))
                .thenReturn(List.of(
                        session("s-c1", "s-root", T0.plusSeconds(1)),
                        session("s-c2", "s-root", T0.plusSeconds(2)),
                        session("s-c3", "s-root", T0.plusSeconds(3))));
        when(traceStore.listTracesBySessionAsc("s-c1", 1)).thenReturn(List.of(c1));
        when(traceStore.listTracesBySessionAsc("s-c2", 1)).thenReturn(List.of(c2));
        // listTracesBySessionAsc("s-c3", ...) not stubbed because truncate kicks in first.
        when(traceStore.listSpansByTrace(any(), any(), anyInt())).thenReturn(List.of());
        lenient().when(sessionRepository.findByParentSessionIdOrderByCreatedAtAsc("s-c1"))
                .thenReturn(List.of());
        lenient().when(sessionRepository.findByParentSessionIdOrderByCreatedAtAsc("s-c2"))
                .thenReturn(List.of());

        TraceWithDescendantsDto dto = service.fetch("t-root", 3, 2, USER_ID);

        assertThat(dto.descendants()).hasSize(2);
        assertThat(dto.descendants()).extracting(DescendantTraceDto::traceId)
                .containsExactly("t-c1", "t-c2");
        assertThat(dto.truncated()).isTrue();
    }

    // ============== cycle detection ==============

    @Test
    @DisplayName("cycle: parent_session_id loops back — visited set prevents infinite recursion")
    void fetch_cycleDetected_doesNotRecurseInfinitely() {
        LlmTrace root = trace("t-root", "s-root", "main", T0, "ok");
        LlmTrace c1 = trace("t-c1", "s-c1", "agent-a", T0.plusSeconds(1), "ok");

        when(traceStore.readByTraceId("t-root"))
                .thenReturn(java.util.Optional.of(new LlmTraceStore.TraceWithSpans(root, List.of())));
        when(sessionRepository.findByParentSessionIdOrderByCreatedAtAsc("s-root"))
                .thenReturn(List.of(session("s-c1", "s-root", T0.plusSeconds(1))));
        // s-c1 has a child whose id is s-root — a cycle. visited set should drop it silently.
        when(sessionRepository.findByParentSessionIdOrderByCreatedAtAsc("s-c1"))
                .thenReturn(List.of(session("s-root", "s-c1", T0.plusSeconds(2))));
        when(traceStore.listTracesBySessionAsc("s-c1", 1)).thenReturn(List.of(c1));
        when(traceStore.listSpansByTrace(any(), any(), anyInt())).thenReturn(List.of());

        TraceWithDescendantsDto dto = service.fetch("t-root", 3, 20, USER_ID);

        // Only s-c1 added; the cyclic s-root child is dropped by visited guard.
        assertThat(dto.descendants()).hasSize(1);
        assertThat(dto.descendants().get(0).sessionId()).isEqualTo("s-c1");
    }

    // ============== resolveDispatchSpan null fallback ==============

    @Test
    @DisplayName("resolveDispatchSpan returns null when output text doesn't carry childSessionId")
    void resolveDispatchSpan_noMatch_returnsNull() {
        when(traceStore.listSpansByTrace(eq("t-root"), eq(Set.of("tool")), anyInt()))
                .thenReturn(List.of(toolSpan("sp-1", "t-root", "TeamCreate",
                        "no marker here", T0)));

        String result = service.resolveDispatchSpan("t-root", "s-missing");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("resolveDispatchSpan returns matching span_id when output carries marker")
    void resolveDispatchSpan_matchFound_returnsSpanId() {
        when(traceStore.listSpansByTrace(eq("t-root"), eq(Set.of("tool")), anyInt()))
                .thenReturn(List.of(toolSpan("sp-1", "t-root", "TeamCreate",
                        "  childSessionId: s-target\n  runId: r1\n", T0)));

        String result = service.resolveDispatchSpan("t-root", "s-target");

        assertThat(result).isEqualTo("sp-1");
    }

    @Test
    @DisplayName("resolveDispatchSpan swallows store exceptions and returns null")
    void resolveDispatchSpan_storeThrows_returnsNullNoLeak() {
        when(traceStore.listSpansByTrace(any(), any(), anyInt()))
                .thenThrow(new RuntimeException("DB down"));

        String result = service.resolveDispatchSpan("t-root", "s-target");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("descendant with no resolvable dispatch span has parentSpanId=null but is still rendered")
    void fetch_resolveDispatchMisses_parentSpanIdNull() {
        LlmTrace root = trace("t-root", "s-root", "main", T0, "ok");
        LlmTrace c1 = trace("t-c1", "s-c1", "agent-a", T0.plusSeconds(1), "ok");

        when(traceStore.readByTraceId("t-root"))
                .thenReturn(java.util.Optional.of(new LlmTraceStore.TraceWithSpans(root, List.of())));
        when(sessionRepository.findByParentSessionIdOrderByCreatedAtAsc("s-root"))
                .thenReturn(List.of(session("s-c1", "s-root", T0.plusSeconds(1))));
        lenient().when(sessionRepository.findByParentSessionIdOrderByCreatedAtAsc("s-c1"))
                .thenReturn(List.of());
        when(traceStore.listTracesBySessionAsc("s-c1", 1)).thenReturn(List.of(c1));
        // resolveDispatchSpan: tool spans contain no matching marker
        when(traceStore.listSpansByTrace(eq("t-root"), eq(Set.of("tool")), anyInt()))
                .thenReturn(List.of(toolSpan("sp-1", "t-root", "TeamCreate",
                        "no marker here", T0)));
        // child trace span fetch (kinds=null variant)
        when(traceStore.listSpansByTrace(eq("t-c1"), eq(null), anyInt()))
                .thenReturn(List.of());

        TraceWithDescendantsDto dto = service.fetch("t-root", 3, 20, USER_ID);

        assertThat(dto.descendants()).hasSize(1);
        assertThat(dto.descendants().get(0).parentSpanId()).isNull();
        assertThat(dto.descendants().get(0).traceId()).isEqualTo("t-c1");
    }

    // ============== root-level errors ==============

    @Test
    @DisplayName("fetch returns 404 when traceId not found")
    void fetch_traceMissing_throws404() {
        when(traceStore.readByTraceId("t-missing")).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.fetch("t-missing", 3, 20, USER_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    @DisplayName("fetch returns 400 when traceId blank")
    void fetch_blankTraceId_throws400() {
        assertThatThrownBy(() -> service.fetch(" ", 3, 20, USER_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
    }
}
