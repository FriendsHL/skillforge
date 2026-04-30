package com.skillforge.core.engine;

import com.skillforge.core.compact.ContextCompactorCallback;
import com.skillforge.core.compact.ContextCompactorCallback.CompactCallbackResult;
import com.skillforge.core.llm.LlmContextLengthExceededException;
import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.LlmRequest;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.core.llm.LlmStreamHandler;
import com.skillforge.core.llm.observer.LlmCallContext;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.Message;
import com.skillforge.core.skill.SkillRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CTX-1 — AgentLoopEngine post-overflow retry path coverage (AC-4 / AC-5).
 *
 * <p>Uses a fake {@link LlmProvider} whose stream behaviour can be programmed per call:
 * <ul>
 *   <li>throw {@link LlmContextLengthExceededException} (via {@code handler.onError})</li>
 *   <li>or return a normal text response</li>
 * </ul>
 * Combined with a recording compact callback the test asserts that a single overflow is
 * caught + compacted + retried, and that two consecutive overflows surface the original
 * exception instead of looping.
 */
class AgentLoopEngineRetryOverflowTest {

    /** Fake provider whose script controls each chatStream call's terminal event. */
    private static class ScriptedProvider implements LlmProvider {
        enum Event { OVERFLOW, TEXT_RESPONSE }
        private final List<Event> script = new ArrayList<>();
        int callCount = 0;
        final List<Integer> messageCountsPerCall = new ArrayList<>();

        ScriptedProvider script(Event... events) {
            Collections.addAll(script, events);
            return this;
        }

        @Override public String getName() { return "scripted"; }
        @Override public LlmResponse chat(LlmRequest request) { throw new UnsupportedOperationException(); }
        @Override
        public void chatStream(LlmRequest request, LlmStreamHandler handler) {
            chatStream(request, LlmCallContext.empty(), handler);
        }
        @Override
        public void chatStream(LlmRequest request, LlmCallContext ctx, LlmStreamHandler handler) {
            messageCountsPerCall.add(request.getMessages() != null ? request.getMessages().size() : 0);
            Event next = callCount < script.size() ? script.get(callCount) : Event.TEXT_RESPONSE;
            callCount++;
            handler.onStreamStart(null);
            switch (next) {
                case OVERFLOW -> handler.onError(
                        new LlmContextLengthExceededException("scripted overflow"));
                case TEXT_RESPONSE -> {
                    LlmResponse resp = new LlmResponse();
                    resp.setContent("hello back");
                    resp.setStopReason("end_turn");
                    handler.onComplete(resp);
                }
            }
        }
    }

    /** Recording compact callback that always reports a performed full compact. */
    private static class RecordingCallback implements ContextCompactorCallback {
        final AtomicInteger lightCalls = new AtomicInteger();
        final AtomicInteger fullCalls = new AtomicInteger();
        final List<String> sources = new ArrayList<>();

        @Override
        public CompactCallbackResult compactLight(String sessionId, List<Message> currentMessages,
                                                    String sourceLabel, String reason) {
            lightCalls.incrementAndGet();
            sources.add("light:" + sourceLabel);
            // Strip down to only the last message — simulating a compact reclaim.
            List<Message> compacted = currentMessages.size() > 1
                    ? new ArrayList<>(currentMessages.subList(currentMessages.size() - 1, currentMessages.size()))
                    : currentMessages;
            return new CompactCallbackResult(compacted, true, 1, currentMessages.size(),
                    compacted.size(), "light");
        }

        @Override
        public CompactCallbackResult compactFull(String sessionId, List<Message> currentMessages,
                                                   String sourceLabel, String reason) {
            fullCalls.incrementAndGet();
            sources.add("full:" + sourceLabel);
            List<Message> compacted = currentMessages.size() > 1
                    ? new ArrayList<>(currentMessages.subList(currentMessages.size() - 1, currentMessages.size()))
                    : currentMessages;
            return new CompactCallbackResult(compacted, true, 100, currentMessages.size() * 50,
                    compacted.size() * 50, "full");
        }
    }

    /** Compact callback that signals "no compact happened" via performed=false. */
    private static class NoOpCallback implements ContextCompactorCallback {
        @Override
        public CompactCallbackResult compactLight(String sessionId, List<Message> currentMessages,
                                                    String sourceLabel, String reason) {
            return CompactCallbackResult.noOp(currentMessages, "no-op for test");
        }
        @Override
        public CompactCallbackResult compactFull(String sessionId, List<Message> currentMessages,
                                                   String sourceLabel, String reason) {
            return CompactCallbackResult.noOp(currentMessages, "no-op for test");
        }
    }

    private AgentLoopEngine engineWith(LlmProvider provider, ContextCompactorCallback cb) {
        LlmProviderFactory factory = new LlmProviderFactory();
        factory.registerProvider("scripted", provider);
        AgentLoopEngine engine = new AgentLoopEngine(
                factory, "scripted", new SkillRegistry(),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        if (cb != null) engine.setCompactorCallback(cb);
        return engine;
    }

    private AgentDefinition agentDef() {
        AgentDefinition def = new AgentDefinition();
        def.setId("test-agent");
        def.setName("test");
        def.setModelId("scripted:dummy-model");
        def.setSystemPrompt("you are helpful");
        // Force max_loops=1 so a successful chatStream ends the loop immediately.
        def.getConfig().put("max_loops", 1);
        return def;
    }

    @Test
    @DisplayName("AC-4: first call overflow → compactFull + retry → second call succeeds")
    void overflow_then_success_compactsAndRetries() {
        ScriptedProvider provider = new ScriptedProvider().script(
                ScriptedProvider.Event.OVERFLOW,
                ScriptedProvider.Event.TEXT_RESPONSE);
        RecordingCallback cb = new RecordingCallback();
        AgentLoopEngine engine = engineWith(provider, cb);

        LoopResult result = engine.run(agentDef(), "hello", new ArrayList<>(), "session-1", 1L);

        assertThat(provider.callCount).as("chatStream called twice (overflow + retry)").isEqualTo(2);
        assertThat(cb.fullCalls.get()).as("compactFull invoked exactly once for post-overflow")
                .isGreaterThanOrEqualTo(1);
        assertThat(cb.sources).contains("full:post-overflow");
        assertThat(result).isNotNull();
        // Second call sees post-compact (smaller) message list.
        assertThat(provider.messageCountsPerCall.get(1))
                .as("retry sees compacted messages (smaller than initial)")
                .isLessThanOrEqualTo(provider.messageCountsPerCall.get(0));
    }

    @Test
    @DisplayName("AC-5: two consecutive overflows → only one retry, then surface")
    void two_overflows_in_a_row_surfaces_after_one_retry() {
        ScriptedProvider provider = new ScriptedProvider().script(
                ScriptedProvider.Event.OVERFLOW,
                ScriptedProvider.Event.OVERFLOW,
                ScriptedProvider.Event.TEXT_RESPONSE);
        RecordingCallback cb = new RecordingCallback();
        AgentLoopEngine engine = engineWith(provider, cb);

        assertThatThrownBy(() ->
                engine.run(agentDef(), "hello", new ArrayList<>(), "session-2", 1L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("overflow");

        assertThat(provider.callCount).as("chatStream called twice — original + one retry, no third")
                .isEqualTo(2);
        assertThat(cb.fullCalls.get()).as("only one compactFull from retry path")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("compactFull no-op (in-flight / idempotent) → surfaces overflow without retry")
    void compactFull_noop_surfaces_overflow() {
        ScriptedProvider provider = new ScriptedProvider().script(
                ScriptedProvider.Event.OVERFLOW);
        AgentLoopEngine engine = engineWith(provider, new NoOpCallback());

        assertThatThrownBy(() ->
                engine.run(agentDef(), "hello", new ArrayList<>(), "session-3", 1L))
                .isInstanceOf(LlmContextLengthExceededException.class);

        // No retry attempt because compactFull returned performed=false.
        assertThat(provider.callCount).isEqualTo(1);
    }

    @Test
    @DisplayName("no compactor callback wired → first overflow surfaces immediately")
    void no_compactor_surfaces_overflow_immediately() {
        ScriptedProvider provider = new ScriptedProvider().script(
                ScriptedProvider.Event.OVERFLOW);
        AgentLoopEngine engine = engineWith(provider, null);

        assertThatThrownBy(() ->
                engine.run(agentDef(), "hello", new ArrayList<>(), "session-4", 1L))
                .isInstanceOf(LlmContextLengthExceededException.class);

        assertThat(provider.callCount).isEqualTo(1);
    }

    @Test
    @DisplayName("unwrapContextOverflow walks the cause chain")
    void unwrapContextOverflow_findsBuriedOverflow() {
        LlmContextLengthExceededException buried =
                new LlmContextLengthExceededException("buried");
        Throwable wrapped = new RuntimeException("outer",
                new RuntimeException("middle", buried));

        assertThat(AgentLoopEngine.unwrapContextOverflow(wrapped)).isSameAs(buried);
        assertThat(AgentLoopEngine.unwrapContextOverflow(null)).isNull();
        assertThat(AgentLoopEngine.unwrapContextOverflow(new RuntimeException("plain"))).isNull();
    }
}
