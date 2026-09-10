package com.skillforge.core.engine;

import com.skillforge.core.compact.ContextCompactorCallback;
import com.skillforge.core.engine.durability.ConversationDurabilitySink;
import com.skillforge.core.engine.durability.DurableFrontier;
import com.skillforge.core.engine.durability.DurableToolAttemptState;
import com.skillforge.core.engine.durability.ExecutionClaimAck;
import com.skillforge.core.engine.durability.ExecutionClaimCommand;
import com.skillforge.core.engine.durability.IntentCommitAck;
import com.skillforge.core.engine.durability.IntentCommitCommand;
import com.skillforge.core.engine.durability.LoopDurabilityScope;
import com.skillforge.core.engine.durability.PersistedMessageOccurrence;
import com.skillforge.core.engine.durability.ToolExecutionScheduler;
import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.LlmRequest;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.core.llm.LlmStreamHandler;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.Message;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.model.ToolUseBlock;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class AgentLoopEngineDurableExecutionClaimBarrierTest {

    private static final LoopDurabilityScope SCOPE = new LoopDurabilityScope(
            "00000000-0000-0000-0000-000000000701",
            71L,
            5L,
            "00000000-0000-0000-0000-000000000702",
            9L,
            "claim-barrier-instance");
    private static final String TRACE_ID = "00000000-0000-0000-0000-000000000703";

    @Test
    void executionClaimFailure_preventsAssistantVisibilityFutureDispatchAndSideEffects() {
        CountingTool tool = new CountingTool();
        SkillRegistry registry = new SkillRegistry();
        registry.registerTool(tool);
        QueueProvider provider = new QueueProvider(List.of(
                toolResponse("call-normal", CountingTool.NAME, Map.of("value", "original")),
                textResponse("must not be reached")));
        RejectingClaimSink sink = new RejectingClaimSink();
        RecordingScheduler scheduler = new RecordingScheduler();
        RecordingBroadcaster broadcaster = new RecordingBroadcaster();
        AgentLoopEngine engine = engine(registry, provider, sink, scheduler, broadcaster);
        LoopContext context = context();
        List<Message> history = new ArrayList<>();

        Throwable failure = catchThrowable(() -> engine.run(
                agent(), "start", history, SCOPE.sessionId(), SCOPE.userId(), context));

        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("claim database unavailable");
        assertThat(provider.calls).hasValue(1);
        assertThat(sink.commitCalls).hasValue(1);
        assertThat(sink.claimCalls).hasValue(1);
        assertThat(sink.claimCommand.claimant()).isEqualTo(SCOPE);
        assertThat(sink.claimCommand.attemptId()).isEqualTo(801L);
        assertThat(sink.claimCommand.stepId()).isEqualTo(sink.intentCommand.stepId());
        assertThat(sink.claimCommand.claimRequestId()).isNotNull();
        assertThat(sink.claimCommand.expectedState())
                .isEqualTo(DurableToolAttemptState.INTENT_COMMITTED);
        assertThat(sink.claimCommand.expectedGeneration()).isZero();
        assertThat(sink.calls).containsExactly("commitIntent", "claimExecution");
        assertThat(context.getExpectedDurableFrontier()).isEqualTo(DurableFrontier.EMPTY);
        assertThat(history).noneMatch(message -> message.getRole() == Message.Role.ASSISTANT);
        assertThat(broadcaster.messageAppended).hasValue(0);
        assertThat(broadcaster.toolStarted).hasValue(0);
        assertThat(broadcaster.toolFinished).hasValue(0);
        assertThat(scheduler.submissions).hasValue(0);
        assertThat(tool.executions).hasValue(0);
    }

    @Test
    void executionClaimFailure_preventsContextCompactionAndDurableBroadcast() {
        QueueProvider provider = new QueueProvider(List.of(
                toolResponse("call-compact", "compact_context",
                        Map.of("level", "full", "reason", "claim gate")),
                textResponse("must not be reached")));
        RejectingClaimSink sink = new RejectingClaimSink();
        RecordingScheduler scheduler = new RecordingScheduler();
        RecordingBroadcaster broadcaster = new RecordingBroadcaster();
        RecordingCompactor compactor = new RecordingCompactor();
        AgentLoopEngine engine = engine(
                new SkillRegistry(), provider, sink, scheduler, broadcaster);
        engine.setCompactorCallback(compactor);
        LoopContext context = context();
        List<Message> history = new ArrayList<>();

        Throwable failure = catchThrowable(() -> engine.run(
                agent(), "start", history, SCOPE.sessionId(), SCOPE.userId(), context));

        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("claim database unavailable");
        assertThat(provider.calls).hasValue(1);
        assertThat(sink.calls).containsExactly("commitIntent", "claimExecution");
        assertThat(compactor.lightCalls).hasValue(0);
        assertThat(compactor.fullCalls).hasValue(0);
        assertThat(history).noneMatch(message -> message.getRole() == Message.Role.ASSISTANT);
        assertThat(broadcaster.messageAppended).hasValue(0);
        assertThat(broadcaster.toolStarted).hasValue(0);
        assertThat(scheduler.submissions).hasValue(0);
    }

    private static AgentLoopEngine engine(
            SkillRegistry registry,
            QueueProvider provider,
            ConversationDurabilitySink sink,
            ToolExecutionScheduler scheduler,
            ChatEventBroadcaster broadcaster) {
        LlmProviderFactory factory = new LlmProviderFactory();
        factory.registerProvider("fake", provider);
        AgentLoopEngine engine = new AgentLoopEngine(
                factory, "fake", registry, List.of(), List.of(), List.of());
        engine.setConversationDurabilitySink(sink);
        engine.setToolExecutionScheduler(scheduler);
        engine.setBroadcaster(broadcaster);
        return engine;
    }

    private static LoopContext context() {
        LoopContext context = new LoopContext();
        context.setDurabilityScope(SCOPE);
        context.setExpectedDurableFrontier(DurableFrontier.EMPTY);
        context.setTraceId(TRACE_ID);
        return context;
    }

    private static AgentDefinition agent() {
        AgentDefinition agent = new AgentDefinition();
        agent.setName("durable-claim-barrier");
        agent.setModelId("fake:model");
        agent.setSystemPrompt("test");
        agent.setConfig(Map.of("max_loops", 3));
        return agent;
    }

    private static LlmResponse toolResponse(
            String toolUseId, String toolName, Map<String, Object> input) {
        LlmResponse response = new LlmResponse();
        response.setStopReason("tool_use");
        response.setToolUseBlocks(List.of(new ToolUseBlock(toolUseId, toolName, input)));
        return response;
    }

    private static LlmResponse textResponse(String text) {
        LlmResponse response = new LlmResponse();
        response.setStopReason("end_turn");
        response.setContent(text);
        return response;
    }

    private static final class RejectingClaimSink implements ConversationDurabilitySink {
        private final AtomicInteger commitCalls = new AtomicInteger();
        private final AtomicInteger claimCalls = new AtomicInteger();
        private final List<String> calls = new ArrayList<>();
        private IntentCommitCommand intentCommand;
        private ExecutionClaimCommand claimCommand;

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public IntentCommitAck commitIntent(IntentCommitCommand command) {
            intentCommand = command;
            commitCalls.incrementAndGet();
            calls.add("commitIntent");
            PersistedMessageOccurrence assistant = new PersistedMessageOccurrence(
                    701L,
                    0L,
                    command.writeBatchId(),
                    0,
                    command.assistant(),
                    "NORMAL",
                    "normal",
                    null,
                    null,
                    Map.of(),
                    command.traceId());
            return new IntentCommitAck(
                    801L,
                    command.stepId(),
                    assistant,
                    command.expectedPreIntentFrontier(),
                    command.manifest(),
                    "a".repeat(64),
                    "b".repeat(64),
                    command.manifest().replaySafety());
        }

        @Override
        public ExecutionClaimAck claimExecution(ExecutionClaimCommand command) {
            claimCommand = command;
            claimCalls.incrementAndGet();
            calls.add("claimExecution");
            throw new IllegalStateException("claim database unavailable");
        }
    }

    private static final class RecordingScheduler implements ToolExecutionScheduler {
        private final AtomicInteger submissions = new AtomicInteger();

        @Override
        public <T> CompletableFuture<T> submit(Supplier<T> task) {
            submissions.incrementAndGet();
            try {
                return CompletableFuture.completedFuture(task.get());
            } catch (Throwable failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }
    }

    private static final class QueueProvider implements LlmProvider {
        private final Queue<LlmResponse> responses;
        private final AtomicInteger calls = new AtomicInteger();

        private QueueProvider(List<LlmResponse> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public String getName() {
            return "fake";
        }

        @Override
        public LlmResponse chat(LlmRequest request) {
            calls.incrementAndGet();
            return responses.remove();
        }

        @Override
        public void chatStream(LlmRequest request, LlmStreamHandler handler) {
            calls.incrementAndGet();
            handler.onComplete(responses.remove());
        }
    }

    private static final class CountingTool implements Tool {
        private static final String NAME = "ClaimBarrierWriteProbe";
        private final AtomicInteger executions = new AtomicInteger();

        @Override
        public String getName() {
            return NAME;
        }

        @Override
        public String getDescription() {
            return "Counts execution side effects";
        }

        @Override
        public ToolSchema getToolSchema() {
            return new ToolSchema(NAME, getDescription(), Map.of("type", "object"));
        }

        @Override
        public SkillResult execute(Map<String, Object> input, SkillContext context) {
            executions.incrementAndGet();
            return SkillResult.success("executed");
        }
    }

    private static final class RecordingCompactor implements ContextCompactorCallback {
        private final AtomicInteger lightCalls = new AtomicInteger();
        private final AtomicInteger fullCalls = new AtomicInteger();

        @Override
        public CompactCallbackResult compactLight(
                String sessionId, List<Message> currentMessages, String sourceLabel, String reason) {
            lightCalls.incrementAndGet();
            return CompactCallbackResult.noOp(currentMessages, "unexpected");
        }

        @Override
        public CompactCallbackResult compactFull(
                String sessionId, List<Message> currentMessages, String sourceLabel, String reason) {
            fullCalls.incrementAndGet();
            return CompactCallbackResult.noOp(currentMessages, "unexpected");
        }
    }

    private static final class RecordingBroadcaster implements ChatEventBroadcaster {
        private final AtomicInteger messageAppended = new AtomicInteger();
        private final AtomicInteger toolStarted = new AtomicInteger();
        private final AtomicInteger toolFinished = new AtomicInteger();

        @Override
        public void sessionStatus(String sessionId, String status, String step, String error) {
        }

        @Override
        public void messageAppended(String sessionId, String traceId, Message message) {
            messageAppended.incrementAndGet();
        }

        @Override
        public void askUser(String sessionId, AskUserEvent event) {
        }

        @Override
        public void toolStarted(
                String sessionId, String toolUseId, String name, Map<String, Object> input) {
            toolStarted.incrementAndGet();
        }

        @Override
        public void toolFinished(
                String sessionId, String toolUseId, String status, long durationMs, String error) {
            toolFinished.incrementAndGet();
        }
    }
}
