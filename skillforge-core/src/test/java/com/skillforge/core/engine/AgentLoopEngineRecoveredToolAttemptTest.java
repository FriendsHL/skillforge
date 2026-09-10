package com.skillforge.core.engine;

import com.skillforge.core.engine.durability.ArchivePreparationAck;
import com.skillforge.core.engine.durability.ArchivePreparationCommand;
import com.skillforge.core.engine.durability.ArchivePreparationState;
import com.skillforge.core.engine.durability.ConversationDurabilitySink;
import com.skillforge.core.engine.durability.DurableFrontier;
import com.skillforge.core.engine.durability.DurableToolAttemptState;
import com.skillforge.core.engine.durability.ExecutionClaimAck;
import com.skillforge.core.engine.durability.ExecutionClaimCommand;
import com.skillforge.core.engine.durability.FrozenJson;
import com.skillforge.core.engine.durability.IntentCommitAck;
import com.skillforge.core.engine.durability.IntentCommitCommand;
import com.skillforge.core.engine.durability.LoopDurabilityScope;
import com.skillforge.core.engine.durability.MessageSnapshot;
import com.skillforge.core.engine.durability.PersistedBlockOccurrence;
import com.skillforge.core.engine.durability.PersistedMessageOccurrence;
import com.skillforge.core.engine.durability.RecoveredToolAttempt;
import com.skillforge.core.engine.durability.ReplaySafety;
import com.skillforge.core.engine.durability.ToolCallIntent;
import com.skillforge.core.engine.durability.ToolCallManifest;
import com.skillforge.core.engine.durability.ToolExecutionScheduler;
import com.skillforge.core.engine.durability.ToolResultCommitAck;
import com.skillforge.core.engine.durability.ToolResultCommitCommand;
import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.LlmRequest;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.core.llm.LlmStreamHandler;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class AgentLoopEngineRecoveredToolAttemptTest {

    private static final LoopDurabilityScope SCOPE = new LoopDurabilityScope(
            "00000000-0000-0000-0000-000000000741",
            74L,
            6L,
            "00000000-0000-0000-0000-000000000742",
            17L,
            "recovery-instance");
    private static final UUID STEP_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000743");
    private static final UUID CLAIM_REQUEST_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000744");
    private static final String TRACE_ID = "00000000-0000-0000-0000-000000000745";
    private static final long ATTEMPT_ID = 901L;
    private static final long ASSISTANT_MESSAGE_ID = 20L;
    private static final long ASSISTANT_SEQ = 5L;
    private static final Map<String, Object> TOOL_INPUT = Map.of("key", "persisted-value");

    @Test
    void recoveredSafeAttempt_replaysPersistedIntentBeforeTool_withoutInitialProviderCall_thenContinuesProvider() {
        List<String> events = new ArrayList<>();
        RecordingReadTool tool = new RecordingReadTool(events);
        SkillRegistry registry = new SkillRegistry();
        registry.registerTool(tool);
        RecordingProvider provider = new RecordingProvider(events, textResponse("continued"));
        RecoverySink sink = new RecoverySink(events);
        AgentLoopEngine engine = engine(registry, provider, sink, new DirectScheduler());
        RecoveredToolAttempt recovered = recoveredAttempt("recovered-call", "recovered-call");
        LoopContext context = recoveryContext(recovered);
        Message originalUser = Message.user("original request");

        LoopResult result = engine.run(
                agent(), null, List.of(originalUser), SCOPE.sessionId(), SCOPE.userId(), context);

        assertThat(result.getStatus()).isEqualTo("completed");
        assertThat(events).containsExactly("tool", "results", "archive", "provider");
        assertThat(provider.calls).hasValue(1);
        assertThat(tool.executions).hasValue(1);
        assertThat(sink.intentCommits).hasValue(0);
        assertThat(sink.executionClaims).hasValue(0);
        assertThat(sink.resultCommits).hasValue(1);
        assertThat(sink.archivePreparations).hasValue(1);

        MessageSnapshot persistedAssistant = recovered.intent().assistant().message();
        assertThat(result.getMessages())
                .filteredOn(message -> message.getRole() == Message.Role.ASSISTANT)
                .extracting(MessageSnapshot::capture)
                .contains(persistedAssistant);
        assertThat(provider.requests).singleElement().satisfies(request -> {
            assertThat(request.getMessages())
                    .filteredOn(message -> message.getRole() == Message.Role.ASSISTANT)
                    .extracting(MessageSnapshot::capture)
                    .containsExactly(persistedAssistant);
            assertThat(request.getMessages())
                    .filteredOn(message -> message.getRole() == Message.Role.USER)
                    .extracting(Message::getTextContent)
                    .containsExactly("original request", "persisted-value");
        });
        assertThat(sink.resultCommand.executionScope()).isEqualTo(SCOPE);
        assertThat(sink.resultCommand.attemptId()).isEqualTo(ATTEMPT_ID);
        assertThat(sink.resultCommand.stepId()).isEqualTo(STEP_ID);
        assertThat(sink.resultCommand.claimRequestId()).isEqualTo(CLAIM_REQUEST_ID);
        assertThat(sink.resultCommand.executionGeneration()).isEqualTo(2L);
        assertThat(sink.resultCommand.expectedPreResultFrontier())
                .isEqualTo(new DurableFrontier(ASSISTANT_MESSAGE_ID, ASSISTANT_SEQ));
        assertThat(context.getExpectedDurableFrontier())
                .isEqualTo(new DurableFrontier(21L, 6L));
        assertThat(context.getRecoveredToolAttempt()).isNull();
    }

    @Test
    void recoveredAttemptWithAssistantManifestMismatch_failsClosedBeforeProviderToolOrPersistence() {
        List<String> events = new ArrayList<>();
        RecordingReadTool tool = new RecordingReadTool(events);
        SkillRegistry registry = new SkillRegistry();
        registry.registerTool(tool);
        RecordingProvider provider = new RecordingProvider(events, textResponse("must not run"));
        RecoverySink sink = new RecoverySink(events);
        DirectScheduler scheduler = new DirectScheduler();
        AgentLoopEngine engine = engine(registry, provider, sink, scheduler);
        RecoveredToolAttempt recovered = recoveredAttempt("persisted-call", "different-manifest-call");
        LoopContext context = recoveryContext(recovered);

        Throwable failure = catchThrowable(() -> engine.run(
                agent(), null, List.of(Message.user("original request")),
                SCOPE.sessionId(), SCOPE.userId(), context));

        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Recovered durable Tool attempt is inconsistent");
        assertThat(events).isEmpty();
        assertThat(provider.calls).hasValue(0);
        assertThat(tool.executions).hasValue(0);
        assertThat(scheduler.submissions).hasValue(0);
        assertThat(sink.intentCommits).hasValue(0);
        assertThat(sink.executionClaims).hasValue(0);
        assertThat(sink.resultCommits).hasValue(0);
        assertThat(sink.archivePreparations).hasValue(0);
        assertThat(context.getExpectedDurableFrontier())
                .isEqualTo(new DurableFrontier(ASSISTANT_MESSAGE_ID, ASSISTANT_SEQ));
        assertThat(context.getRecoveredToolAttempt()).isSameAs(recovered);
    }

    private static AgentLoopEngine engine(
            SkillRegistry registry,
            RecordingProvider provider,
            ConversationDurabilitySink sink,
            ToolExecutionScheduler scheduler) {
        LlmProviderFactory factory = new LlmProviderFactory();
        factory.registerProvider("fake", provider);
        AgentLoopEngine engine = new AgentLoopEngine(
                factory, "fake", registry, List.of(), List.of(), List.of());
        engine.setConversationDurabilitySink(sink);
        engine.setToolExecutionScheduler(scheduler);
        return engine;
    }

    private static LoopContext recoveryContext(RecoveredToolAttempt recovered) {
        LoopContext context = new LoopContext();
        context.setDurabilityScope(SCOPE);
        context.setExpectedDurableFrontier(
                new DurableFrontier(ASSISTANT_MESSAGE_ID, ASSISTANT_SEQ));
        context.setRecoveredToolAttempt(recovered);
        context.setTraceId(TRACE_ID);
        return context;
    }

    private static AgentDefinition agent() {
        AgentDefinition agent = new AgentDefinition();
        agent.setName("durable-recovery");
        agent.setModelId("fake:model");
        agent.setSystemPrompt("test");
        agent.setConfig(Map.of("max_loops", 3));
        return agent;
    }

    private static RecoveredToolAttempt recoveredAttempt(
            String persistedToolUseId,
            String manifestToolUseId) {
        Message assistant = new Message();
        assistant.setRole(Message.Role.ASSISTANT);
        assistant.setReasoningContent("persisted reasoning");
        assistant.setContent(List.of(
                ContentBlock.text("Persisted recovery plan"),
                ContentBlock.toolUse(
                        persistedToolUseId, RecordingReadTool.NAME, TOOL_INPUT)));
        ToolCallManifest manifest = new ToolCallManifest(
                List.of(new ToolCallIntent(
                        0,
                        manifestToolUseId,
                        RecordingReadTool.NAME,
                        FrozenJson.capture(TOOL_INPUT),
                        ReplaySafety.READ_ONLY_REPLAYABLE)),
                ReplaySafety.READ_ONLY_REPLAYABLE);
        IntentCommitAck intent = new IntentCommitAck(
                ATTEMPT_ID,
                STEP_ID,
                new PersistedMessageOccurrence(
                        ASSISTANT_MESSAGE_ID,
                        ASSISTANT_SEQ,
                        "recovered-intent-batch",
                        0,
                        MessageSnapshot.capture(assistant),
                        "NORMAL",
                        "normal",
                        null,
                        null,
                        Map.of(),
                        TRACE_ID),
                new DurableFrontier(19L, 4L),
                manifest,
                "a".repeat(64),
                "b".repeat(64),
                ReplaySafety.READ_ONLY_REPLAYABLE);
        Instant claimedAt = Instant.parse("2026-09-02T10:00:00Z");
        ExecutionClaimAck execution = new ExecutionClaimAck(
                ATTEMPT_ID,
                STEP_ID,
                CLAIM_REQUEST_ID,
                DurableToolAttemptState.EXECUTING,
                SCOPE,
                2L,
                claimedAt,
                claimedAt.plusSeconds(60));
        return new RecoveredToolAttempt(intent, execution);
    }

    private static LlmResponse textResponse(String text) {
        LlmResponse response = new LlmResponse();
        response.setStopReason("end_turn");
        response.setContent(text);
        return response;
    }

    private static final class RecordingProvider implements LlmProvider {
        private final List<String> events;
        private final LlmResponse response;
        private final AtomicInteger calls = new AtomicInteger();
        private final List<LlmRequest> requests = new ArrayList<>();

        private RecordingProvider(List<String> events, LlmResponse response) {
            this.events = events;
            this.response = response;
        }

        @Override
        public String getName() {
            return "fake";
        }

        @Override
        public LlmResponse chat(LlmRequest request) {
            calls.incrementAndGet();
            events.add("provider");
            requests.add(request);
            return response;
        }

        @Override
        public void chatStream(LlmRequest request, LlmStreamHandler handler) {
            calls.incrementAndGet();
            events.add("provider");
            requests.add(request);
            handler.onComplete(response);
        }
    }

    private static final class RecordingReadTool implements Tool {
        private static final String NAME = "RecoveredReadProbe";
        private final List<String> events;
        private final AtomicInteger executions = new AtomicInteger();

        private RecordingReadTool(List<String> events) {
            this.events = events;
        }

        @Override
        public String getName() {
            return NAME;
        }

        @Override
        public String getDescription() {
            return "Reads one persisted value during recovery";
        }

        @Override
        public ToolSchema getToolSchema() {
            return new ToolSchema(NAME, getDescription(), Map.of("type", "object"));
        }

        @Override
        public SkillResult execute(Map<String, Object> input, SkillContext context) {
            executions.incrementAndGet();
            events.add("tool");
            return SkillResult.success(String.valueOf(input.get("key")));
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }
    }

    private static final class DirectScheduler implements ToolExecutionScheduler {
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

    private static final class RecoverySink implements ConversationDurabilitySink {
        private final List<String> events;
        private final AtomicInteger intentCommits = new AtomicInteger();
        private final AtomicInteger executionClaims = new AtomicInteger();
        private final AtomicInteger resultCommits = new AtomicInteger();
        private final AtomicInteger archivePreparations = new AtomicInteger();
        private ToolResultCommitCommand resultCommand;

        private RecoverySink(List<String> events) {
            this.events = events;
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public IntentCommitAck commitIntent(IntentCommitCommand command) {
            intentCommits.incrementAndGet();
            throw new AssertionError("Recovered intent must not be committed again");
        }

        @Override
        public ExecutionClaimAck claimExecution(ExecutionClaimCommand command) {
            executionClaims.incrementAndGet();
            throw new AssertionError("Recovered execution must not be claimed again");
        }

        @Override
        public ToolResultCommitAck commitResults(ToolResultCommitCommand command) {
            resultCommits.incrementAndGet();
            events.add("results");
            resultCommand = command;
            MessageSnapshot result = command.results().get(0);
            PersistedMessageOccurrence occurrence = new PersistedMessageOccurrence(
                    21L,
                    6L,
                    command.resultBatchId().toString(),
                    0,
                    result,
                    "NORMAL",
                    "normal",
                    null,
                    null,
                    Map.of(),
                    command.traceId());
            PersistedBlockOccurrence block = blockOccurrence(command, result);
            return new ToolResultCommitAck(
                    command.attemptId(),
                    command.stepId(),
                    command.resultBatchId(),
                    command.executionScope(),
                    command.executionGeneration(),
                    List.of(occurrence),
                    List.of(block),
                    command.expectedPreResultFrontier(),
                    new DurableFrontier(21L, 6L),
                    command.assistantPayloadHash(),
                    command.manifestHash());
        }

        @Override
        public ArchivePreparationAck prepareResultArchive(ArchivePreparationCommand command) {
            archivePreparations.incrementAndGet();
            events.add("archive");
            return new ArchivePreparationAck(
                    command.attemptId(),
                    command.stepId(),
                    command.resultBatchId(),
                    command.executionScope(),
                    command.executionGeneration(),
                    ArchivePreparationState.PREPARED,
                    command.resultBlocks().size(),
                    command.resultBlocks().size(),
                    command.resultBlocks());
        }

        @Override
        public void withResultVisibilityAuthority(
                ArchivePreparationCommand command,
                Runnable visibilityAction) {
            visibilityAction.run();
        }

        @SuppressWarnings("unchecked")
        private static PersistedBlockOccurrence blockOccurrence(
                ToolResultCommitCommand command,
                MessageSnapshot result) {
            List<Map<String, Object>> blocks =
                    (List<Map<String, Object>>) result.toMessage().getContent();
            Map<String, Object> block = blocks.get(0);
            return new PersistedBlockOccurrence(
                    21L,
                    6L,
                    command.executionScope().sessionId(),
                    command.resultBatchId(),
                    0,
                    0,
                    (String) block.get("tool_use_id"),
                    (String) block.get("content"),
                    Boolean.TRUE.equals(block.get("is_error")),
                    (String) block.get("error_type"),
                    command.traceId());
        }
    }
}
