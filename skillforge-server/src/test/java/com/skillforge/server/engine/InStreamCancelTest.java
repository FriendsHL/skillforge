package com.skillforge.server.engine;

import com.skillforge.core.engine.LoopContext;
import com.skillforge.core.llm.LlmStreamHandler;
import com.skillforge.core.model.ToolUseBlock;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the in-stream cancel mechanism:
 * - LoopContext.streamCanceller wiring
 * - requestCancel() invoking the stream canceller
 * - Handler delegation to LoopContext
 */
class InStreamCancelTest {

    @Test
    void testRequestCancelInvokesStreamCanceller() {
        LoopContext ctx = new LoopContext();
        AtomicBoolean cancellerInvoked = new AtomicBoolean(false);

        ctx.setStreamCanceller(() -> cancellerInvoked.set(true));

        assertThat(cancellerInvoked.get()).isFalse();
        ctx.requestCancel();

        assertThat(ctx.isCancelled()).isTrue();
        assertThat(cancellerInvoked.get()).isTrue();
    }

    @Test
    void testRequestCancelWithoutStreamCancellerDoesNotThrow() {
        LoopContext ctx = new LoopContext();
        // No stream canceller set — should not throw
        ctx.requestCancel();
        assertThat(ctx.isCancelled()).isTrue();
    }

    @Test
    void testRequestCancelToleratesCancellerException() {
        LoopContext ctx = new LoopContext();
        ctx.setStreamCanceller(() -> { throw new RuntimeException("boom"); });

        // Should not propagate the exception
        ctx.requestCancel();
        assertThat(ctx.isCancelled()).isTrue();
    }

    @Test
    void testStreamCancellerClearedAfterStream() {
        LoopContext ctx = new LoopContext();
        AtomicBoolean cancellerInvoked = new AtomicBoolean(false);

        ctx.setStreamCanceller(() -> cancellerInvoked.set(true));
        ctx.clearStreamCanceller();

        // Now cancel — the cleared canceller should NOT be invoked
        ctx.requestCancel();
        assertThat(ctx.isCancelled()).isTrue();
        assertThat(cancellerInvoked.get()).isFalse();
    }

    @Test
    void testIsCancelledDelegation() {
        LoopContext ctx = new LoopContext();

        // Build a handler that delegates to LoopContext, same as AgentLoopEngine does
        LlmStreamHandler handler = new LlmStreamHandler() {
            @Override public void onText(String text) {}
            @Override public void onToolUse(ToolUseBlock block) {}
            @Override public void onComplete(com.skillforge.core.llm.LlmResponse fullResponse) {}
            @Override public void onError(Throwable error) {}
            @Override public boolean isCancelled() { return ctx.isCancelled(); }
            @Override public void onStreamStart(Runnable cancelAction) {
                if (cancelAction != null) {
                    ctx.setStreamCanceller(cancelAction);
                } else {
                    ctx.clearStreamCanceller();
                }
            }
        };

        assertThat(handler.isCancelled()).isFalse();
        ctx.requestCancel();
        assertThat(handler.isCancelled()).isTrue();
    }

    @Test
    void testCancelAfterStreamCompleteIsNoop() {
        LoopContext ctx = new LoopContext();
        AtomicBoolean callCancelled = new AtomicBoolean(false);

        ctx.setStreamCanceller(() -> callCancelled.set(true));
        // Simulate stream completing — provider clears the canceller
        ctx.clearStreamCanceller();

        // Now cancel arrives after stream complete
        ctx.requestCancel();
        assertThat(ctx.isCancelled()).isTrue();
        // The original call::cancel should NOT have been invoked
        assertThat(callCancelled.get()).isFalse();
    }

    @Test
    void testOnStreamStartNullClearsCanceller() {
        LoopContext ctx = new LoopContext();
        AtomicBoolean cancellerInvoked = new AtomicBoolean(false);

        // Simulate the provider flow: onStreamStart(action) then onStreamStart(null)
        LlmStreamHandler handler = new LlmStreamHandler() {
            @Override public void onText(String text) {}
            @Override public void onToolUse(ToolUseBlock block) {}
            @Override public void onComplete(com.skillforge.core.llm.LlmResponse fullResponse) {}
            @Override public void onError(Throwable error) {}
            @Override public void onStreamStart(Runnable cancelAction) {
                if (cancelAction != null) {
                    ctx.setStreamCanceller(cancelAction);
                } else {
                    ctx.clearStreamCanceller();
                }
            }
        };

        handler.onStreamStart(() -> cancellerInvoked.set(true));
        handler.onStreamStart(null); // simulate finally block

        ctx.requestCancel();
        assertThat(cancellerInvoked.get()).isFalse();
    }

    @Test
    void testDefaultHandlerMethodsAreNoOps() {
        // Verify the default implementations on the interface don't break
        LlmStreamHandler defaultHandler = new LlmStreamHandler() {
            @Override public void onText(String text) {}
            @Override public void onToolUse(ToolUseBlock block) {}
            @Override public void onComplete(com.skillforge.core.llm.LlmResponse fullResponse) {}
            @Override public void onError(Throwable error) {}
        };

        // Default isCancelled returns false
        assertThat(defaultHandler.isCancelled()).isFalse();
        // Default onStreamStart is no-op, should not throw
        defaultHandler.onStreamStart(() -> {});
        defaultHandler.onStreamStart(null);
    }

    @Test
    void testSetStreamCancellerDoubleCheckFiresIfAlreadyCancelled() {
        // Fix for TOCTOU race: if requestCancel fires before setStreamCanceller,
        // the canceller must still be invoked when it's registered
        LoopContext ctx = new LoopContext();
        ctx.requestCancel(); // cancel BEFORE registering canceller

        AtomicBoolean cancellerInvoked = new AtomicBoolean(false);
        ctx.setStreamCanceller(() -> cancellerInvoked.set(true));

        // setStreamCanceller should have double-checked and fired the canceller
        assertThat(cancellerInvoked.get()).isTrue();
    }

    @Test
    void testSetStreamCancellerDoubleCheckDoesNotFireIfNotCancelled() {
        LoopContext ctx = new LoopContext();
        AtomicBoolean cancellerInvoked = new AtomicBoolean(false);

        ctx.setStreamCanceller(() -> cancellerInvoked.set(true));

        // Not cancelled — canceller should NOT have been invoked on registration
        assertThat(cancellerInvoked.get()).isFalse();
    }
}
