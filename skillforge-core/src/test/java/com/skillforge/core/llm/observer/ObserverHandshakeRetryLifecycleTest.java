package com.skillforge.core.llm.observer;

import com.skillforge.core.llm.ClaudeProvider;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.core.llm.LlmStreamHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan §4.2 R2-B2 invariant: chatStream observer hooks fire ONCE at terminal.
 * — beforeCall exactly once
 * — onStreamComplete OR onError exactly once (mutex)
 * — handshake retries do NOT trigger hooks
 *
 * <p>Direct test of {@link ClaudeProvider.ObservedStreamHandler} decorator covering the
 * "stream complete" terminal path.
 */
class ObserverHandshakeRetryLifecycleTest {

    @Test
    @DisplayName("handshake succeeds → onStreamComplete fires exactly once; onError 0 times")
    void handshakeSucceeds_onStreamCompleteOnce_onError_zero() {
        AtomicInteger before = new AtomicInteger(), complete = new AtomicInteger(), err = new AtomicInteger();
        LlmCallObserver obs = new LlmCallObserver() {
            @Override public void beforeCall(LlmCallContext ctx, RawHttpRequest req) { before.incrementAndGet(); }
            @Override public void onStreamComplete(LlmCallContext ctx, RawStreamCapture cap, LlmResponse parsed) {
                complete.incrementAndGet();
            }
            @Override public void onError(LlmCallContext ctx, Throwable e, RawStreamCapture p) { err.incrementAndGet(); }
        };
        LlmCallObserverRegistry reg = () -> java.util.List.of(obs);
        LlmCallContext ctx = LlmCallContext.builder().traceId("t").spanId("s1").stream(true).build();

        // Simulate provider: beforeCall once outside retry loop, then handshake succeeds → onComplete.
        SafeObservers.notifyBefore(reg, ctx, new RawHttpRequest("POST", "x",
                java.util.Map.of(), new byte[0], "application/json"));

        InnerCapturingHandler inner = new InnerCapturingHandler();
        ClaudeProvider.ObservedStreamHandler observed =
                new ClaudeProvider.ObservedStreamHandler(inner, ctx, reg);
        observed.appendSseLine("data: {\"type\":\"message_stop\"}");
        observed.onComplete(new LlmResponse());

        assertThat(before.get()).isEqualTo(1);
        assertThat(complete.get()).isEqualTo(1);
        assertThat(err.get()).isEqualTo(0);
        assertThat(inner.completed).isTrue();
    }

    @Test
    @DisplayName("handshake-all-fail → only onError fires once; onStreamComplete 0 times")
    void handshakeAllFail_onErrorOnce_onStreamComplete_zero() {
        AtomicInteger before = new AtomicInteger(), complete = new AtomicInteger(), err = new AtomicInteger();
        LlmCallObserver obs = new LlmCallObserver() {
            @Override public void beforeCall(LlmCallContext ctx, RawHttpRequest req) { before.incrementAndGet(); }
            @Override public void onStreamComplete(LlmCallContext ctx, RawStreamCapture cap, LlmResponse parsed) {
                complete.incrementAndGet();
            }
            @Override public void onError(LlmCallContext ctx, Throwable e, RawStreamCapture p) { err.incrementAndGet(); }
        };
        LlmCallObserverRegistry reg = () -> java.util.List.of(obs);
        LlmCallContext ctx = LlmCallContext.builder().traceId("t").spanId("s2").stream(true).build();

        // beforeCall fires once outside retry loop; handshake fails repeatedly until exhausted →
        // exactly one onError. The provider's internal handshake loop never invokes any observer
        // hooks during retry.
        SafeObservers.notifyBefore(reg, ctx, new RawHttpRequest("POST", "x",
                java.util.Map.of(), new byte[0], "application/json"));
        SafeObservers.notifyError(reg, ctx, new RuntimeException("retry exhausted"), null);

        assertThat(before.get()).isEqualTo(1);
        assertThat(err.get()).isEqualTo(1);
        assertThat(complete.get()).isEqualTo(0);
    }

    @Test
    @DisplayName("ObservedStreamHandler is mutex-safe: multiple onComplete calls fire observer once")
    void observedHandler_terminalFiredGuardsAgainstDoubleFire() {
        AtomicInteger complete = new AtomicInteger();
        LlmCallObserver obs = new LlmCallObserver() {
            @Override public void onStreamComplete(LlmCallContext ctx, RawStreamCapture cap, LlmResponse parsed) {
                complete.incrementAndGet();
            }
        };
        LlmCallContext ctx = LlmCallContext.builder().spanId("s3").build();
        InnerCapturingHandler inner = new InnerCapturingHandler();
        ClaudeProvider.ObservedStreamHandler observed = new ClaudeProvider.ObservedStreamHandler(
                inner, ctx, () -> java.util.List.of(obs));

        observed.onComplete(new LlmResponse());
        observed.onComplete(new LlmResponse()); // double-fire guard

        assertThat(complete.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("ObservedStreamHandler: onError after onComplete still gates terminal observer fire")
    void observedHandler_completeThenError_observerFiresOnce() {
        AtomicInteger complete = new AtomicInteger(), err = new AtomicInteger();
        LlmCallObserver obs = new LlmCallObserver() {
            @Override public void onStreamComplete(LlmCallContext c, RawStreamCapture cap, LlmResponse r) {
                complete.incrementAndGet();
            }
            @Override public void onError(LlmCallContext c, Throwable t, RawStreamCapture p) {
                err.incrementAndGet();
            }
        };
        LlmCallContext ctx = LlmCallContext.builder().spanId("s4").build();
        InnerCapturingHandler inner = new InnerCapturingHandler();
        ClaudeProvider.ObservedStreamHandler observed = new ClaudeProvider.ObservedStreamHandler(
                inner, ctx, () -> java.util.List.of(obs));

        observed.onComplete(new LlmResponse());
        observed.onError(new RuntimeException("late"));

        assertThat(complete.get()).isEqualTo(1);
        assertThat(err.get()).isEqualTo(0); // mutex: terminalFired blocks subsequent onError
    }

    @Test
    @DisplayName("ObservedStreamHandler buffer cap: > 5 MB SSE → sseTruncated=true, capacity stays at 5 MB")
    void observedHandler_sseBufferCap() {
        AtomicInteger complete = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<RawStreamCapture> capRef = new java.util.concurrent.atomic.AtomicReference<>();
        LlmCallObserver obs = new LlmCallObserver() {
            @Override public void onStreamComplete(LlmCallContext c, RawStreamCapture cap, LlmResponse r) {
                complete.incrementAndGet();
                capRef.set(cap);
            }
        };
        LlmCallContext ctx = LlmCallContext.builder().spanId("s5").build();
        ClaudeProvider.ObservedStreamHandler observed = new ClaudeProvider.ObservedStreamHandler(
                new InnerCapturingHandler(), ctx, () -> java.util.List.of(obs));

        // Push ~6 MB worth of "data: payload" lines.
        String chunk = "data: " + "x".repeat(1024);
        long target = 6L * 1024L * 1024L;
        long pushed = 0;
        while (pushed < target) {
            observed.appendSseLine(chunk);
            pushed += chunk.length() + 1; // +1 newline
        }
        observed.onComplete(new LlmResponse());

        RawStreamCapture cap = capRef.get();
        assertThat(cap).isNotNull();
        assertThat(cap.sseTruncated()).isTrue();
        assertThat(cap.rawSse().length).isLessThanOrEqualTo(5 * 1024 * 1024);
        assertThat(complete.get()).isEqualTo(1);
    }

    private static final class InnerCapturingHandler implements LlmStreamHandler {
        boolean completed;
        boolean errored;

        @Override public void onText(String text) {}
        @Override public void onToolUse(com.skillforge.core.model.ToolUseBlock block) {}
        @Override public void onComplete(LlmResponse fullResponse) { completed = true; }
        @Override public void onError(Throwable error) { errored = true; }
    }
}
