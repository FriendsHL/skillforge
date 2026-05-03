package com.skillforge.core.engine;

import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.skill.SkillRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OBS-3 BE — locks in the unified WS broadcast in {@link AgentLoopEngine#finalizeTraceSafe}.
 *
 * <p>The engine has 7 distinct {@code finalizeTraceSafe} call sites covering 10 logical
 * paths (cancelled / budget / duration / max_tokens / max_loops / normal / abortToolUse /
 * ASK_USER waiting / INSTALL_CONFIRM waiting / AGENT_CONFIRM waiting). Centralizing the
 * broadcast inside {@code finalizeTraceSafe} guarantees every path emits exactly one
 * {@code trace_finalized} event without each call site having to remember.
 *
 * <p>This test invokes the private method via reflection (no public hook exists) and
 * asserts both the lifecycle sink AND the broadcaster are notified, with broadcaster
 * exceptions swallowed.
 */
class AgentLoopEngineTraceFinalizedBroadcastTest {

    private AgentLoopEngine engine;
    private CapturingBroadcaster broadcaster;
    private CapturingSink sink;

    @BeforeEach
    void setUp() {
        engine = new AgentLoopEngine(
                new LlmProviderFactory(),
                "unused",
                new SkillRegistry(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
        broadcaster = new CapturingBroadcaster();
        sink = new CapturingSink();
        engine.setBroadcaster(broadcaster);
        engine.setTraceLifecycleSink(sink);
    }

    private LoopContext ctx(String sessionId, String traceId) {
        LoopContext c = new LoopContext();
        c.setSessionId(sessionId);
        c.setTraceId(traceId);
        c.setMessages(new ArrayList<>());
        return c;
    }

    private void invokeFinalize(LoopContext loopCtx, String status, String error,
                                long startedAtMs,
                                AtomicInteger toolCalls, AtomicInteger events) throws Exception {
        Method m = AgentLoopEngine.class.getDeclaredMethod(
                "finalizeTraceSafe", LoopContext.class, String.class, String.class,
                long.class, AtomicInteger.class, AtomicInteger.class);
        m.setAccessible(true);
        m.invoke(engine, loopCtx, status, error, startedAtMs, toolCalls, events);
    }

    @Test
    @DisplayName("finalizeTraceSafe emits trace_finalized broadcast for ok status")
    void finalize_okStatus_broadcasts() throws Exception {
        LoopContext c = ctx("session-1", "trace-1");
        invokeFinalize(c, "ok", null,
                System.currentTimeMillis() - 1500,
                new AtomicInteger(3), new AtomicInteger(2));

        assertThat(broadcaster.events).hasSize(1);
        Map<String, Object> ev = broadcaster.events.get(0);
        assertThat(ev.get("sessionId")).isEqualTo("session-1");
        assertThat(ev.get("traceId")).isEqualTo("trace-1");
        assertThat(ev.get("status")).isEqualTo("ok");
        assertThat(ev.get("error")).isNull();
        assertThat(ev.get("toolCallCount")).isEqualTo(3);
        assertThat(ev.get("eventCount")).isEqualTo(2);
        assertThat((Long) ev.get("totalDurationMs")).isGreaterThanOrEqualTo(1500L);
        // Lifecycle sink also called.
        assertThat(sink.calls).hasSize(1);
    }

    @Test
    @DisplayName("finalizeTraceSafe emits broadcast for error / cancelled status with error string")
    void finalize_errorStatus_broadcastsError() throws Exception {
        invokeFinalize(ctx("s2", "t2"), "error", "max_loops",
                System.currentTimeMillis(),
                new AtomicInteger(0), new AtomicInteger(0));

        assertThat(broadcaster.events).hasSize(1);
        assertThat(broadcaster.events.get(0).get("status")).isEqualTo("error");
        assertThat(broadcaster.events.get(0).get("error")).isEqualTo("max_loops");
    }

    @Test
    @DisplayName("finalizeTraceSafe with null traceId is a no-op (no broadcast, no sink call)")
    void finalize_nullTraceId_noop() throws Exception {
        LoopContext c = new LoopContext();
        c.setSessionId("s3");
        c.setMessages(new ArrayList<>());
        // traceId stays null
        invokeFinalize(c, "ok", null,
                System.currentTimeMillis(),
                new AtomicInteger(0), new AtomicInteger(0));

        assertThat(broadcaster.events).isEmpty();
        assertThat(sink.calls).isEmpty();
    }

    @Test
    @DisplayName("broadcaster exception is swallowed and lifecycle sink call still completes")
    void finalize_broadcasterThrows_swallowedSinkCalled() throws Exception {
        broadcaster.throwOnNext = true;
        invokeFinalize(ctx("s4", "t4"), "ok", null,
                System.currentTimeMillis(),
                new AtomicInteger(1), new AtomicInteger(0));

        // Sink called even though broadcaster threw.
        assertThat(sink.calls).hasSize(1);
    }

    @Test
    @DisplayName("lifecycle sink exception swallowed; broadcaster still fires")
    void finalize_sinkThrows_swallowedBroadcasterFires() throws Exception {
        sink.throwOnNext = true;
        invokeFinalize(ctx("s5", "t5"), "cancelled", "cancelled",
                System.currentTimeMillis(),
                new AtomicInteger(0), new AtomicInteger(0));

        assertThat(broadcaster.events).hasSize(1);
        assertThat(broadcaster.events.get(0).get("status")).isEqualTo("cancelled");
    }

    // ===== Stubs =====

    private static class CapturingBroadcaster implements ChatEventBroadcaster {
        final List<Map<String, Object>> events = new ArrayList<>();
        boolean throwOnNext = false;

        @Override
        public void sessionStatus(String sessionId, String status, String step, String error) {}

        @Override
        public void messageAppended(String sessionId, String traceId, com.skillforge.core.model.Message message) {}

        @Override
        public void askUser(String sessionId, AskUserEvent event) {}

        @Override
        public void traceFinalized(String sessionId, String traceId, String status, String error,
                                   long totalDurationMs, int toolCallCount, int eventCount) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("sessionId", sessionId);
            ev.put("traceId", traceId);
            ev.put("status", status);
            ev.put("error", error);
            ev.put("totalDurationMs", totalDurationMs);
            ev.put("toolCallCount", toolCallCount);
            ev.put("eventCount", eventCount);
            events.add(ev);
            if (throwOnNext) {
                throwOnNext = false;
                throw new RuntimeException("simulated WS outage");
            }
        }
    }

    private static class CapturingSink implements TraceLifecycleSink {
        final List<Object[]> calls = new ArrayList<>();
        boolean throwOnNext = false;

        @Override
        public void upsertTraceStub(String traceId, String sessionId, Long agentId, Long userId,
                                    String agentName, Instant startedAt) {}

        @Override
        public void writeToolSpan(String spanId, String traceId, String parentSpanId,
                                  String sessionId, Long agentId, String name, String toolUseId,
                                  String inputSummary, String outputSummary,
                                  Instant startedAt, Instant endedAt, long latencyMs,
                                  int iterationIndex, boolean success, String error) {}

        @Override
        public void writeEventSpan(String spanId, String traceId, String parentSpanId,
                                   String sessionId, Long agentId, String eventType, String name,
                                   String inputSummary, String outputSummary,
                                   Instant startedAt, Instant endedAt, long latencyMs,
                                   int iterationIndex, boolean success, String error) {}

        @Override
        public void finalizeTrace(String traceId, String status, String error,
                                  long totalDurationMs, int toolCallCount, int eventCount,
                                  Instant endedAt) {
            calls.add(new Object[] { traceId, status, error, totalDurationMs, toolCallCount, eventCount });
            if (throwOnNext) {
                throwOnNext = false;
                throw new RuntimeException("simulated DB outage");
            }
        }
    }
}
