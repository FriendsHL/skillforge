package com.skillforge.core.engine;

import com.skillforge.core.compact.ContextCompactorCallback;
import com.skillforge.core.engine.confirm.ConfirmationPrompter;
import com.skillforge.core.engine.confirm.ConfirmationPromptPayload;
import com.skillforge.core.engine.confirm.Decision;
import com.skillforge.core.engine.durability.ConversationDurabilitySink;
import com.skillforge.core.engine.durability.DurableFrontier;
import com.skillforge.core.engine.durability.FrozenJson;
import com.skillforge.core.engine.durability.IntentCommitAck;
import com.skillforge.core.engine.durability.IntentCommitCommand;
import com.skillforge.core.engine.durability.InteractiveIntentAck;
import com.skillforge.core.engine.durability.InteractiveIntentCommand;
import com.skillforge.core.engine.durability.LoopDurabilityScope;
import com.skillforge.core.engine.durability.MessageSnapshot;
import com.skillforge.core.engine.durability.ReplaySafety;
import com.skillforge.core.engine.durability.ToolCallIntent;
import com.skillforge.core.engine.durability.ToolCallManifest;
import com.skillforge.core.engine.durability.ToolExecutionScheduler;
import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.LlmRequest;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.core.llm.LlmStreamHandler;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.ContentBlock;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class AgentLoopEngineDurableIntentBarrierTest {

    @Test
    void intentCommitFailure_preventsToolEventsSchedulingDispatchSideEffectsAndDurableBroadcast() {
        Map<String, Object> originalInput = new LinkedHashMap<>();
        originalInput.put("path", "/tmp/durable-intent-probe");
        originalInput.put("content", "original-payload");
        Map<String, Object> expectedInput = new LinkedHashMap<>(originalInput);

        CountingTool tool = new CountingTool();
        SkillRegistry registry = new SkillRegistry();
        registry.registerTool(tool);
        CountingQueueProvider provider = new CountingQueueProvider(List.of(
                toolResponse("I will write the probe.", "private-reasoning", originalInput),
                textResponse("must not be reached")));
        LlmProviderFactory factory = new LlmProviderFactory();
        factory.registerProvider("fake", provider);

        RecordingBroadcaster broadcaster = new RecordingBroadcaster();
        RecordingToolExecutionScheduler scheduler = new RecordingToolExecutionScheduler();
        FailingConversationDurabilitySink durabilitySink = new FailingConversationDurabilitySink();
        AgentLoopEngine engine = new AgentLoopEngine(factory, "fake", registry,
                List.of(), List.of(), List.of());
        engine.setBroadcaster(broadcaster);
        engine.setConversationDurabilitySink(durabilitySink);
        engine.setToolExecutionScheduler(scheduler);

        LoopDurabilityScope scope = new LoopDurabilityScope(
                "00000000-0000-0000-0000-000000000101",
                7L,
                11L,
                "00000000-0000-0000-0000-000000000201",
                13L,
                "instance-red-1");
        LoopContext context = new LoopContext();
        context.setDurabilityScope(scope);
        context.setExpectedDurableFrontier(DurableFrontier.EMPTY);
        context.setTraceId("00000000-0000-0000-0000-000000000301");
        List<Message> history = new ArrayList<>();

        Throwable failure = catchThrowable(() -> engine.run(
                agent(), "start", history, scope.sessionId(), scope.userId(), context));

        // Mutating caller-owned input after the boundary must not mutate the captured command.
        originalInput.put("content", "mutated-after-commit-attempt");

        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("intent database unavailable");
        assertThat(provider.calls).hasValue(1);
        assertThat(durabilitySink.commitCalls).hasValue(1);
        assertThat(durabilitySink.command).isNotNull();
        assertThat(durabilitySink.command.origin()).isEqualTo(scope);
        assertThat(durabilitySink.command.stepId()).isNotNull();
        assertThat(durabilitySink.command.writeBatchId()).isNotBlank();
        assertThat(durabilitySink.command.traceId()).isEqualTo(context.getTraceId());
        assertThat(durabilitySink.command.assistant()).isEqualTo(
                MessageSnapshot.capture(expectedAssistant(expectedInput)));
        assertThat(durabilitySink.command.assistant().toMessage().getToolUseBlocks())
                .extracting(ToolUseBlock::getId, ToolUseBlock::getName, ToolUseBlock::getInput)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        "call-1", CountingTool.NAME, expectedInput));
        assertThat(durabilitySink.command.manifest()).isEqualTo(new ToolCallManifest(
                List.of(new ToolCallIntent(
                        0,
                        "call-1",
                        CountingTool.NAME,
                        FrozenJson.capture(expectedInput),
                        ReplaySafety.MUTATING)),
                ReplaySafety.MUTATING));

        assertThat(scheduler.submissions).hasValue(0);
        assertThat(tool.executions).hasValue(0);
        assertThat(broadcaster.toolStarted).hasValue(0);
        assertThat(broadcaster.toolFinished).hasValue(0);
        assertThat(broadcaster.messageAppended).hasValue(0);
        assertThat(durabilitySink.calls).containsExactly("commitIntent");
        assertThat(history).noneMatch(message -> message.getRole() == Message.Role.ASSISTANT);
    }

    @Test
    void askUserIntentCommitFailure_preventsPendingRegistrationOrAwaitAndAllDownstreamSignals() {
        SpecialBarrierHarness harness = specialBarrierHarness(
                "ask-1",
                "ask_user",
                Map.of(
                        "question", "Which environment?",
                        "context", "Deployment target",
                        "options", List.of("staging", "production"),
                        "allowOther", false));
        RecordingPendingAskRegistry pendingAskRegistry = new RecordingPendingAskRegistry();
        harness.engine.setPendingAskRegistry(pendingAskRegistry);

        harness.run();

        assertSpecialBarrierClosed(harness);
        assertThat(pendingAskRegistry.registerCalls).hasValue(0);
        assertThat(pendingAskRegistry.awaitCalls).hasValue(0);
        assertThat(pendingAskRegistry.pendingChecks).hasValue(0);
    }

    @Test
    void confirmationIntentCommitFailure_preventsPromptAndAllDownstreamSignals() {
        SpecialBarrierHarness harness = specialBarrierHarness(
                "bash-1",
                "Bash",
                Map.of("command", "clawhub install left-pad"));
        RecordingPendingAskRegistry pendingAskRegistry = new RecordingPendingAskRegistry();
        RecordingConfirmationPrompter confirmationPrompter = new RecordingConfirmationPrompter();
        harness.engine.setPendingAskRegistry(pendingAskRegistry);
        harness.engine.setConfirmationPrompter(confirmationPrompter);

        harness.run();

        assertSpecialBarrierClosed(harness);
        assertThat(confirmationPrompter.blockingCalls).hasValue(0);
        assertThat(confirmationPrompter.nonBlockingCalls).hasValue(0);
        assertThat(pendingAskRegistry.registerCalls).hasValue(0);
        assertThat(pendingAskRegistry.awaitCalls).hasValue(0);
        assertThat(pendingAskRegistry.pendingChecks).hasValue(0);
    }

    @Test
    void compactContextIntentCommitFailure_preventsCompactorAndAllDownstreamSignals() {
        SpecialBarrierHarness harness = specialBarrierHarness(
                "compact-1",
                "compact_context",
                Map.of("level", "full", "reason", "recover token budget"));
        RecordingCompactor compactor = new RecordingCompactor();
        harness.engine.setCompactorCallback(compactor);

        harness.run();

        assertSpecialBarrierClosed(harness);
        assertThat(compactor.lightCalls).hasValue(0);
        assertThat(compactor.fullCalls).hasValue(0);
    }

    private static SpecialBarrierHarness specialBarrierHarness(
            String toolUseId, String toolName, Map<String, Object> input) {
        SkillRegistry registry = new SkillRegistry();
        CountingQueueProvider provider = new CountingQueueProvider(List.of(
                specialToolResponse(toolUseId, toolName, input),
                textResponse("must not be reached")));
        LlmProviderFactory factory = new LlmProviderFactory();
        factory.registerProvider("fake", provider);
        RecordingBroadcaster broadcaster = new RecordingBroadcaster();
        RecordingToolExecutionScheduler scheduler = new RecordingToolExecutionScheduler();
        FailingConversationDurabilitySink durabilitySink = new FailingConversationDurabilitySink();
        AgentLoopEngine engine = new AgentLoopEngine(factory, "fake", registry,
                List.of(), List.of(), List.of());
        engine.setBroadcaster(broadcaster);
        engine.setConversationDurabilitySink(durabilitySink);
        engine.setToolExecutionScheduler(scheduler);

        LoopDurabilityScope scope = new LoopDurabilityScope(
                "00000000-0000-0000-0000-000000000111",
                17L,
                19L,
                "00000000-0000-0000-0000-000000000211",
                23L,
                "instance-red-1b");
        LoopContext context = new LoopContext();
        context.setDurabilityScope(scope);
        context.setExpectedDurableFrontier(DurableFrontier.EMPTY);
        context.setTraceId("00000000-0000-0000-0000-000000000311");
        return new SpecialBarrierHarness(
                engine, provider, durabilitySink, scheduler, broadcaster, scope, context);
    }

    private static void assertSpecialBarrierClosed(SpecialBarrierHarness harness) {
        assertThat(harness.provider.calls).hasValue(1);
        assertThat(harness.durabilitySink.commitCalls).hasValue(1);
        String toolName = harness.durabilitySink.command.manifest().calls().get(0).toolName();
        assertThat(harness.durabilitySink.calls).containsExactly(
                "compact_context".equals(toolName)
                        ? "commitIntent"
                        : "commitInteractiveIntent");
        assertThat(harness.scheduler.submissions).hasValue(0);
        assertThat(harness.broadcaster.toolStarted).hasValue(0);
        assertThat(harness.broadcaster.toolFinished).hasValue(0);
        assertThat(harness.broadcaster.messageAppended).hasValue(0);
        assertThat(harness.history)
                .noneMatch(message -> message.getRole() == Message.Role.ASSISTANT);
    }

    private static AgentDefinition agent() {
        AgentDefinition agent = new AgentDefinition();
        agent.setName("durable-intent-red");
        agent.setModelId("fake:model");
        agent.setSystemPrompt("test");
        agent.setConfig(Map.of("max_loops", 3));
        return agent;
    }

    private static LlmResponse toolResponse(String text, String reasoning, Map<String, Object> input) {
        LlmResponse response = new LlmResponse();
        response.setContent(text);
        response.setReasoningContent(reasoning);
        response.setStopReason("tool_use");
        response.setToolUseBlocks(List.of(new ToolUseBlock("call-1", CountingTool.NAME, input)));
        return response;
    }

    private static LlmResponse textResponse(String text) {
        LlmResponse response = new LlmResponse();
        response.setContent(text);
        response.setStopReason("end_turn");
        return response;
    }

    private static LlmResponse specialToolResponse(
            String toolUseId, String toolName, Map<String, Object> input) {
        LlmResponse response = new LlmResponse();
        response.setStopReason("tool_use");
        response.setToolUseBlocks(List.of(new ToolUseBlock(toolUseId, toolName, input)));
        return response;
    }

    private static Message expectedAssistant(Map<String, Object> input) {
        Message assistant = new Message();
        assistant.setRole(Message.Role.ASSISTANT);
        assistant.setReasoningContent("private-reasoning");
        assistant.setContent(List.of(
                ContentBlock.text("I will write the probe."),
                ContentBlock.toolUse("call-1", CountingTool.NAME, input)));
        return assistant;
    }

    private static final class FailingConversationDurabilitySink implements ConversationDurabilitySink {
        private final AtomicInteger commitCalls = new AtomicInteger();
        private final List<String> calls = new ArrayList<>();
        private IntentCommitCommand command;

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public IntentCommitAck commitIntent(IntentCommitCommand command) {
            this.command = command;
            calls.add("commitIntent");
            commitCalls.incrementAndGet();
            throw new IllegalStateException("intent database unavailable");
        }

        @Override
        public InteractiveIntentAck commitInteractiveIntent(InteractiveIntentCommand command) {
            this.command = command.intent();
            calls.add("commitInteractiveIntent");
            commitCalls.incrementAndGet();
            throw new IllegalStateException("intent database unavailable");
        }
    }

    private static final class RecordingToolExecutionScheduler implements ToolExecutionScheduler {
        private final AtomicInteger submissions = new AtomicInteger();

        @Override
        public <T> CompletableFuture<T> submit(Supplier<T> task) {
            submissions.incrementAndGet();
            return CompletableFuture.failedFuture(
                    new AssertionError("tool future must not be submitted before intent ACK"));
        }
    }

    private static final class RecordingPendingAskRegistry extends PendingAskRegistry {
        private final AtomicInteger registerCalls = new AtomicInteger();
        private final AtomicInteger awaitCalls = new AtomicInteger();
        private final AtomicInteger pendingChecks = new AtomicInteger();

        @Override
        public PendingAsk register(String askId, String sessionId) {
            registerCalls.incrementAndGet();
            return new PendingAsk(sessionId);
        }

        @Override
        public String await(String askId, long timeoutSeconds) {
            awaitCalls.incrementAndGet();
            return "unexpected-answer";
        }

        @Override
        public boolean hasPendingForSession(String sessionId) {
            pendingChecks.incrementAndGet();
            return false;
        }
    }

    private static final class RecordingConfirmationPrompter implements ConfirmationPrompter {
        private final AtomicInteger blockingCalls = new AtomicInteger();
        private final AtomicInteger nonBlockingCalls = new AtomicInteger();

        @Override
        public Decision prompt(ConfirmationRequest request) {
            blockingCalls.incrementAndGet();
            return Decision.DENIED;
        }

        @Override
        public ConfirmationPromptPayload promptNonBlocking(ConfirmationRequest request) {
            nonBlockingCalls.incrementAndGet();
            return new ConfirmationPromptPayload(
                    "confirmation-red-1b",
                    request.sessionId(),
                    request.installTool(),
                    request.installTarget(),
                    request.command(),
                    "Confirmation",
                    "Must remain behind the durable intent barrier",
                    List.of(),
                    Instant.parse("2026-09-02T00:00:00Z"));
        }
    }

    private static final class RecordingCompactor implements ContextCompactorCallback {
        private final AtomicInteger lightCalls = new AtomicInteger();
        private final AtomicInteger fullCalls = new AtomicInteger();

        @Override
        public CompactCallbackResult compactLight(
                String sessionId, List<Message> currentMessages, String sourceLabel, String reason) {
            lightCalls.incrementAndGet();
            return CompactCallbackResult.noOp(currentMessages, "unexpected light compact");
        }

        @Override
        public CompactCallbackResult compactFull(
                String sessionId, List<Message> currentMessages, String sourceLabel, String reason) {
            fullCalls.incrementAndGet();
            return CompactCallbackResult.noOp(currentMessages, "unexpected full compact");
        }
    }

    private static final class SpecialBarrierHarness {
        private final AgentLoopEngine engine;
        private final CountingQueueProvider provider;
        private final FailingConversationDurabilitySink durabilitySink;
        private final RecordingToolExecutionScheduler scheduler;
        private final RecordingBroadcaster broadcaster;
        private final LoopDurabilityScope scope;
        private final LoopContext context;
        private final List<Message> history = new ArrayList<>();

        private SpecialBarrierHarness(
                AgentLoopEngine engine,
                CountingQueueProvider provider,
                FailingConversationDurabilitySink durabilitySink,
                RecordingToolExecutionScheduler scheduler,
                RecordingBroadcaster broadcaster,
                LoopDurabilityScope scope,
                LoopContext context) {
            this.engine = engine;
            this.provider = provider;
            this.durabilitySink = durabilitySink;
            this.scheduler = scheduler;
            this.broadcaster = broadcaster;
            this.scope = scope;
            this.context = context;
        }

        private void run() {
            Throwable failure = catchThrowable(() -> engine.run(
                    agent(), "start", history, scope.sessionId(), scope.userId(), context));
            assertThat(failure)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("intent database unavailable");
        }
    }

    private static final class CountingQueueProvider implements LlmProvider {
        private final Queue<LlmResponse> responses;
        private final AtomicInteger calls = new AtomicInteger();

        private CountingQueueProvider(List<LlmResponse> responses) {
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
        private static final String NAME = "WriteProbe";
        private final AtomicInteger executions = new AtomicInteger();

        @Override
        public String getName() {
            return NAME;
        }

        @Override
        public String getDescription() {
            return "Mutating durability barrier probe";
        }

        @Override
        public ToolSchema getToolSchema() {
            ToolSchema schema = new ToolSchema();
            schema.setName(NAME);
            schema.setDescription(getDescription());
            schema.setInputSchema(Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "path", Map.of("type", "string"),
                            "content", Map.of("type", "string")),
                    "required", List.of("path", "content")));
            return schema;
        }

        @Override
        public SkillResult execute(Map<String, Object> input, SkillContext context) {
            executions.incrementAndGet();
            return SkillResult.success("side effect executed");
        }

        @Override
        public boolean isReadOnly() {
            return false;
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
        public void toolStarted(String sessionId, String toolUseId,
                                String name, Map<String, Object> input) {
            toolStarted.incrementAndGet();
        }

        @Override
        public void toolFinished(String sessionId, String toolUseId,
                                 String status, long durationMs, String error) {
            toolFinished.incrementAndGet();
        }
    }
}
