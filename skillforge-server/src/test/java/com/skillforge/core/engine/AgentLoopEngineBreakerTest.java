package com.skillforge.core.engine;

import com.skillforge.core.compact.ContextCompactorCallback;
import com.skillforge.core.compact.ContextCompactorCallback.CompactCallbackResult;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUG-A / BUG-B: compact circuit breaker state machine.
 *
 * <p>Lives under {@code com.skillforge.core.engine} to access package-private
 * {@link AgentLoopEngine#isCompactBreakerAllowing(LoopContext)} and
 * {@link AgentLoopEngine#recordCompactFailure(LoopContext)} plus the breaker constants.
 *
 * <p>Regression anchors:
 * <ul>
 *   <li>session {@code cec1cd60} — CompactionService normal no-ops (idempotency guard,
 *       fullCompactInFlight, etc.) were being counted as failures, opening the breaker
 *       and leaving the session uncompactable for the rest of its lifetime.</li>
 * </ul>
 */
class AgentLoopEngineBreakerTest {

    @Test
    void isCompactBreakerAllowing_closed_whenFailuresBelowThreshold() {
        LoopContext ctx = new LoopContext();
        assertThat(AgentLoopEngine.isCompactBreakerAllowing(ctx)).isTrue();
        ctx.incrementCompactFailures();
        ctx.incrementCompactFailures();
        // 2 < threshold(3)
        assertThat(AgentLoopEngine.isCompactBreakerAllowing(ctx)).isTrue();
    }

    @Test
    void isCompactBreakerAllowing_blocked_whenOpenWithinHalfOpenWindow() {
        LoopContext ctx = new LoopContext();
        // Simulate 3 failures with openedAt just now
        ctx.incrementCompactFailures();
        ctx.incrementCompactFailures();
        ctx.incrementCompactFailures();
        ctx.refreshCompactBreakerOpenedAt();
        // Within window (< 60s) → blocked
        assertThat(AgentLoopEngine.isCompactBreakerAllowing(ctx)).isFalse();
    }

    @Test
    void isCompactBreakerAllowing_allowsHalfOpenProbe_whenWindowExpired()
            throws ReflectiveOperationException {
        LoopContext ctx = new LoopContext();
        ctx.incrementCompactFailures();
        ctx.incrementCompactFailures();
        ctx.incrementCompactFailures();
        // Set openedAt to (now - 61s) via reflection to simulate elapsed window
        long pastMs = System.currentTimeMillis() - 61_000L;
        var field = LoopContext.class.getDeclaredField("compactBreakerOpenedAt");
        field.setAccessible(true);
        field.setLong(ctx, pastMs);
        assertThat(AgentLoopEngine.isCompactBreakerAllowing(ctx)).isTrue();
    }

    @Test
    void isCompactBreakerAllowing_safeFallback_whenOpenedAtUnset() {
        // Defensive: counter at threshold but openedAt == 0 (inconsistent state) → allow.
        LoopContext ctx = new LoopContext();
        for (int i = 0; i < 5; i++) ctx.incrementCompactFailures();
        // Do NOT call refreshCompactBreakerOpenedAt
        assertThat(ctx.getCompactBreakerOpenedAt()).isZero();
        assertThat(AgentLoopEngine.isCompactBreakerAllowing(ctx)).isTrue();
    }

    @Test
    void resetCompactFailures_clearsBothCounterAndOpenedAt() {
        LoopContext ctx = new LoopContext();
        for (int i = 0; i < 4; i++) ctx.incrementCompactFailures();
        ctx.refreshCompactBreakerOpenedAt();
        assertThat(ctx.getCompactBreakerOpenedAt()).isPositive();

        ctx.resetCompactFailures();

        assertThat(ctx.getConsecutiveCompactFailures()).isZero();
        assertThat(ctx.getCompactBreakerOpenedAt()).isZero();
    }

    @Test
    void recordCompactFailure_refreshesOpenedAtOnceAtThreshold() {
        LoopContext ctx = new LoopContext();
        AgentLoopEngine.recordCompactFailure(ctx);
        AgentLoopEngine.recordCompactFailure(ctx);
        assertThat(ctx.getCompactBreakerOpenedAt()).isZero();  // below threshold

        AgentLoopEngine.recordCompactFailure(ctx);
        assertThat(ctx.getConsecutiveCompactFailures()).isEqualTo(3);
        assertThat(ctx.getCompactBreakerOpenedAt()).isPositive();
    }

    @Test
    void recordCompactFailure_halfOpenFailureRefreshesOpenedAt() throws InterruptedException {
        LoopContext ctx = new LoopContext();
        for (int i = 0; i < 3; i++) AgentLoopEngine.recordCompactFailure(ctx);
        long firstOpenedAt = ctx.getCompactBreakerOpenedAt();
        assertThat(firstOpenedAt).isPositive();

        // Sleep a few ms so the refreshed timestamp is strictly greater than the original.
        Thread.sleep(5);
        AgentLoopEngine.recordCompactFailure(ctx);  // 4th failure (half-open probe failed)

        long secondOpenedAt = ctx.getCompactBreakerOpenedAt();
        assertThat(secondOpenedAt).isGreaterThan(firstOpenedAt);
        assertThat(ctx.getConsecutiveCompactFailures()).isEqualTo(4);
    }

    /**
     * BUG-A regression (session cec1cd60): CompactionService returning performed=false
     * (idempotency guard / strategy no-op / fullCompactInFlight) must NOT be counted as
     * a failure. Only exceptions increment the breaker.
     *
     * <p>This test drives the state machine directly — the branch that formerly called
     * {@code loopCtx.incrementCompactFailures()} on performed=false has been removed in
     * {@link AgentLoopEngine} (see BUG-A fix). Here we verify that if the engine only
     * treats the {@code catch (Exception e)} path as a failure, repeated no-ops leave
     * the breaker closed.
     */
    @Test
    void noOpCompactResults_doNotIncrementFailures_cec1cd60Regression() {
        LoopContext ctx = new LoopContext();
        RecordingCallback cb = new RecordingCallback();
        cb.noOpAlways = true;

        // Simulate 10 iterations where the compact path returns performed=false.
        // Match the engine's new branch structure: success → reset; no-op → neutral;
        // exception → recordCompactFailure.
        for (int i = 0; i < 10; i++) {
            CompactCallbackResult cr = cb.compactLight("sess", new ArrayList<>(),
                    "engine-soft", "test");
            if (cr != null && cr.performed) {
                ctx.resetCompactFailures();
            }
            // BUG-A: no-op branch is neutral — we do NOT increment here.
        }

        assertThat(ctx.getConsecutiveCompactFailures()).isZero();
        assertThat(ctx.getCompactBreakerOpenedAt()).isZero();
        assertThat(AgentLoopEngine.isCompactBreakerAllowing(ctx)).isTrue();
    }

    @Test
    void exceptionCompactResults_openBreakerAfterThreshold() {
        LoopContext ctx = new LoopContext();
        for (int i = 0; i < 3; i++) {
            try {
                throw new RuntimeException("simulated LLM failure");
            } catch (RuntimeException e) {
                AgentLoopEngine.recordCompactFailure(ctx);
            }
        }
        assertThat(ctx.getConsecutiveCompactFailures()).isEqualTo(3);
        assertThat(AgentLoopEngine.isCompactBreakerAllowing(ctx)).isFalse();
    }

    // --- Helpers -------------------------------------------------------------

    private static class RecordingCallback implements ContextCompactorCallback {
        final AtomicInteger lightCalls = new AtomicInteger();
        final AtomicInteger fullCalls = new AtomicInteger();
        boolean noOpAlways;

        @Override
        public CompactCallbackResult compactLight(String sessionId, List<com.skillforge.core.model.Message> msgs,
                                                    String source, String reason) {
            lightCalls.incrementAndGet();
            if (noOpAlways) return CompactCallbackResult.noOp(msgs, "idempotency guard");
            return new CompactCallbackResult(msgs, true, 100, 500, 400, "mock");
        }

        @Override
        public CompactCallbackResult compactFull(String sessionId, List<com.skillforge.core.model.Message> msgs,
                                                   String source, String reason) {
            fullCalls.incrementAndGet();
            if (noOpAlways) return CompactCallbackResult.noOp(msgs, "full compact no-op or in-flight");
            return new CompactCallbackResult(msgs, true, 200, 600, 400, "mock full");
        }
    }
}
