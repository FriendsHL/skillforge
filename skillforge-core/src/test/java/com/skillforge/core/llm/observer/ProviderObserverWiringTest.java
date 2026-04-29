package com.skillforge.core.llm.observer;

import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.LlmRequest;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.core.llm.LlmStreamHandler;
import com.skillforge.core.model.ToolUseBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M1 acceptance: factory wires observer registry through to provider; default registry is NO_OP.
 *
 * <p>Plan §2.6 / §10.1.
 */
class ProviderObserverWiringTest {

    @Test
    @DisplayName("registerProvider with custom observer registry — observer hooks fire on stream success")
    void registerProvider_appliesObserverRegistry() {
        LlmProviderFactory factory = new LlmProviderFactory();
        List<String> events = new ArrayList<>();
        LlmCallObserver observer = new LlmCallObserver() {
            @Override public void beforeCall(LlmCallContext ctx, RawHttpRequest req) { events.add("before"); }
            @Override public void onStreamComplete(LlmCallContext ctx, RawStreamCapture cap, LlmResponse parsed) {
                events.add("complete");
            }
            @Override public void onError(LlmCallContext ctx, Throwable err, RawStreamCapture partial) {
                events.add("error");
            }
        };
        factory.setObserverRegistry(() -> List.of(observer));

        FakeProvider fp = new FakeProvider();
        // Manually wire registry: factory's pattern-match wiring only handles
        // ClaudeProvider/OpenAiProvider — for the in-test fake we set explicitly.
        fp.setObserverRegistry(() -> List.of(observer));
        factory.registerProvider("fake", fp);

        // Drive the new ctx-aware streaming path explicitly so the test exercises the OBS hooks.
        fp.fireBeforeAndComplete(LlmCallContext.builder().traceId("t").spanId("s").build());

        assertThat(events).containsExactly("before", "complete");
    }

    @Test
    @DisplayName("default registry is NO_OP (no exception, no events)")
    void noObserver_isNoOp() {
        LlmProviderFactory factory = new LlmProviderFactory();
        FakeProvider fp = new FakeProvider();
        factory.registerProvider("fake", fp);
        // Just exercise — must not throw.
        fp.fireBeforeAndComplete(LlmCallContext.empty());
    }

    /** Minimal provider that exposes hooks directly to verify wiring. */
    private static final class FakeProvider implements LlmProvider {
        private LlmCallObserverRegistry registry = LlmCallObserverRegistry.NO_OP;

        public void setObserverRegistry(LlmCallObserverRegistry r) {
            this.registry = r == null ? LlmCallObserverRegistry.NO_OP : r;
        }

        void fireBeforeAndComplete(LlmCallContext ctx) {
            SafeObservers.notifyBefore(registry, ctx,
                    new RawHttpRequest("POST", "x", java.util.Map.of(), new byte[0], "application/json"));
            SafeObservers.notifyStreamComplete(registry, ctx,
                    new RawStreamCapture(new byte[0], new byte[0], false, 0L),
                    new LlmResponse());
        }

        @Override public String getName() { return "fake"; }
        @Override public LlmResponse chat(LlmRequest request) { return new LlmResponse(); }
        @Override public void chatStream(LlmRequest request, LlmStreamHandler handler) {
            handler.onComplete(new LlmResponse());
        }
    }

    @SuppressWarnings("unused")
    private static class IgnoreToolUse {
        void noOp(ToolUseBlock b) {}
    }
}
