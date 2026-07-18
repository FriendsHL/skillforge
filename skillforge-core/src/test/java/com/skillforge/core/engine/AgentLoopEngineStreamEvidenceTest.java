package com.skillforge.core.engine;

import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.LlmRequest;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.core.llm.LlmStreamHandler;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.skill.SkillRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentLoopEngineStreamEvidenceTest {

    @Test
    void loopContext_defaultsToNoObservedProviderDelta() {
        assertThat(new LoopContext().hasObservedProviderStreamDelta()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(DeltaKind.class)
    void streamHandler_anyProviderContentDelta_recordsRetrySafetyEvidence(DeltaKind deltaKind) {
        LlmProviderFactory factory = new LlmProviderFactory();
        factory.registerProvider("fake", new FailingStreamProvider(deltaKind));
        AgentLoopEngine engine = new AgentLoopEngine(
                factory,
                "fake",
                new SkillRegistry(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
        AgentDefinition agent = new AgentDefinition();
        agent.setName("stream-evidence-agent");
        agent.setModelId("fake:model");
        agent.setSystemPrompt("test");
        agent.setConfig(Map.of("max_loops", 1));
        LoopContext context = new LoopContext();

        assertThatThrownBy(() -> engine.run(
                agent, "hello", new ArrayList<>(), "session-1", 1L, context))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("LLM call failed");

        assertThat(context.hasObservedProviderStreamDelta()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(DeltaKind.class)
    void maxTokensContinuation_anyProviderContentDelta_recordsRetrySafetyEvidence(
            DeltaKind deltaKind) {
        LlmProviderFactory factory = new LlmProviderFactory();
        factory.registerProvider("fake", new FailingContinuationProvider(deltaKind));
        AgentLoopEngine engine = new AgentLoopEngine(
                factory, "fake", new SkillRegistry(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());
        AgentDefinition agent = new AgentDefinition();
        agent.setName("continuation-evidence-agent");
        agent.setModelId("fake:model");
        agent.setSystemPrompt("test");
        agent.setConfig(Map.of("max_loops", 1, "max_tokens", 64));
        LoopContext context = new LoopContext();

        LoopResult result = engine.run(
                agent, "hello", new ArrayList<>(), "session-1", 1L, context);

        assertThat(result.getStatus()).isEqualTo("max_tokens_exhausted");
        assertThat(context.hasObservedProviderStreamDelta()).isTrue();
    }

    @Test
    void beforeLoopReplacement_streamEvidenceIsMirroredToCallerContext() {
        LlmProviderFactory factory = new LlmProviderFactory();
        factory.registerProvider("fake", new FailingStreamProvider(DeltaKind.TEXT));
        LoopHook replacingHook = new LoopHook() {
            @Override
            public LoopContext beforeLoop(LoopContext original) {
                LoopContext replacement = new LoopContext();
                replacement.setAgentDefinition(original.getAgentDefinition());
                replacement.setSessionId(original.getSessionId());
                replacement.setUserId(original.getUserId());
                replacement.setMessages(original.getMessages());
                return replacement;
            }
        };
        AgentLoopEngine engine = new AgentLoopEngine(
                factory, "fake", new SkillRegistry(), List.of(replacingHook),
                Collections.emptyList(), Collections.emptyList());
        AgentDefinition agent = new AgentDefinition();
        agent.setName("replacement-hook-agent");
        agent.setModelId("fake:model");
        agent.setSystemPrompt("test");
        agent.setConfig(Map.of("max_loops", 1));
        LoopContext callerContext = new LoopContext();

        assertThatThrownBy(() -> engine.run(
                agent, "hello", new ArrayList<>(), "session-1", 1L, callerContext))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("LLM call failed");

        assertThat(callerContext.hasObservedProviderStreamDelta()).isTrue();
    }

    @Test
    void replacementContext_toolEvidenceIsMirroredToCallerContext() {
        LoopContext callerContext = new LoopContext();
        LoopContext replacement = new LoopContext();
        replacement.mirrorRetrySafetyEvidenceTo(callerContext);

        replacement.recordToolCall("CreateTask");

        assertThat(callerContext.getToolCallCounts()).containsEntry("CreateTask", 1);
    }

    private enum DeltaKind {
        TEXT,
        REASONING,
        TOOL_USE
    }

    private record FailingStreamProvider(DeltaKind deltaKind) implements LlmProvider {
        @Override
        public String getName() {
            return "fake";
        }

        @Override
        public LlmResponse chat(LlmRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void chatStream(LlmRequest request, LlmStreamHandler handler) {
            switch (deltaKind) {
                case TEXT -> handler.onText("partial");
                case REASONING -> handler.onReasoning("thinking");
                case TOOL_USE -> {
                    handler.onToolUseStart("tool-1", "Bash");
                    handler.onToolUseInputDelta("tool-1", "{\"command\":");
                }
            }
            handler.onError(new SocketTimeoutException("stream stopped"));
        }
    }

    private static final class FailingContinuationProvider implements LlmProvider {
        private final DeltaKind deltaKind;
        private final AtomicInteger calls = new AtomicInteger();

        private FailingContinuationProvider(DeltaKind deltaKind) {
            this.deltaKind = deltaKind;
        }

        @Override
        public String getName() {
            return "fake";
        }

        @Override
        public LlmResponse chat(LlmRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void chatStream(LlmRequest request, LlmStreamHandler handler) {
            if (calls.getAndIncrement() == 0) {
                LlmResponse truncated = new LlmResponse();
                // Complete-only fake response supplies a partial assistant body without
                // invoking the primary delta callbacks; the second call therefore proves
                // the continuation handler itself records the evidence.
                truncated.setContent("partial");
                truncated.setStopReason("max_tokens");
                handler.onComplete(truncated);
                return;
            }
            switch (deltaKind) {
                case TEXT -> handler.onText("partial continuation");
                case REASONING -> handler.onReasoning("continuation thinking");
                case TOOL_USE -> handler.onToolUseStart("tool-1", "UnexpectedTool");
            }
            handler.onError(new SocketTimeoutException("continuation stopped"));
        }
    }
}
