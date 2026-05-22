package com.skillforge.server.flywheel;

import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.OptimizationEventRepository;
import com.skillforge.server.service.SessionService;
import com.skillforge.server.websocket.UserWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FLYWHEEL-PER-AGENT-RUN-NOW — unit tests for the polling-based chain
 * orchestrator. We directly invoke {@link FlywheelChainOrchestrator#tick()}
 * (visible-for-test) so the test does not depend on Spring scheduler timing.
 *
 * <p>The fake {@link Clock} lets us walk the TTL boundary without sleeping.
 */
@DisplayName("FlywheelChainOrchestrator")
class FlywheelChainOrchestratorTest {

    private SessionService sessionService;
    private OptimizationEventRepository optimizationEventRepository;
    private UserWebSocketHandler userWebSocketHandler;
    private MutableClock clock;
    private FlywheelChainOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        sessionService = mock(SessionService.class);
        optimizationEventRepository = mock(OptimizationEventRepository.class);
        userWebSocketHandler = mock(UserWebSocketHandler.class);
        clock = new MutableClock(Instant.parse("2026-05-21T10:00:00Z"));
        orchestrator = new FlywheelChainOrchestrator(
                sessionService,
                optimizationEventRepository,
                userWebSocketHandler,
                clock);
    }

    @Test
    @DisplayName("registerAnnotatorEndHook: rejects blank sessionId / null Runnable")
    void register_validatesInput() {
        assertThatThrownBy(() -> orchestrator.registerAnnotatorEndHook("", () -> {}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> orchestrator.registerAnnotatorEndHook(null, () -> {}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> orchestrator.registerAnnotatorEndHook("sess-A", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("tick: annotator session still 'running' → hook NOT fired, stays pending")
    void tick_sessionStillRunning_hookNotFired() {
        AtomicInteger callCount = new AtomicInteger(0);
        orchestrator.registerAnnotatorEndHook("sess-running", callCount::incrementAndGet);
        assertThat(orchestrator.isPending("sess-running")).isTrue();

        when(sessionService.getSession(eq("sess-running")))
                .thenReturn(session("sess-running", "running"));

        orchestrator.tick();

        assertThat(callCount.get()).isEqualTo(0);
        assertThat(orchestrator.isPending("sess-running")).isTrue();
    }

    @Test
    @DisplayName("tick: annotator session 'waiting_user' (non-terminal) → hook NOT fired, stays pending (r2 F4 java-reviewer W-5)")
    void tick_sessionWaitingUser_hookNotFired() {
        // session-annotator never asks the user — but if a future session-
        // annotator variant or test-injection landed it in 'waiting_user',
        // orchestrator must treat it as non-terminal (only 'idle' / 'error'
        // qualify). Closes the test gap for the 4th runtimeStatus value.
        AtomicInteger callCount = new AtomicInteger(0);
        orchestrator.registerAnnotatorEndHook("sess-waiting", callCount::incrementAndGet);
        assertThat(orchestrator.isPending("sess-waiting")).isTrue();

        when(sessionService.getSession(eq("sess-waiting")))
                .thenReturn(session("sess-waiting", "waiting_user"));

        orchestrator.tick();

        assertThat(callCount.get()).isEqualTo(0);
        assertThat(orchestrator.isPending("sess-waiting")).isTrue();
    }

    @Test
    @DisplayName("tick: annotator session 'idle' (terminal) → hook fires exactly once + map cleared")
    void tick_sessionIdle_hookFiresOnceAndRemoved() {
        AtomicInteger callCount = new AtomicInteger(0);
        orchestrator.registerAnnotatorEndHook("sess-A", callCount::incrementAndGet);

        when(sessionService.getSession(eq("sess-A"))).thenReturn(session("sess-A", "idle"));

        orchestrator.tick();
        assertThat(callCount.get()).isEqualTo(1);
        assertThat(orchestrator.isPending("sess-A")).isFalse();
        assertThat(orchestrator.pendingSize()).isEqualTo(0);

        // Second tick should NOT re-fire — hook is gone from the map.
        orchestrator.tick();
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("tick: annotator session 'error' is terminal too → hook fires")
    void tick_sessionError_hookFires() {
        AtomicInteger callCount = new AtomicInteger(0);
        orchestrator.registerAnnotatorEndHook("sess-err", callCount::incrementAndGet);

        when(sessionService.getSession(eq("sess-err"))).thenReturn(session("sess-err", "error"));

        orchestrator.tick();
        assertThat(callCount.get()).isEqualTo(1);
        assertThat(orchestrator.isPending("sess-err")).isFalse();
    }

    @Test
    @DisplayName("tick: SessionService throws → hook dropped (don't infinite-retry on a dead session)")
    void tick_sessionLookupThrows_hookDroppedDontLeak() {
        AtomicInteger callCount = new AtomicInteger(0);
        orchestrator.registerAnnotatorEndHook("sess-gone", callCount::incrementAndGet);

        when(sessionService.getSession(eq("sess-gone")))
                .thenThrow(new RuntimeException("SessionNotFoundException stub"));

        orchestrator.tick();
        assertThat(callCount.get()).isEqualTo(0);
        assertThat(orchestrator.isPending("sess-gone")).isFalse();
    }

    @Test
    @DisplayName("tick: hook older than TTL → expired without firing")
    void tick_ttlExpired_hookRemovedSilently() {
        AtomicInteger callCount = new AtomicInteger(0);
        orchestrator.registerAnnotatorEndHook("sess-stuck", callCount::incrementAndGet);

        // Advance clock past TTL — no need for any sessionService stub because
        // the TTL check should sweep the hook before lookup.
        clock.advance(FlywheelChainOrchestrator.HOOK_TTL.plusSeconds(1));

        orchestrator.tick();
        assertThat(callCount.get()).isEqualTo(0);
        assertThat(orchestrator.isPending("sess-stuck")).isFalse();
    }

    @Test
    @DisplayName("registerAnnotatorEndHook: re-register same sessionId replaces prior hook")
    void register_reregisterReplaces() {
        AtomicInteger first = new AtomicInteger(0);
        AtomicInteger second = new AtomicInteger(0);
        orchestrator.registerAnnotatorEndHook("sess-A", first::incrementAndGet);
        orchestrator.registerAnnotatorEndHook("sess-A", second::incrementAndGet);

        when(sessionService.getSession(eq("sess-A"))).thenReturn(session("sess-A", "idle"));

        orchestrator.tick();
        assertThat(first.get()).isEqualTo(0);
        assertThat(second.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("tick: onComplete Runnable throws → exception swallowed, map still cleared")
    void tick_onCompleteThrows_doesNotPropagate_andMapCleared() {
        orchestrator.registerAnnotatorEndHook("sess-bad", () -> {
            throw new RuntimeException("hook impl bug");
        });
        when(sessionService.getSession(eq("sess-bad"))).thenReturn(session("sess-bad", "idle"));

        // Should not throw — orchestrator's tick swallows RuntimeException
        // from onComplete so one bad hook doesn't poison subsequent ticks.
        orchestrator.tick();

        assertThat(orchestrator.isPending("sess-bad")).isFalse();
    }

    // ─── FLYWHEEL-CHAIN-VISIBILITY (2026-05-22) dispatcher-hook tests ──────

    @Test
    @DisplayName("registerDispatcherHook: rejects blank dispatcherSessionId / null agentId / null startedAt")
    void registerDispatcherHook_validatesInput() {
        Instant now = clock.instant();
        assertThatThrownBy(() -> orchestrator.registerDispatcherHook(
                "", 7L, "my-agent", "ann-A", now, "idle"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> orchestrator.registerDispatcherHook(
                null, 7L, "my-agent", "ann-A", now, "idle"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> orchestrator.registerDispatcherHook(
                "disp-A", null, "my-agent", "ann-A", now, "idle"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> orchestrator.registerDispatcherHook(
                "disp-A", 7L, "my-agent", "ann-A", null, "idle"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("dispatcherHook: terminal 'idle' → completes chain, queries opt count, broadcasts WS")
    void dispatcherHook_terminalIdle_completesChainAndBroadcasts() {
        Instant startedAt = clock.instant();
        orchestrator.registerDispatcherHook(
                "disp-A", 7L, "my-agent", "ann-A", startedAt, "idle");
        assertThat(orchestrator.isDispatcherPending("disp-A")).isTrue();

        when(sessionService.getSession(eq("disp-A"))).thenReturn(session("disp-A", "idle"));
        when(optimizationEventRepository.countByAgentIdAndCreatedAtAfter(eq(7L), eq(startedAt)))
                .thenReturn(3L);

        orchestrator.tick();

        // Hook drained from pendingDispatcher map.
        assertThat(orchestrator.isDispatcherPending("disp-A")).isFalse();
        assertThat(orchestrator.completedRunsSize()).isEqualTo(1);

        // WS broadcast payload contains expected fields.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(userWebSocketHandler).broadcastAll(payloadCaptor.capture());
        Map<String, Object> payload = payloadCaptor.getValue();
        assertThat(payload.get("type")).isEqualTo("flywheel_chain_completed");
        assertThat(payload.get("agentId")).isEqualTo(7L);
        assertThat(payload.get("agentName")).isEqualTo("my-agent");
        assertThat(payload.get("annotatorSessionId")).isEqualTo("ann-A");
        assertThat(payload.get("dispatcherSessionId")).isEqualTo("disp-A");
        assertThat(payload.get("annotatorStatus")).isEqualTo("idle");
        assertThat(payload.get("dispatcherStatus")).isEqualTo("idle");
        assertThat(payload.get("optEventCount")).isEqualTo(3);
        assertThat(payload.get("hasResults")).isEqualTo(true);
    }

    @Test
    @DisplayName("dispatcherHook: terminal 'error' → completes chain with dispatcherStatus=error")
    void dispatcherHook_terminalError_completesChainWithErrorStatus() {
        Instant startedAt = clock.instant();
        orchestrator.registerDispatcherHook(
                "disp-err", 8L, "another-agent", "ann-B", startedAt, "idle");
        when(sessionService.getSession(eq("disp-err"))).thenReturn(session("disp-err", "error"));
        when(optimizationEventRepository.countByAgentIdAndCreatedAtAfter(eq(8L), eq(startedAt)))
                .thenReturn(0L);

        orchestrator.tick();

        assertThat(orchestrator.isDispatcherPending("disp-err")).isFalse();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(userWebSocketHandler).broadcastAll(payloadCaptor.capture());
        Map<String, Object> payload = payloadCaptor.getValue();
        assertThat(payload.get("dispatcherStatus")).isEqualTo("error");
        assertThat(payload.get("optEventCount")).isEqualTo(0);
        assertThat(payload.get("hasResults")).isEqualTo(false);
    }

    @Test
    @DisplayName("dispatcherHook: TTL expired → dropped without firing WS broadcast")
    void dispatcherHook_ttlExpired_droppedWithoutBroadcast() {
        Instant startedAt = clock.instant();
        orchestrator.registerDispatcherHook(
                "disp-stuck", 9L, "stuck-agent", "ann-C", startedAt, "idle");

        // Advance clock past TTL.
        clock.advance(FlywheelChainOrchestrator.HOOK_TTL.plusSeconds(1));

        orchestrator.tick();

        assertThat(orchestrator.isDispatcherPending("disp-stuck")).isFalse();
        assertThat(orchestrator.completedRunsSize()).isEqualTo(0);
        verify(userWebSocketHandler, never()).broadcastAll(any());
    }

    @Test
    @DisplayName("dispatcherHook: slow-annotator scenario — dispatcher gets fresh full TTL from fire-time (r1 W2 fix)")
    void dispatcherHook_slowAnnotator_dispatcherStillGetsFullTTL() {
        // Operator clicked Run-Loop at t=0. Annotator took 9.9min to finish
        // (just under HOOK_TTL). The startedAt captured at click-time is now
        // very stale; if TTL anchored to startedAt, dispatcher would have
        // <60s left before silent drop.
        //
        // r1 W2 fix anchors TTL to dispatcherFiredAt (set by registerDispatcherHook
        // when the controller's annotator-end hook callback fires the
        // dispatcher chatAsync), so the dispatcher gets a full HOOK_TTL
        // window regardless of annotator latency.
        Instant clickTime = clock.instant();
        // Simulate 9.9 min of annotator runtime — clock has advanced before
        // dispatcher registration.
        clock.advance(FlywheelChainOrchestrator.HOOK_TTL.minusSeconds(30));
        Instant fireTime = clock.instant();
        orchestrator.registerDispatcherHook(
                "disp-slow", 11L, "slow-agent", "ann-slow", clickTime, "idle");

        // Now advance another 5 minutes after dispatcher fire. dispatcherFiredAt
        // + 5min < HOOK_TTL → hook should still be alive. But startedAt
        // (= clickTime) + 5min is now 14.9min — way past HOOK_TTL. If TTL
        // was anchored to startedAt this hook would be incorrectly dropped.
        clock.advance(Duration.ofMinutes(5));
        when(sessionService.getSession(eq("disp-slow")))
                .thenReturn(session("disp-slow", "idle"));
        when(optimizationEventRepository.countByAgentIdAndCreatedAtAfter(eq(11L), eq(clickTime)))
                .thenReturn(2L);

        orchestrator.tick();

        // Hook fired (not TTL-dropped) — chain completed.
        assertThat(orchestrator.isDispatcherPending("disp-slow")).isFalse();
        assertThat(orchestrator.completedRunsSize()).isEqualTo(1);
        verify(userWebSocketHandler).broadcastAll(any());

        // Sanity: fireTime was used as anchor, not clickTime.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(userWebSocketHandler).broadcastAll(payloadCaptor.capture());
        // payload.startedAt is the original opt-loop click — orchestrator
        // preserves that for FE display, only TTL accounting uses
        // dispatcherFiredAt.
        assertThat(payloadCaptor.getValue().get("startedAt")).isEqualTo(clickTime.toString());
        assertThat(fireTime).isAfter(clickTime);  // explicit relationship
    }

    @Test
    @DisplayName("dispatcherHook: opt count query throws → optEventCount=-1 in payload")
    void dispatcherHook_optCountQueryFails_emitsMinusOne() {
        Instant startedAt = clock.instant();
        orchestrator.registerDispatcherHook(
                "disp-D", 10L, "agent-D", "ann-D", startedAt, "idle");
        when(sessionService.getSession(eq("disp-D"))).thenReturn(session("disp-D", "idle"));
        when(optimizationEventRepository.countByAgentIdAndCreatedAtAfter(anyLong(), any()))
                .thenThrow(new RuntimeException("DB hiccup"));

        orchestrator.tick();

        assertThat(orchestrator.isDispatcherPending("disp-D")).isFalse();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(userWebSocketHandler).broadcastAll(payloadCaptor.capture());
        assertThat(payloadCaptor.getValue().get("optEventCount")).isEqualTo(-1);
        assertThat(payloadCaptor.getValue().get("hasResults")).isEqualTo(false);
    }

    @Test
    @DisplayName("getChainRuns: filters by agentId, returns correct subset newest-first")
    void getChainRuns_filtersByAgentId_returnsCorrectSubset() {
        // Drain 2 completions for agentId=1, 1 for agentId=2.
        completeOnce("disp-1a", 1L, "agent-1", "ann-1a", clock.instant());
        clock.advance(Duration.ofSeconds(10));
        completeOnce("disp-2a", 2L, "agent-2", "ann-2a", clock.instant());
        clock.advance(Duration.ofSeconds(10));
        completeOnce("disp-1b", 1L, "agent-1", "ann-1b", clock.instant());

        // Filter agentId=1 → 2 results, newest first.
        List<FlywheelChainOrchestrator.ChainRunResult> runs =
                orchestrator.getChainRuns(1L, 20);
        assertThat(runs).hasSize(2);
        assertThat(runs.get(0).dispatcherSessionId()).isEqualTo("disp-1b");
        assertThat(runs.get(1).dispatcherSessionId()).isEqualTo("disp-1a");

        // Filter agentId=2 → 1 result.
        List<FlywheelChainOrchestrator.ChainRunResult> runs2 =
                orchestrator.getChainRuns(2L, 20);
        assertThat(runs2).hasSize(1);
        assertThat(runs2.get(0).dispatcherSessionId()).isEqualTo("disp-2a");

        // No filter → all 3, newest first.
        List<FlywheelChainOrchestrator.ChainRunResult> all =
                orchestrator.getChainRuns(null, 20);
        assertThat(all).hasSize(3);
        assertThat(all.get(0).dispatcherSessionId()).isEqualTo("disp-1b");
    }

    @Test
    @DisplayName("getChainRuns: limit truncates result + in-flight runs included as partial entries")
    void getChainRuns_inflightAndCompleted_mergedAndTruncated() {
        // 1 completed run.
        completeOnce("disp-done", 1L, "agent-1", "ann-1", clock.instant());

        // 1 in-flight run (registered but not yet terminal).
        clock.advance(Duration.ofSeconds(5));
        orchestrator.registerDispatcherHook(
                "disp-running", 1L, "agent-1", "ann-2", clock.instant(), "idle");

        // limit=1 → only newest (in-flight) returned.
        List<FlywheelChainOrchestrator.ChainRunResult> runs =
                orchestrator.getChainRuns(null, 1);
        assertThat(runs).hasSize(1);
        assertThat(runs.get(0).dispatcherSessionId()).isEqualTo("disp-running");
        // In-flight projection — dispatcherStatus=null, completedAt=null, optEventCount=-1.
        assertThat(runs.get(0).dispatcherStatus()).isNull();
        assertThat(runs.get(0).completedAt()).isNull();
        assertThat(runs.get(0).optEventCount()).isEqualTo(-1);

        // limit=20 → both returned, newest first.
        List<FlywheelChainOrchestrator.ChainRunResult> both =
                orchestrator.getChainRuns(null, 20);
        assertThat(both).hasSize(2);
        assertThat(both.get(0).dispatcherSessionId()).isEqualTo("disp-running");
        assertThat(both.get(1).dispatcherSessionId()).isEqualTo("disp-done");
    }

    /** Helper: register + drive tick to drain a single completion into completedRuns. */
    private void completeOnce(String dispatcherSessionId, Long agentId,
                              String agentName, String annotatorSessionId, Instant startedAt) {
        orchestrator.registerDispatcherHook(
                dispatcherSessionId, agentId, agentName, annotatorSessionId, startedAt, "idle");
        when(sessionService.getSession(eq(dispatcherSessionId)))
                .thenReturn(session(dispatcherSessionId, "idle"));
        when(optimizationEventRepository.countByAgentIdAndCreatedAtAfter(eq(agentId), eq(startedAt)))
                .thenReturn(0L);
        orchestrator.tick();
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private static SessionEntity session(String id, String runtimeStatus) {
        SessionEntity s = new SessionEntity();
        s.setId(id);
        s.setUserId(0L);
        s.setAgentId(0L);
        s.setRuntimeStatus(runtimeStatus);
        return s;
    }

    /** Test-only mutable clock so we can step through TTL boundaries deterministically. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
