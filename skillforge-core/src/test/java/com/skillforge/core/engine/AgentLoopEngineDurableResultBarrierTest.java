package com.skillforge.core.engine;

import com.skillforge.core.engine.durability.ConversationDurabilitySink;
import com.skillforge.core.engine.durability.ArchivePreparationAck;
import com.skillforge.core.engine.durability.ArchivePreparationCommand;
import com.skillforge.core.engine.durability.ArchivePreparationState;
import com.skillforge.core.engine.durability.DurableFrontier;
import com.skillforge.core.engine.durability.DurableToolAttemptState;
import com.skillforge.core.engine.durability.DurableToolExecutionIncompleteException;
import com.skillforge.core.engine.durability.ExecutionClaimAck;
import com.skillforge.core.engine.durability.ExecutionClaimCommand;
import com.skillforge.core.engine.durability.IntentCommitAck;
import com.skillforge.core.engine.durability.IntentCommitCommand;
import com.skillforge.core.engine.durability.LoopDurabilityScope;
import com.skillforge.core.engine.durability.MessageSnapshot;
import com.skillforge.core.engine.durability.PersistedBlockOccurrence;
import com.skillforge.core.engine.durability.PersistedMessageOccurrence;
import com.skillforge.core.engine.durability.QueuedUserDrainAck;
import com.skillforge.core.engine.durability.QueuedUserDrainItem;
import com.skillforge.core.engine.durability.ToolExecutionScheduler;
import com.skillforge.core.engine.durability.ToolResultCommitAck;
import com.skillforge.core.engine.durability.ToolResultCommitCommand;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class AgentLoopEngineDurableResultBarrierTest {

    private static final LoopDurabilityScope SCOPE = new LoopDurabilityScope(
            "00000000-0000-0000-0000-000000000721",
            72L,
            4L,
            "00000000-0000-0000-0000-000000000722",
            13L,
            "result-barrier-instance");
    private static final String TRACE_ID = "00000000-0000-0000-0000-000000000723";

    @Test
    void parallelCompletion_isCommittedAndExposedInProviderOrder() {
        QueueProvider provider = new QueueProvider(List.of(
                toolResponse(List.of(
                        call("call-0", 60, "zero"),
                        call("call-1", 20, "one"),
                        call("call-2", 1, "two"))),
                textResponse("done")));
        ResultSink sink = new ResultSink(false);
        RecordingBroadcaster broadcaster = new RecordingBroadcaster();
        CountingTool tool = new CountingTool();
        SkillRegistry registry = new SkillRegistry();
        registry.registerTool(tool);
        AgentLoopEngine engine = engine(registry, provider, sink, broadcaster);
        LoopContext context = context();
        List<Message> history = new ArrayList<>();

        LoopResult result = engine.run(
                agent(), "start", history, SCOPE.sessionId(), SCOPE.userId(), context);

        assertThat(result.getStatus()).isEqualTo("completed");
        assertThat(provider.calls).hasValue(2);
        assertThat(tool.executions).hasValue(3);
        assertThat(tool.completionOrder).containsExactly("two", "one", "zero");
        assertThat(sink.calls).containsExactly(
                "intent", "claim", "results", "archive", "visibility");
        assertThat(sink.resultCommand.results())
                .extracting(AgentLoopEngineDurableResultBarrierTest::toolUseId)
                .containsExactly("call-0", "call-1", "call-2");
        assertThat(sink.resultCommand.results())
                .extracting(snapshot -> snapshot.toMessage().getTextContent())
                .containsExactly("zero", "one", "two");
        assertThat(result.getMessages().subList(2, 5))
                .extracting(Message::getTextContent)
                .containsExactly("zero", "one", "two");
        assertThat(context.getExpectedDurableFrontier())
                .isEqualTo(new DurableFrontier(103L, 3L));
        assertThat(broadcaster.toolStarted).hasValue(3);
        assertThat(broadcaster.toolFinished).hasValue(3);
        assertThat(broadcaster.appended).hasSize(4);
        assertThat(broadcaster.appended)
                .noneMatch(message -> "done".equals(message.getTextContent()));
        assertThat(result.getDeferredBroadcastMessages())
                .singleElement()
                .extracting(Message::getTextContent)
                .isEqualTo("done");
    }

    @Test
    void resultCommitFailure_blocksResultVisibilityEventsAndNextProviderCall() {
        QueueProvider provider = new QueueProvider(List.of(
                toolResponse(List.of(call("call-fail", 0, "side-effect-finished"))),
                textResponse("must not be reached")));
        ResultSink sink = new ResultSink(true);
        RecordingBroadcaster broadcaster = new RecordingBroadcaster();
        CountingTool tool = new CountingTool();
        SkillRegistry registry = new SkillRegistry();
        registry.registerTool(tool);
        AgentLoopEngine engine = engine(registry, provider, sink, broadcaster);
        LoopContext context = context();
        List<Message> history = new ArrayList<>();

        Throwable failure = catchThrowable(() -> engine.run(
                agent(), "start", history, SCOPE.sessionId(), SCOPE.userId(), context));

        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("result database unavailable");
        assertThat(provider.calls).hasValue(1);
        assertThat(tool.executions).hasValue(1);
        assertThat(sink.calls).containsExactly("intent", "claim", "results");
        assertThat(context.getMessages()).extracting(Message::getRole)
                .containsExactly(Message.Role.USER, Message.Role.ASSISTANT);
        assertThat(broadcaster.appended).hasSize(1);
        assertThat(broadcaster.appended.get(0).getRole()).isEqualTo(Message.Role.ASSISTANT);
        assertThat(broadcaster.toolStarted).hasValue(0);
        assertThat(broadcaster.toolFinished).hasValue(0);
        assertThat(context.getExpectedDurableFrontier())
                .isEqualTo(new DurableFrontier(100L, 0L));
    }

    @Test
    void rawFallback_continuesWithExactCommittedResultWithoutReexecutingTool() {
        QueueProvider provider = new QueueProvider(List.of(
                toolResponse(List.of(call("call-raw", 0, "exact-raw-evidence"))),
                textResponse("continued")));
        ResultSink sink = new ResultSink(false, true);
        CountingTool tool = new CountingTool();
        SkillRegistry registry = new SkillRegistry();
        registry.registerTool(tool);
        AgentLoopEngine engine = engine(registry, provider, sink, new RecordingBroadcaster());

        LoopResult result = engine.run(
                agent(), "start", new ArrayList<>(), SCOPE.sessionId(), SCOPE.userId(), context());

        assertThat(result.getStatus()).isEqualTo("completed");
        assertThat(provider.calls).hasValue(2);
        assertThat(tool.executions).hasValue(1);
        assertThat(sink.calls).containsExactly(
                "intent", "claim", "results", "archive", "visibility");
        assertThat(provider.requests.get(1).getMessages())
                .filteredOn(message -> message.getRole() == Message.Role.USER)
                .extracting(Message::getTextContent)
                .contains("exact-raw-evidence");
    }

    @Test
    void timedOutSibling_keepsAttemptOpenAndLateCompletionCannotCommitOrBroadcast() throws Exception {
        QueueProvider provider = new QueueProvider(List.of(
                toolResponse(List.of(
                        call("call-fast", 0, "fast"),
                        call("call-late", 200, "late"))),
                textResponse("continued-after-timeout")));
        ResultSink sink = new ResultSink(false);
        RecordingBroadcaster broadcaster = new RecordingBroadcaster();
        CountingTool tool = new CountingTool();
        SkillRegistry registry = new SkillRegistry();
        registry.registerTool(tool);
        AgentLoopEngine engine = engine(registry, provider, sink, broadcaster);
        engine.setToolExecutionTimeoutMillis(20L);

        Throwable failure = catchThrowable(() -> engine.run(
                agent(), "start", new ArrayList<>(), SCOPE.sessionId(), SCOPE.userId(), context()));

        assertThat(failure).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("known terminal state");
        assertThat(tool.executions).hasValue(2);
        assertThat(sink.resultCommand).isNull();
        assertThat(broadcaster.toolStarted).hasValue(0);
        assertThat(broadcaster.toolFinished).hasValue(0);

        Thread.sleep(260L);

        assertThat(tool.completionOrder).containsExactly("fast", "late");
        assertThat(sink.calls).containsExactly("intent", "claim");
        assertThat(broadcaster.toolStarted).hasValue(0);
        assertThat(broadcaster.toolFinished).hasValue(0);
    }

    @Test
    void userCancelDuringRunningToolFailsOpen_withoutResultCommitOrProviderContinuation()
            throws Exception {
        QueueProvider provider = new QueueProvider(List.of(
                toolResponse(List.of(call("call-cancel", 2_000, "late"))),
                textResponse("must-not-continue")));
        ResultSink sink = new ResultSink(false);
        RecordingBroadcaster broadcaster = new RecordingBroadcaster();
        CountingTool tool = new CountingTool();
        SkillRegistry registry = new SkillRegistry();
        registry.registerTool(tool);
        AgentLoopEngine engine = engine(registry, provider, sink, broadcaster);
        engine.setToolExecutionTimeoutMillis(5_000L);
        LoopContext context = context();

        CompletableFuture<Throwable> run = CompletableFuture.supplyAsync(() ->
                catchThrowable(() -> engine.run(
                        agent(), "start", new ArrayList<>(),
                        SCOPE.sessionId(), SCOPE.userId(), context)));
        long waitUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(1L);
        while (tool.executions.get() == 0 && System.nanoTime() < waitUntil) {
            Thread.sleep(5L);
        }
        context.requestCancel();

        // The cancellation path is asynchronous and the full focused suite can put
        // transient load on the common pool. Correctness is asserted below; a wider
        // scheduling allowance keeps this test from confusing pool latency with a
        // missed cancellation fence.
        Throwable failure = run.get(5L, TimeUnit.SECONDS);
        assertThat(failure).isInstanceOf(DurableToolExecutionIncompleteException.class);
        assertThat(((DurableToolExecutionIncompleteException) failure).isUserCancelled())
                .isTrue();
        assertThat(provider.calls).hasValue(1);
        assertThat(sink.calls).containsExactly("intent", "claim");
        assertThat(broadcaster.toolStarted).hasValue(0);
        assertThat(broadcaster.toolFinished).hasValue(0);

        assertThat(sink.calls).containsExactly("intent", "claim");
    }

    @Test
    void staleVisibilityAuthority_blocksResultEventsAndProviderContinuation() {
        QueueProvider provider = new QueueProvider(List.of(
                toolResponse(List.of(call("call-stale", 0, "committed"))),
                textResponse("must-not-continue")));
        ResultSink sink = new ResultSink(false, false, true);
        RecordingBroadcaster broadcaster = new RecordingBroadcaster();
        SkillRegistry registry = new SkillRegistry();
        registry.registerTool(new CountingTool());
        AgentLoopEngine engine = engine(registry, provider, sink, broadcaster);

        Throwable failure = catchThrowable(() -> engine.run(
                agent(), "start", new ArrayList<>(),
                SCOPE.sessionId(), SCOPE.userId(), context()));

        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("stale result visibility fence");
        assertThat(sink.calls).containsExactly(
                "intent", "claim", "results", "archive", "visibility");
        assertThat(provider.calls).hasValue(1);
        assertThat(broadcaster.toolStarted).hasValue(0);
        assertThat(broadcaster.toolFinished).hasValue(0);
        assertThat(broadcaster.appended).hasSize(1);
    }

    @Test
    void orderedInboxDrainsAfterArchiveAndBeforeNextProvider_withoutChangingRawRows() {
        QueueProvider provider = new QueueProvider(List.of(
                toolResponse(List.of(call("call-inbox", 0, "tool-result"))),
                textResponse("done")));
        ResultSink sink = new ResultSink(false);
        sink.queuedAfterResults = List.of("queued-a", "queued-b");
        SkillRegistry registry = new SkillRegistry();
        registry.registerTool(new CountingTool());
        AgentLoopEngine engine = engine(
                registry, provider, sink, new RecordingBroadcaster());
        LoopContext context = context();

        LoopResult result = engine.run(
                agent(), "start", new ArrayList<>(),
                SCOPE.sessionId(), SCOPE.userId(), context);

        assertThat(provider.calls).hasValue(2);
        assertThat(sink.drainFrontiers)
                .containsExactly(
                        DurableFrontier.EMPTY,
                        new DurableFrontier(101L, 1L));
        assertThat(provider.requests.get(1).getMessages())
                .extracting(Message::getTextContent)
                .containsSubsequence(
                        "tool-result",
                        "queued-a\n\n---\n\nqueued-b");
        assertThat(result.getMessages())
                .extracting(Message::getTextContent)
                .containsSubsequence("tool-result", "queued-a", "queued-b", "done");
        assertThat(context.getExpectedDurableFrontier())
                .isEqualTo(new DurableFrontier(103L, 3L));
    }

    private static AgentLoopEngine engine(
            SkillRegistry registry,
            QueueProvider provider,
            ConversationDurabilitySink sink,
            ChatEventBroadcaster broadcaster) {
        LlmProviderFactory factory = new LlmProviderFactory();
        factory.registerProvider("fake", provider);
        AgentLoopEngine engine = new AgentLoopEngine(
                factory, "fake", registry, List.of(), List.of(), List.of());
        engine.setConversationDurabilitySink(sink);
        engine.setToolExecutionScheduler(new AsyncScheduler());
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
        agent.setName("durable-result-barrier");
        agent.setModelId("fake:model");
        agent.setSystemPrompt("test");
        agent.setConfig(Map.of("max_loops", 3));
        return agent;
    }

    private static ToolUseBlock call(String id, int delayMs, String value) {
        return new ToolUseBlock(id, CountingTool.NAME, Map.of("delayMs", delayMs, "value", value));
    }

    private static LlmResponse toolResponse(List<ToolUseBlock> calls) {
        LlmResponse response = new LlmResponse();
        response.setStopReason("tool_use");
        response.setToolUseBlocks(calls);
        return response;
    }

    private static LlmResponse textResponse(String text) {
        LlmResponse response = new LlmResponse();
        response.setStopReason("end_turn");
        response.setContent(text);
        return response;
    }

    @SuppressWarnings("unchecked")
    private static String toolUseId(MessageSnapshot snapshot) {
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) snapshot.toMessage().getContent();
        return (String) blocks.get(0).get("tool_use_id");
    }

    private static final class ResultSink implements ConversationDurabilitySink {
        private final boolean rejectResults;
        private final boolean rawFallback;
        private final boolean rejectVisibility;
        private final List<String> calls = new ArrayList<>();
        private final List<DurableFrontier> drainFrontiers = new ArrayList<>();
        private List<String> queuedAfterResults = List.of();
        private boolean queuedDelivered;
        private ToolResultCommitCommand resultCommand;

        private ResultSink(boolean rejectResults) {
            this(rejectResults, false, false);
        }

        private ResultSink(boolean rejectResults, boolean rawFallback) {
            this(rejectResults, rawFallback, false);
        }

        private ResultSink(
                boolean rejectResults,
                boolean rawFallback,
                boolean rejectVisibility) {
            this.rejectResults = rejectResults;
            this.rawFallback = rawFallback;
            this.rejectVisibility = rejectVisibility;
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public IntentCommitAck commitIntent(IntentCommitCommand command) {
            calls.add("intent");
            return new IntentCommitAck(
                    200L,
                    command.stepId(),
                    new PersistedMessageOccurrence(
                            100L, 0L, command.writeBatchId(), 0, command.assistant(),
                            "NORMAL", "normal", null, null, Map.of(), command.traceId()),
                    command.expectedPreIntentFrontier(),
                    command.manifest(),
                    "a".repeat(64),
                    "b".repeat(64),
                    command.manifest().replaySafety());
        }

        @Override
        public ExecutionClaimAck claimExecution(ExecutionClaimCommand command) {
            calls.add("claim");
            Instant claimedAt = Instant.parse("2026-09-02T10:00:00Z");
            return new ExecutionClaimAck(
                    command.attemptId(), command.stepId(), command.claimRequestId(),
                    DurableToolAttemptState.EXECUTING, command.claimant(), 1L,
                    claimedAt, claimedAt.plusSeconds(60));
        }

        @Override
        public ToolResultCommitAck commitResults(ToolResultCommitCommand command) {
            calls.add("results");
            resultCommand = command;
            if (rejectResults) throw new IllegalStateException("result database unavailable");
            List<PersistedMessageOccurrence> occurrences = new ArrayList<>();
            for (int ordinal = 0; ordinal < command.results().size(); ordinal++) {
                occurrences.add(new PersistedMessageOccurrence(
                        101L + ordinal,
                        1L + ordinal,
                        command.resultBatchId().toString(),
                        ordinal,
                        command.results().get(ordinal),
                        "NORMAL",
                        "normal",
                        null,
                        null,
                        Map.of(),
                        command.traceId()));
            }
            PersistedMessageOccurrence last = occurrences.get(occurrences.size() - 1);
            List<PersistedBlockOccurrence> blocks = new ArrayList<>();
            for (int ordinal = 0; ordinal < occurrences.size(); ordinal++) {
                blocks.add(blockOccurrence(command, occurrences.get(ordinal), ordinal));
            }
            return new ToolResultCommitAck(
                    command.attemptId(), command.stepId(), command.resultBatchId(),
                    command.executionScope(), command.executionGeneration(), occurrences, blocks,
                    command.expectedPreResultFrontier(),
                    new DurableFrontier(last.messageId(), last.seqNo()),
                    command.assistantPayloadHash(), command.manifestHash());
        }

        @Override
        public ArchivePreparationAck prepareResultArchive(ArchivePreparationCommand command) {
            calls.add("archive");
            return new ArchivePreparationAck(
                    command.attemptId(),
                    command.stepId(),
                    command.resultBatchId(),
                    command.executionScope(),
                    command.executionGeneration(),
                    rawFallback
                            ? ArchivePreparationState.RAW_FALLBACK
                            : ArchivePreparationState.PREPARED,
                    rawFallback ? 0 : command.resultBlocks().size(),
                    command.resultBlocks().size(),
                    command.resultBlocks());
        }

        @Override
        public void withResultVisibilityAuthority(
                ArchivePreparationCommand command,
                Runnable visibilityAction) {
            calls.add("visibility");
            if (rejectVisibility) {
                throw new IllegalStateException("stale result visibility fence");
            }
            visibilityAction.run();
        }

        @Override
        public QueuedUserDrainAck drainQueuedUsers(
                LoopDurabilityScope scope,
                DurableFrontier expectedPreDrainFrontier) {
            drainFrontiers.add(expectedPreDrainFrontier);
            if (resultCommand == null || queuedDelivered || queuedAfterResults.isEmpty()) {
                return new QueuedUserDrainAck(
                        scope, expectedPreDrainFrontier, List.of(),
                        expectedPreDrainFrontier, 0L);
            }
            queuedDelivered = true;
            List<QueuedUserDrainItem> items = new ArrayList<>();
            long seq = expectedPreDrainFrontier.maxSeq() + 1L;
            long messageId = expectedPreDrainFrontier.maxMessageId() + 1L;
            for (String text : queuedAfterResults) {
                UUID inboxId = UUID.randomUUID();
                items.add(new QueuedUserDrainItem(
                        inboxId,
                        new PersistedMessageOccurrence(
                                messageId++, seq++, inboxId.toString(), 0,
                                MessageSnapshot.capture(Message.user(text)),
                                "NORMAL", "normal", null, null, Map.of(), null)));
            }
            QueuedUserDrainItem tail = items.get(items.size() - 1);
            return new QueuedUserDrainAck(
                    scope, expectedPreDrainFrontier, items,
                    new DurableFrontier(
                            tail.message().messageId(), tail.message().seqNo()),
                    0L);
        }

        @SuppressWarnings("unchecked")
        private static PersistedBlockOccurrence blockOccurrence(
                ToolResultCommitCommand command,
                PersistedMessageOccurrence occurrence,
                int ordinal) {
            List<Map<String, Object>> content = (List<Map<String, Object>>)
                    occurrence.message().toMessage().getContent();
            Map<String, Object> block = content.get(0);
            return new PersistedBlockOccurrence(
                    occurrence.messageId(), occurrence.seqNo(), command.executionScope().sessionId(),
                    command.resultBatchId(), ordinal, 0,
                    (String) block.get("tool_use_id"),
                    (String) block.get("content"),
                    Boolean.TRUE.equals(block.get("is_error")),
                    (String) block.get("error_type"),
                    occurrence.traceId());
        }
    }

    private static final class AsyncScheduler implements ToolExecutionScheduler {
        @Override
        public <T> CompletableFuture<T> submit(Supplier<T> task) {
            return CompletableFuture.supplyAsync(task);
        }
    }

    private static final class QueueProvider implements LlmProvider {
        private final Queue<LlmResponse> responses;
        private final AtomicInteger calls = new AtomicInteger();
        private final List<LlmRequest> requests = new ArrayList<>();

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
            requests.add(request);
            return responses.remove();
        }

        @Override
        public void chatStream(LlmRequest request, LlmStreamHandler handler) {
            calls.incrementAndGet();
            requests.add(request);
            handler.onComplete(responses.remove());
        }
    }

    private static final class CountingTool implements Tool {
        private static final String NAME = "ResultBarrierProbe";
        private final AtomicInteger executions = new AtomicInteger();
        private final List<String> completionOrder = Collections.synchronizedList(new ArrayList<>());

        @Override
        public String getName() {
            return NAME;
        }

        @Override
        public String getDescription() {
            return "Returns after a deterministic delay";
        }

        @Override
        public ToolSchema getToolSchema() {
            return new ToolSchema(NAME, getDescription(), Map.of("type", "object"));
        }

        @Override
        public SkillResult execute(Map<String, Object> input, SkillContext context) {
            executions.incrementAndGet();
            try {
                Thread.sleep(((Number) input.get("delayMs")).longValue());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            String value = (String) input.get("value");
            completionOrder.add(value);
            return SkillResult.success(value);
        }
    }

    private static final class RecordingBroadcaster implements ChatEventBroadcaster {
        private final List<Message> appended = new ArrayList<>();
        private final AtomicInteger toolStarted = new AtomicInteger();
        private final AtomicInteger toolFinished = new AtomicInteger();

        @Override
        public void sessionStatus(String sessionId, String status, String step, String error) {
        }

        @Override
        public void messageAppended(String sessionId, String traceId, Message message) {
            appended.add(message);
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
