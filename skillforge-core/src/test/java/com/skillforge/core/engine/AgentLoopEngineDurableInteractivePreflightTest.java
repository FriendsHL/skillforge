package com.skillforge.core.engine;

import com.skillforge.core.engine.confirm.ConfirmationPromptPayload;
import com.skillforge.core.engine.confirm.ConfirmationPrompter;
import com.skillforge.core.engine.confirm.Decision;
import com.skillforge.core.engine.durability.ConversationDurabilitySink;
import com.skillforge.core.engine.durability.DurableFrontier;
import com.skillforge.core.engine.durability.ExecutionClaimAck;
import com.skillforge.core.engine.durability.ExecutionClaimCommand;
import com.skillforge.core.engine.durability.IntentCommitAck;
import com.skillforge.core.engine.durability.IntentCommitCommand;
import com.skillforge.core.engine.durability.InteractiveIntentAck;
import com.skillforge.core.engine.durability.InteractiveIntentCommand;
import com.skillforge.core.engine.durability.InteractiveStepPlanner;
import com.skillforge.core.engine.durability.LoopDurabilityScope;
import com.skillforge.core.engine.durability.MessageSnapshot;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class AgentLoopEngineDurableInteractivePreflightTest {

    private static final String SESSION_ID = "00000000-0000-0000-0000-000000000801";
    private static final long USER_ID = 83L;
    private static final String LOOP_ID = "00000000-0000-0000-0000-000000000802";
    private static final String TRACE_ID = "00000000-0000-0000-0000-000000000803";
    private static final DurableFrontier PRE_INTENT_FRONTIER = new DurableFrontier(80L, 7L);
    private static final long ASSISTANT_MESSAGE_ID = 91L;
    private static final long ASSISTANT_SEQ = 8L;
    private static final long CONTROL_MESSAGE_ID = 92L;
    private static final long CONTROL_SEQ = 9L;

    @Test
    void normalThenAsk_commitsWholeInteractiveVectorBeforeAnySideEffect() {
        verifyInteractiveVector(
                List.of(
                        normalCall("normal-0"),
                        askCall("ask-1")),
                1,
                InteractiveStepPlanner.CallKind.ASK_USER);
    }

    @Test
    void askThenNormal_commitsWholeInteractiveVectorBeforeAnySideEffect() {
        verifyInteractiveVector(
                List.of(
                        askCall("ask-0"),
                        normalCall("normal-1")),
                0,
                InteractiveStepPlanner.CallKind.ASK_USER);
    }

    @Test
    void normalConfirmationNormal_commitsWholeInteractiveVectorBeforeAnySideEffect() {
        verifyInteractiveVector(
                List.of(
                        normalCall("normal-0"),
                        confirmationCall("confirm-1"),
                        normalCall("normal-2")),
                1,
                InteractiveStepPlanner.CallKind.CONFIRMATION);
    }

    @Test
    void multipleInteractiveCalls_selectsFirstAndExecutesNoSibling() {
        verifyInteractiveVector(
                List.of(
                        normalCall("normal-0"),
                        confirmationCall("confirm-1"),
                        askCall("ask-2")),
                1,
                InteractiveStepPlanner.CallKind.CONFIRMATION);
    }

    private static void verifyInteractiveVector(
            List<ToolUseBlock> calls,
            int expectedSelectedOrdinal,
            InteractiveStepPlanner.CallKind expectedKind) {
        List<String> timeline = new ArrayList<>();
        CountingProvider provider = new CountingProvider(toolResponse(calls));
        RecordingScheduler scheduler = new RecordingScheduler(timeline);
        RecordingBroadcaster broadcaster = new RecordingBroadcaster(timeline);
        RecordingPendingAskRegistry pendingAskRegistry = new RecordingPendingAskRegistry();
        RecordingConfirmationPrompter confirmationPrompter =
                new RecordingConfirmationPrompter();
        SkillRegistry registry = new SkillRegistry();
        List<CountingTool> executableTools = calls.stream()
                .filter(call -> !"ask_user".equals(call.getName()))
                .map(call -> new CountingTool(call.getName()))
                .toList();
        executableTools.forEach(registry::registerTool);
        StrictInteractiveSink sink = new StrictInteractiveSink(
                timeline,
                scheduler,
                broadcaster,
                pendingAskRegistry,
                confirmationPrompter,
                executableTools);

        LlmProviderFactory providerFactory = new LlmProviderFactory();
        providerFactory.registerProvider("fake", provider);
        AgentLoopEngine engine = new AgentLoopEngine(
                providerFactory, "fake", registry, List.of(), List.of(), List.of());
        engine.setConversationDurabilitySink(sink);
        engine.setToolExecutionScheduler(scheduler);
        engine.setBroadcaster(broadcaster);
        engine.setPendingAskRegistry(pendingAskRegistry);
        engine.setConfirmationPrompter(confirmationPrompter);
        LoopContext context = durableContext();

        LoopResult result = engine.run(
                agent(), "start", new ArrayList<>(), SESSION_ID, USER_ID, context);

        assertThat(result.getStatus()).isEqualTo("waiting_user");
        assertThat(provider.calls).hasValue(1);
        assertThat(sink.interactiveCommitCalls).hasValue(1);
        assertThat(sink.ordinaryCommitCalls).hasValue(0);
        assertThat(sink.claimCalls).hasValue(0);
        assertThat(sink.noSideEffectsAtCommit).isTrue();
        assertThat(scheduler.submissions).hasValue(0);
        assertThat(executableTools)
                .allSatisfy(tool -> assertThat(tool.executions).hasValue(0));
        assertThat(pendingAskRegistry.totalCalls()).isZero();
        assertThat(confirmationPrompter.totalCalls()).isZero();
        assertThat(broadcaster.toolStarted).hasValue(0);
        assertThat(broadcaster.toolFinished).hasValue(0);

        assertThat(sink.command.plan().calls())
                .extracting(call -> call.toolUseId())
                .containsExactlyElementsOf(calls.stream().map(ToolUseBlock::getId).toList());
        assertThat(sink.command.plan().selectedControl().providerOrdinal())
                .isEqualTo(expectedSelectedOrdinal);
        assertThat(sink.command.plan().selectedControl().kind()).isEqualTo(expectedKind);
        assertThat(result.getPendingControl().getToolUseId())
                .isEqualTo(calls.get(expectedSelectedOrdinal).getId());
        assertThat(result.getPendingControl().getToolName())
                .isEqualTo(calls.get(expectedSelectedOrdinal).getName());

        MessageSnapshot acknowledgedAssistant = sink.ack.intent().assistant().message();
        assertThat(result.getMessages())
                .filteredOn(message -> message.getRole() == Message.Role.ASSISTANT)
                .singleElement()
                .satisfies(message -> assertThat(MessageSnapshot.capture(message))
                        .isEqualTo(acknowledgedAssistant));
        assertThat(MessageSnapshot.capture(
                result.getPendingControl().getAssistantToolUseMessage()))
                .isEqualTo(acknowledgedAssistant);
        assertThat(context.getExpectedDurableFrontier()).isEqualTo(new DurableFrontier(
                sink.ack.control().messageId(), sink.ack.control().seqNo()));
        assertThat(context.getExpectedDurableFrontier())
                .isEqualTo(new DurableFrontier(CONTROL_MESSAGE_ID, CONTROL_SEQ));

        String controlBroadcast = expectedKind == InteractiveStepPlanner.CallKind.ASK_USER
                ? "broadcast:ask"
                : "broadcast:confirmation";
        assertThat(timeline).containsExactly(
                "commitInteractiveIntent:start",
                "commitInteractiveIntent:ack",
                "broadcast:assistant",
                controlBroadcast);
        assertThat(broadcaster.appended)
                .singleElement()
                .satisfies(message -> assertThat(MessageSnapshot.capture(message))
                        .isEqualTo(acknowledgedAssistant));
    }

    private static ToolUseBlock normalCall(String id) {
        return new ToolUseBlock(id, "NormalProbe", Map.of("value", id));
    }

    private static ToolUseBlock askCall(String id) {
        return new ToolUseBlock(id, "ask_user", Map.of(
                "question", "Choose for " + id,
                "context", "Durable preflight",
                "options", List.of("one", "two"),
                "allowOther", false));
    }

    private static ToolUseBlock confirmationCall(String id) {
        return new ToolUseBlock(
                id, "Bash", Map.of("command", "clawhub install durable-probe"));
    }

    private static LlmResponse toolResponse(List<ToolUseBlock> calls) {
        LlmResponse response = new LlmResponse();
        response.setContent("Persist the complete interactive vector.");
        response.setReasoningContent("preflight reasoning");
        response.setStopReason("tool_use");
        response.setToolUseBlocks(calls);
        return response;
    }

    private static AgentDefinition agent() {
        AgentDefinition agent = new AgentDefinition();
        agent.setName("durable-interactive-preflight");
        agent.setModelId("fake:model");
        agent.setSystemPrompt("test");
        agent.setConfig(Map.of("max_loops", 3, "execution_mode", "ask"));
        return agent;
    }

    private static LoopContext durableContext() {
        LoopContext context = new LoopContext();
        context.setTraceId(TRACE_ID);
        context.setDurabilityScope(new LoopDurabilityScope(
                SESSION_ID, USER_ID, 17L, LOOP_ID, 19L, "interactive-preflight-owner"));
        context.setExpectedDurableFrontier(PRE_INTENT_FRONTIER);
        return context;
    }

    private static final class StrictInteractiveSink implements ConversationDurabilitySink {
        private final List<String> timeline;
        private final RecordingScheduler scheduler;
        private final RecordingBroadcaster broadcaster;
        private final RecordingPendingAskRegistry pendingAskRegistry;
        private final RecordingConfirmationPrompter confirmationPrompter;
        private final List<CountingTool> tools;
        private final AtomicInteger ordinaryCommitCalls = new AtomicInteger();
        private final AtomicInteger interactiveCommitCalls = new AtomicInteger();
        private final AtomicInteger claimCalls = new AtomicInteger();
        private InteractiveIntentCommand command;
        private InteractiveIntentAck ack;
        private boolean noSideEffectsAtCommit;

        private StrictInteractiveSink(
                List<String> timeline,
                RecordingScheduler scheduler,
                RecordingBroadcaster broadcaster,
                RecordingPendingAskRegistry pendingAskRegistry,
                RecordingConfirmationPrompter confirmationPrompter,
                List<CountingTool> tools) {
            this.timeline = timeline;
            this.scheduler = scheduler;
            this.broadcaster = broadcaster;
            this.pendingAskRegistry = pendingAskRegistry;
            this.confirmationPrompter = confirmationPrompter;
            this.tools = tools;
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public IntentCommitAck commitIntent(IntentCommitCommand command) {
            ordinaryCommitCalls.incrementAndGet();
            throw new AssertionError("interactive vectors must use commitInteractiveIntent");
        }

        @Override
        public InteractiveIntentAck commitInteractiveIntent(InteractiveIntentCommand command) {
            timeline.add("commitInteractiveIntent:start");
            interactiveCommitCalls.incrementAndGet();
            this.command = command;
            noSideEffectsAtCommit = scheduler.submissions.get() == 0
                    && broadcaster.eventsBeforeAck() == 0
                    && pendingAskRegistry.totalCalls() == 0
                    && confirmationPrompter.totalCalls() == 0
                    && tools.stream().allMatch(tool -> tool.executions.get() == 0);
            ack = validAck(command);
            timeline.add("commitInteractiveIntent:ack");
            return ack;
        }

        @Override
        public ExecutionClaimAck claimExecution(ExecutionClaimCommand command) {
            claimCalls.incrementAndGet();
            throw new AssertionError("WAITING_USER must not claim execution before an answer");
        }

        private static InteractiveIntentAck validAck(InteractiveIntentCommand command) {
            IntentCommitAck intent = new IntentCommitAck(
                    701L,
                    command.intent().stepId(),
                    new PersistedMessageOccurrence(
                            ASSISTANT_MESSAGE_ID,
                            ASSISTANT_SEQ,
                            command.intent().writeBatchId(),
                            0,
                            command.intent().assistant(),
                            "NORMAL",
                            "normal",
                            null,
                            null,
                            Map.of(),
                            command.intent().traceId()),
                    command.intent().expectedPreIntentFrontier(),
                    command.intent().manifest(),
                    "a".repeat(64),
                    "b".repeat(64),
                    command.intent().manifest().replaySafety());
            String interactionKind = command.plan().selectedControl().kind()
                    == InteractiveStepPlanner.CallKind.ASK_USER
                            ? "ask_user"
                            : "confirmation";
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("controlId", command.controlId());
            metadata.put("interactionKind", interactionKind);
            metadata.put("toolUseId", command.plan().selectedControl().call().toolUseId());
            metadata.put("toolName", command.plan().selectedControl().call().toolName());
            metadata.put("providerOrdinal", command.plan().selectedControl().providerOrdinal());
            metadata.put("state", "pending");
            metadata.put("attemptId", intent.attemptId());
            metadata.put("stepId", intent.stepId().toString());
            metadata.put("payload", command.payload().toJavaValue());
            PersistedMessageOccurrence control = new PersistedMessageOccurrence(
                    CONTROL_MESSAGE_ID,
                    CONTROL_SEQ,
                    UUID.randomUUID().toString(),
                    0,
                    MessageSnapshot.capture(Message.assistant(command.displayText())),
                    "SYSTEM_EVENT",
                    interactionKind,
                    command.controlId(),
                    null,
                    metadata,
                    command.intent().traceId());
            return new InteractiveIntentAck(
                    intent, control, command.plan().selectedControl());
        }
    }

    private static final class RecordingScheduler implements ToolExecutionScheduler {
        private final List<String> timeline;
        private final AtomicInteger submissions = new AtomicInteger();

        private RecordingScheduler(List<String> timeline) {
            this.timeline = timeline;
        }

        @Override
        public <T> CompletableFuture<T> submit(Supplier<T> task) {
            submissions.incrementAndGet();
            timeline.add("scheduler:submit");
            return CompletableFuture.failedFuture(
                    new AssertionError("interactive sibling must not be scheduled"));
        }
    }

    private static final class CountingProvider implements LlmProvider {
        private final LlmResponse response;
        private final AtomicInteger calls = new AtomicInteger();

        private CountingProvider(LlmResponse response) {
            this.response = response;
        }

        @Override
        public String getName() {
            return "fake";
        }

        @Override
        public LlmResponse chat(LlmRequest request) {
            calls.incrementAndGet();
            return response;
        }

        @Override
        public void chatStream(LlmRequest request, LlmStreamHandler handler) {
            calls.incrementAndGet();
            handler.onComplete(response);
        }
    }

    private static final class CountingTool implements Tool {
        private final String name;
        private final AtomicInteger executions = new AtomicInteger();

        private CountingTool(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "Must remain behind the durable interactive preflight";
        }

        @Override
        public ToolSchema getToolSchema() {
            return new ToolSchema(name, getDescription(), Map.of("type", "object"));
        }

        @Override
        public SkillResult execute(Map<String, Object> input, SkillContext context) {
            executions.incrementAndGet();
            return SkillResult.success("unexpected execution");
        }

        @Override
        public boolean isReadOnly() {
            return !"Bash".equals(name);
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
            return "unexpected answer";
        }

        @Override
        public boolean hasPendingForSession(String sessionId) {
            pendingChecks.incrementAndGet();
            return false;
        }

        private int totalCalls() {
            return registerCalls.get() + awaitCalls.get() + pendingChecks.get();
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
                    UUID.randomUUID().toString(),
                    request.sessionId(),
                    request.installTool(),
                    request.installTarget(),
                    request.command(),
                    "Unexpected prompt",
                    "Interactive preflight must not prompt",
                    List.of(),
                    Instant.parse("2026-09-03T00:00:00Z"));
        }

        private int totalCalls() {
            return blockingCalls.get() + nonBlockingCalls.get();
        }
    }

    private static final class RecordingBroadcaster implements ChatEventBroadcaster {
        private final List<String> timeline;
        private final List<Message> appended = new ArrayList<>();
        private final AtomicInteger toolStarted = new AtomicInteger();
        private final AtomicInteger toolFinished = new AtomicInteger();

        private RecordingBroadcaster(List<String> timeline) {
            this.timeline = timeline;
        }

        private int eventsBeforeAck() {
            return timeline.stream().mapToInt(event -> event.startsWith("broadcast:") ? 1 : 0).sum();
        }

        @Override
        public void sessionStatus(String sessionId, String status, String step, String error) {
            timeline.add("broadcast:status");
        }

        @Override
        public void messageAppended(String sessionId, String traceId, Message message) {
            appended.add(message);
            timeline.add("broadcast:assistant");
        }

        @Override
        public void askUser(String sessionId, AskUserEvent event) {
            timeline.add("broadcast:ask");
        }

        @Override
        public void confirmationRequired(String sessionId, ConfirmationPromptPayload payload) {
            timeline.add("broadcast:confirmation");
        }

        @Override
        public void toolStarted(
                String sessionId, String toolUseId, String name, Map<String, Object> input) {
            toolStarted.incrementAndGet();
            timeline.add("broadcast:tool-started");
        }

        @Override
        public void toolFinished(
                String sessionId, String toolUseId, String status, long durationMs, String error) {
            toolFinished.incrementAndGet();
            timeline.add("broadcast:tool-finished");
        }

        @Override
        public void assistantDelta(String sessionId, String text) {
            timeline.add("broadcast:assistant-delta");
        }

        @Override
        public void assistantStreamEnd(String sessionId) {
            timeline.add("broadcast:assistant-stream-end");
        }

        @Override
        public void textDelta(String sessionId, String delta) {
            timeline.add("broadcast:text-delta");
        }

        @Override
        public void reasoningDelta(String sessionId, String delta) {
            timeline.add("broadcast:reasoning-delta");
        }

        @Override
        public void toolUseDelta(
                String sessionId, String toolUseId, String toolName, String jsonFragment) {
            timeline.add("broadcast:tool-use-delta");
        }

        @Override
        public void toolUseComplete(
                String sessionId, String toolUseId, Map<String, Object> parsedInput) {
            timeline.add("broadcast:tool-use-complete");
        }
    }
}
