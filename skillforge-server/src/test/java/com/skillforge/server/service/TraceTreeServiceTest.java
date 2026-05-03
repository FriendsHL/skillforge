package com.skillforge.server.service;

import com.skillforge.observability.entity.LlmSpanEntity;
import com.skillforge.observability.entity.LlmTraceEntity;
import com.skillforge.observability.repository.LlmSpanRepository;
import com.skillforge.observability.repository.LlmTraceRepository;
import com.skillforge.server.controller.observability.dto.TraceNodeDto;
import com.skillforge.server.controller.observability.dto.TraceSpanDto;
import com.skillforge.server.controller.observability.dto.TraceTreeDto;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * OBS-4 M2 — TraceTreeService unit tests.
 *
 * <p>Covers the assembly logic Mockito-style without booting the JPA layer:
 * <ul>
 *   <li>empty / unknown rootTraceId → Optional.empty (404 path)</li>
 *   <li>single self-rooted trace (legacy session, no children) → 1 node depth=0</li>
 *   <li>parent + child via t_session.parent_session_id → 2 nodes depth 0/1</li>
 *   <li>parent + child + grandchild (Q6 recursive) → 3 nodes depth 0/1/2</li>
 *   <li>span grouping: same root, multiple traces, spans bucketed by traceId</li>
 *   <li>multi-trace same session (INV-3 主 agent 同 user message 内多 trace) → all depth=0</li>
 * </ul>
 *
 * <p>SQL-level coverage of {@code findByRootTraceIdOrderByStartedAtAsc} and
 * {@code findByTraceIdInOrderByStartedAtAsc} is provided by existing zonky ITs
 * (SubAgentSpawnRootTraceIT verifies the by-root SELECT path; PgLlmTraceLifecycleIT
 * exercises generic span lookups). This unit test focuses on the depth algorithm and
 * DTO assembly that runs above the repos.
 */
@ExtendWith(MockitoExtension.class)
class TraceTreeServiceTest {

    @Mock private LlmTraceRepository traceRepository;
    @Mock private LlmSpanRepository spanRepository;
    @Mock private SessionRepository sessionRepository;

    private TraceTreeService service;

    @BeforeEach
    void setUp() {
        service = new TraceTreeService(traceRepository, spanRepository, sessionRepository);
    }

    @Test
    @DisplayName("blank rootTraceId returns empty")
    void blankRoot_returnsEmpty() {
        assertThat(service.getTree(null)).isEmpty();
        assertThat(service.getTree("")).isEmpty();
        assertThat(service.getTree("   ")).isEmpty();
    }

    @Test
    @DisplayName("unknown rootTraceId returns empty (404 path)")
    void unknownRoot_returnsEmpty() {
        when(traceRepository.findByRootTraceIdOrderByStartedAtAsc("unknown"))
                .thenReturn(List.of());
        assertThat(service.getTree("unknown")).isEmpty();
    }

    @Test
    @DisplayName("legacy self-rooted trace → 1 node depth=0, no spans")
    void legacySelfRooted_singleNodeDepthZero() {
        String R = "trace-1";
        LlmTraceEntity t = trace(R, R, "session-1", "Main", Instant.parse("2026-05-03T10:00:00Z"));
        when(traceRepository.findByRootTraceIdOrderByStartedAtAsc(R)).thenReturn(List.of(t));
        when(spanRepository.findByTraceIdInOrderByStartedAtAsc(any(Collection.class)))
                .thenReturn(List.of());
        when(sessionRepository.findAllById(any(Iterable.class)))
                .thenReturn(List.of(session("session-1", null)));

        Optional<TraceTreeDto> tree = service.getTree(R);
        assertThat(tree).isPresent();
        assertThat(tree.get().rootTraceId()).isEqualTo(R);
        assertThat(tree.get().traces()).hasSize(1);
        TraceNodeDto node = tree.get().traces().get(0);
        assertThat(node.traceId()).isEqualTo(R);
        assertThat(node.depth()).isEqualTo(0);
        assertThat(node.parentSessionId()).isNull();
        assertThat(node.spans()).isEmpty();
    }

    @Test
    @DisplayName("parent + child: child depth=1, parentSessionId set")
    void parentAndChild_depthCorrect() {
        String R = "trace-parent";
        LlmTraceEntity parent = trace(R, R, "session-p", "Main",
                Instant.parse("2026-05-03T10:00:00Z"));
        LlmTraceEntity child = trace("trace-child", R, "session-c", "Researcher",
                Instant.parse("2026-05-03T10:00:01Z"));

        when(traceRepository.findByRootTraceIdOrderByStartedAtAsc(R))
                .thenReturn(List.of(parent, child));
        when(spanRepository.findByTraceIdInOrderByStartedAtAsc(any(Collection.class)))
                .thenReturn(List.of());
        when(sessionRepository.findAllById(any(Iterable.class)))
                .thenReturn(List.of(
                        session("session-p", null),
                        session("session-c", "session-p")));

        TraceTreeDto tree = service.getTree(R).orElseThrow();
        assertThat(tree.traces()).hasSize(2);

        TraceNodeDto parentNode = findNode(tree, "trace-parent");
        TraceNodeDto childNode = findNode(tree, "trace-child");
        assertThat(parentNode.depth()).isEqualTo(0);
        assertThat(parentNode.parentSessionId()).isNull();
        assertThat(childNode.depth()).isEqualTo(1);
        assertThat(childNode.parentSessionId()).isEqualTo("session-p");
        assertThat(childNode.agentName()).isEqualTo("Researcher");
    }

    @Test
    @DisplayName("parent + child + grandchild (Q6 recursion): depths 0/1/2")
    void grandchild_depthRecursive() {
        String R = "trace-parent";
        LlmTraceEntity p = trace(R, R, "s-p", "P", Instant.parse("2026-05-03T10:00:00Z"));
        LlmTraceEntity c = trace("trace-c", R, "s-c", "C", Instant.parse("2026-05-03T10:00:01Z"));
        LlmTraceEntity g = trace("trace-g", R, "s-g", "G", Instant.parse("2026-05-03T10:00:02Z"));

        when(traceRepository.findByRootTraceIdOrderByStartedAtAsc(R))
                .thenReturn(List.of(p, c, g));
        when(spanRepository.findByTraceIdInOrderByStartedAtAsc(any(Collection.class)))
                .thenReturn(List.of());
        when(sessionRepository.findAllById(any(Iterable.class)))
                .thenReturn(List.of(
                        session("s-p", null),
                        session("s-c", "s-p"),
                        session("s-g", "s-c")));

        TraceTreeDto tree = service.getTree(R).orElseThrow();
        assertThat(findNode(tree, "trace-parent").depth()).isEqualTo(0);
        assertThat(findNode(tree, "trace-c").depth()).isEqualTo(1);
        assertThat(findNode(tree, "trace-g").depth()).isEqualTo(2);
    }

    @Test
    @DisplayName("INV-3: 主 agent 同 user message 内多 trace 共享 root，全 depth=0（同 session）")
    void mainAgentMultipleTracesSameSession_allDepthZero() {
        String R = "trace-1";
        LlmTraceEntity t1 = trace(R, R, "s-main", "Main", Instant.parse("2026-05-03T10:00:00Z"));
        LlmTraceEntity t2 = trace("trace-2", R, "s-main", "Main", Instant.parse("2026-05-03T10:00:05Z"));

        when(traceRepository.findByRootTraceIdOrderByStartedAtAsc(R))
                .thenReturn(List.of(t1, t2));
        when(spanRepository.findByTraceIdInOrderByStartedAtAsc(any(Collection.class)))
                .thenReturn(List.of());
        when(sessionRepository.findAllById(any(Iterable.class)))
                .thenReturn(List.of(session("s-main", null)));

        TraceTreeDto tree = service.getTree(R).orElseThrow();
        assertThat(tree.traces()).hasSize(2);
        assertThat(tree.traces().get(0).depth()).isEqualTo(0);
        assertThat(tree.traces().get(1).depth()).isEqualTo(0);
    }

    @Test
    @DisplayName("toolCallCount / eventCount propagate from t_llm_trace columns")
    void toolAndEventCountsPropagate() {
        String R = "trace-1";
        LlmTraceEntity t = trace(R, R, "s-1", "Main", Instant.parse("2026-05-03T10:00:00Z"));
        t.setToolCallCount(5);
        t.setEventCount(2);
        when(traceRepository.findByRootTraceIdOrderByStartedAtAsc(R)).thenReturn(List.of(t));
        when(spanRepository.findByTraceIdInOrderByStartedAtAsc(any(Collection.class)))
                .thenReturn(List.of());
        when(sessionRepository.findAllById(any(Iterable.class)))
                .thenReturn(List.of(session("s-1", null)));

        TraceNodeDto node = service.getTree(R).orElseThrow().traces().get(0);
        assertThat(node.toolCallCount()).isEqualTo(5);
        assertThat(node.eventCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("agentName falls back to rootName when null")
    void agentNameFallsBackToRootName() {
        String R = "trace-1";
        LlmTraceEntity t = trace(R, R, "s-1", null, Instant.parse("2026-05-03T10:00:00Z"));
        t.setRootName("LegacyRootName");   // agent_name=null but root_name set (OBS-1 legacy rows)
        when(traceRepository.findByRootTraceIdOrderByStartedAtAsc(R)).thenReturn(List.of(t));
        when(spanRepository.findByTraceIdInOrderByStartedAtAsc(any(Collection.class)))
                .thenReturn(List.of());
        when(sessionRepository.findAllById(any(Iterable.class)))
                .thenReturn(List.of(session("s-1", null)));

        TraceNodeDto node = service.getTree(R).orElseThrow().traces().get(0);
        assertThat(node.agentName()).isEqualTo("LegacyRootName");
    }

    @Test
    @DisplayName("missing-session fallback: depth=0, parentSessionId=null")
    void missingSession_depthZeroNullParent() {
        String R = "trace-1";
        LlmTraceEntity t = trace(R, R, "s-orphan", "Main", Instant.parse("2026-05-03T10:00:00Z"));
        when(traceRepository.findByRootTraceIdOrderByStartedAtAsc(R)).thenReturn(List.of(t));
        when(spanRepository.findByTraceIdInOrderByStartedAtAsc(any(Collection.class)))
                .thenReturn(List.of());
        // session deleted / never persisted — findAllById returns nothing
        when(sessionRepository.findAllById(any(Iterable.class))).thenReturn(List.of());

        TraceNodeDto node = service.getTree(R).orElseThrow().traces().get(0);
        assertThat(node.depth()).isEqualTo(0);
        assertThat(node.parentSessionId()).isNull();
    }

    @Test
    @DisplayName("cycle in parent_session_id falls back to depth=0 (defensive)")
    void cycleInParentSessionId_fallsBackToDepthZero() {
        String R = "trace-1";
        LlmTraceEntity t1 = trace(R, R, "s-a", "A", Instant.parse("2026-05-03T10:00:00Z"));
        LlmTraceEntity t2 = trace("trace-2", R, "s-b", "B", Instant.parse("2026-05-03T10:00:01Z"));
        when(traceRepository.findByRootTraceIdOrderByStartedAtAsc(R)).thenReturn(List.of(t1, t2));
        when(spanRepository.findByTraceIdInOrderByStartedAtAsc(any(Collection.class)))
                .thenReturn(List.of());
        // s-a's parent = s-b, s-b's parent = s-a — cycle
        when(sessionRepository.findAllById(any(Iterable.class)))
                .thenReturn(List.of(
                        session("s-a", "s-b"),
                        session("s-b", "s-a")));

        TraceTreeDto tree = service.getTree(R).orElseThrow();
        // Both should fall back to depth=0 rather than maxHops (3) — defensive bug guard.
        assertThat(tree.traces()).allSatisfy(node ->
                assertThat(node.depth())
                        .as("cycle defence: depth fallback to 0")
                        .isEqualTo(0));
    }

    @Test
    @DisplayName("span status derived from error column (LlmSpanEntity has no status)")
    void spanStatusDerivedFromError() {
        String R = "trace-1";
        LlmTraceEntity p = trace(R, R, "s-1", "Main", Instant.parse("2026-05-03T10:00:00Z"));
        LlmSpanEntity okSpan = span("span-ok", R, "llm", "claude");
        okSpan.setError(null);
        LlmSpanEntity errSpan = span("span-err", R, "tool", "Bash");
        errSpan.setError("non-zero exit code 1");

        when(traceRepository.findByRootTraceIdOrderByStartedAtAsc(R)).thenReturn(List.of(p));
        when(spanRepository.findByTraceIdInOrderByStartedAtAsc(any(Collection.class)))
                .thenReturn(List.of(okSpan, errSpan));
        when(sessionRepository.findAllById(any(Iterable.class)))
                .thenReturn(List.of(session("s-1", null)));

        List<TraceSpanDto> spans = service.getTree(R).orElseThrow().traces().get(0).spans();
        TraceSpanDto ok = spans.stream().filter(s -> "span-ok".equals(s.spanId())).findFirst().orElseThrow();
        TraceSpanDto err = spans.stream().filter(s -> "span-err".equals(s.spanId())).findFirst().orElseThrow();
        assertThat(ok.status()).isEqualTo("ok");
        assertThat(ok.error()).isNull();
        assertThat(err.status()).isEqualTo("error");
        assertThat(err.error()).isEqualTo("non-zero exit code 1");
    }

    @Test
    @DisplayName("spans bucketed by traceId; llmCallCount counted per kind=llm")
    void spansGroupedByTraceId_llmCountFromSpans() {
        String R = "trace-1";
        LlmTraceEntity p = trace(R, R, "s-p", "Main", Instant.parse("2026-05-03T10:00:00Z"));
        LlmTraceEntity c = trace("trace-c", R, "s-c", "Sub", Instant.parse("2026-05-03T10:00:01Z"));

        LlmSpanEntity pLlm = span("span-pl", R, "llm", "claude-opus");
        LlmSpanEntity pTool = span("span-pt", R, "tool", "Bash");
        LlmSpanEntity cLlm1 = span("span-cl1", "trace-c", "llm", "deepseek");
        LlmSpanEntity cLlm2 = span("span-cl2", "trace-c", "llm", "deepseek");
        LlmSpanEntity cEvent = span("span-ce", "trace-c", "event", null);

        when(traceRepository.findByRootTraceIdOrderByStartedAtAsc(R))
                .thenReturn(List.of(p, c));
        when(spanRepository.findByTraceIdInOrderByStartedAtAsc(any(Collection.class)))
                .thenReturn(List.of(pLlm, pTool, cLlm1, cLlm2, cEvent));
        when(sessionRepository.findAllById(any(Iterable.class)))
                .thenReturn(List.of(
                        session("s-p", null),
                        session("s-c", "s-p")));

        TraceTreeDto tree = service.getTree(R).orElseThrow();
        TraceNodeDto parentNode = findNode(tree, "trace-1");
        TraceNodeDto childNode = findNode(tree, "trace-c");
        assertThat(parentNode.spans()).hasSize(2);
        assertThat(parentNode.llmCallCount()).isEqualTo(1);
        assertThat(childNode.spans()).hasSize(3);
        assertThat(childNode.llmCallCount()).isEqualTo(2);

        // span-level field 验证
        TraceSpanDto pLlmDto = parentNode.spans().stream()
                .filter(s -> "llm".equals(s.kind())).findFirst().orElseThrow();
        assertThat(pLlmDto.spanId()).isEqualTo("span-pl");
        assertThat(pLlmDto.model()).isEqualTo("claude-opus");
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private static LlmTraceEntity trace(String traceId, String rootTraceId, String sessionId,
                                        String agentName, Instant startedAt) {
        LlmTraceEntity t = new LlmTraceEntity();
        t.setTraceId(traceId);
        t.setRootTraceId(rootTraceId);
        t.setSessionId(sessionId);
        t.setAgentName(agentName);
        t.setRootName(agentName);
        t.setStartedAt(startedAt);
        t.setStatus("ok");
        return t;
    }

    private static SessionEntity session(String sessionId, String parentSessionId) {
        SessionEntity s = new SessionEntity();
        s.setId(sessionId);
        s.setUserId(1L);
        s.setAgentId(1L);
        s.setParentSessionId(parentSessionId);
        return s;
    }

    private static LlmSpanEntity span(String spanId, String traceId, String kind, String model) {
        LlmSpanEntity s = new LlmSpanEntity();
        s.setSpanId(spanId);
        s.setTraceId(traceId);
        s.setKind(kind);
        s.setModel(model);
        s.setStartedAt(Instant.parse("2026-05-03T10:00:00Z"));
        return s;
    }

    private static TraceNodeDto findNode(TraceTreeDto tree, String traceId) {
        return tree.traces().stream()
                .filter(n -> traceId.equals(n.traceId())).findFirst().orElseThrow();
    }
}
