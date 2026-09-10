package com.skillforge.core.engine;

import com.skillforge.core.engine.durability.ConversationDurabilitySink;
import com.skillforge.core.engine.durability.ArchivePreparationAck;
import com.skillforge.core.engine.durability.ArchivePreparationCommand;
import com.skillforge.core.engine.durability.ArchivePreparationState;
import com.skillforge.core.engine.durability.DurableFrontier;
import com.skillforge.core.engine.durability.DurableToolAttemptState;
import com.skillforge.core.engine.durability.ExecutionClaimAck;
import com.skillforge.core.engine.durability.ExecutionClaimCommand;
import com.skillforge.core.engine.durability.FrozenJson;
import com.skillforge.core.engine.durability.IntentCommitAck;
import com.skillforge.core.engine.durability.IntentCommitCommand;
import com.skillforge.core.engine.durability.InteractiveIntentAck;
import com.skillforge.core.engine.durability.InteractiveIntentCommand;
import com.skillforge.core.engine.durability.LoopDurabilityScope;
import com.skillforge.core.engine.durability.MessageSnapshot;
import com.skillforge.core.engine.durability.PersistedBlockOccurrence;
import com.skillforge.core.engine.durability.PersistedMessageOccurrence;
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
import com.skillforge.core.model.ToolUseBlock;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class AgentLoopEngineDurableIntentAckTest {

    private static final String SESSION_ID = "00000000-0000-0000-0000-000000000401";
    private static final long USER_ID = 29L;
    private static final String LOOP_ID = "00000000-0000-0000-0000-000000000402";
    private static final String TRACE_ID = "00000000-0000-0000-0000-000000000403";
    private static final DurableFrontier EXPECTED_FRONTIER = new DurableFrontier(101L, 7L);

    @Test
    void multiToolResponse_freezesExactTextReasoningFullManifestAndExpectedFrontier() {
        Map<String, Object> readInput = nestedInput("读取", true);
        Map<String, Object> writeInput = nestedInput("写入", false);
        Map<String, Object> unknownInput = nestedInput("未知", true);
        Map<String, Object> expectedReadInput = nestedInput("读取", true);
        Map<String, Object> expectedWriteInput = nestedInput("写入", false);
        Map<String, Object> expectedUnknownInput = nestedInput("未知", true);

        LlmResponse response = toolResponse(
                "先读取，再写入，最后调用未知工具。",
                "精确 reasoning 🧠\n第二行",
                List.of(
                        new ToolUseBlock("call-read", "ReadProbe", readInput),
                        new ToolUseBlock("call-write", "WriteProbe", writeInput),
                        new ToolUseBlock("call-unknown", "UnregisteredProbe", unknownInput)));
        CapturingFailingSink sink = new CapturingFailingSink();
        RecordingScheduler scheduler = new RecordingScheduler();
        RecordingBroadcaster broadcaster = new RecordingBroadcaster();
        SkillRegistry registry = new SkillRegistry();
        registry.registerTool(new ProbeTool("ReadProbe", true));
        registry.registerTool(new ProbeTool("WriteProbe", false));
        CountingQueueProvider provider = new CountingQueueProvider(List.of(response));
        AgentLoopEngine engine = engine(registry, provider, sink, scheduler, broadcaster);
        LoopContext context = durableContext(EXPECTED_FRONTIER);

        Throwable failure = catchThrowable(() -> engine.run(
                agent(), "start", new ArrayList<>(), SESSION_ID, USER_ID, context));

        mutateNestedInput(readInput);
        mutateNestedInput(writeInput);
        mutateNestedInput(unknownInput);

        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("intent database unavailable");
        assertThat(provider.calls).hasValue(1);
        assertThat(sink.commitCalls).hasValue(1);
        assertThat(context.getExpectedDurableFrontier()).isEqualTo(EXPECTED_FRONTIER);
        assertThat(sink.command.expectedPreIntentFrontier()).isEqualTo(EXPECTED_FRONTIER);
        assertThat(sink.command.assistant()).isEqualTo(MessageSnapshot.capture(
                assistantMessage(
                        "先读取，再写入，最后调用未知工具。",
                        "精确 reasoning 🧠\n第二行",
                        List.of(
                                new ToolUseBlock("call-read", "ReadProbe", expectedReadInput),
                                new ToolUseBlock("call-write", "WriteProbe", expectedWriteInput),
                                new ToolUseBlock("call-unknown", "UnregisteredProbe", expectedUnknownInput)))));
        assertThat(sink.command.manifest()).isEqualTo(new ToolCallManifest(
                List.of(
                        new ToolCallIntent(0, "call-read", "ReadProbe",
                                FrozenJson.capture(expectedReadInput), ReplaySafety.READ_ONLY_REPLAYABLE),
                        new ToolCallIntent(1, "call-write", "WriteProbe",
                                FrozenJson.capture(expectedWriteInput), ReplaySafety.MUTATING),
                        new ToolCallIntent(2, "call-unknown", "UnregisteredProbe",
                                FrozenJson.capture(expectedUnknownInput), ReplaySafety.UNKNOWN)),
                ReplaySafety.UNKNOWN));
        assertThat(scheduler.submissions).hasValue(0);
        assertThat(broadcaster.appended).isEmpty();
        assertThat(context.getMessages())
                .extracting(Message::getRole)
                .containsExactly(Message.Role.USER);
    }

    @Test
    void successfulAck_replacesMutableCandidateBeforeItsSingleHistoryAddAndBroadcast() {
        Map<String, Object> askInput = askInput();
        Map<String, Object> expectedAskInput = askInput();
        CountingQueueProvider provider = new CountingQueueProvider(List.of(toolResponse(
                "请选择部署环境。",
                "reasoning-before-ask",
                List.of(new ToolUseBlock("ask-1", "ask_user", askInput)))));
        MutatingAckSink sink = new MutatingAckSink(() -> mutateAskInput(askInput));
        RecordingScheduler scheduler = new RecordingScheduler();
        RecordingBroadcaster broadcaster = new RecordingBroadcaster();
        AgentLoopEngine engine = engine(
                new SkillRegistry(), provider, sink, scheduler, broadcaster);
        engine.setPendingAskRegistry(new PendingAskRegistry());
        LoopContext context = durableContext(EXPECTED_FRONTIER);

        LoopResult result = engine.run(
                agent(), "start", new ArrayList<>(), SESSION_ID, USER_ID, context);

        MessageSnapshot expectedAssistant = MessageSnapshot.capture(assistantMessage(
                "请选择部署环境。",
                "reasoning-before-ask",
                List.of(new ToolUseBlock("ask-1", "ask_user", expectedAskInput))));
        assertThat(result.getStatus()).isEqualTo("waiting_user");
        assertThat(provider.calls).hasValue(1);
        assertThat(sink.commitCalls).hasValue(1);
        assertThat(sink.ack.preIntentFrontier()).isEqualTo(EXPECTED_FRONTIER);
        assertThat(sink.ack.assistant().message()).isEqualTo(expectedAssistant);
        assertThat(result.getMessages()).hasSize(2);
        Message persistedAssistant = result.getMessages().get(1);
        assertThat(MessageSnapshot.capture(persistedAssistant)).isEqualTo(expectedAssistant);
        assertThat(result.getMessages())
                .filteredOn(message -> message.getRole() == Message.Role.ASSISTANT)
                .containsExactly(persistedAssistant);
        assertThat(broadcaster.appended).containsExactly(persistedAssistant);
        assertThat(broadcaster.appended.get(0)).isSameAs(persistedAssistant);
        assertThat(scheduler.submissions).hasValue(0);
        assertThat(broadcaster.toolStarted).hasValue(0);
        assertThat(broadcaster.toolFinished).hasValue(0);
        assertThat(result.getPendingControl().getQuestion()).isEqualTo("Which environment?");
        assertThat(result.getPendingControl().getContext()).isEqualTo("Deployment target");
        assertThat(result.getPendingControl().isAllowOther()).isFalse();
        assertThat(result.getPendingControl().getOptions()).containsExactly(
                Map.of("label", "staging", "description", "Use staging"),
                Map.of("label", "production"));
        assertThat(MessageSnapshot.capture(
                result.getPendingControl().getAssistantToolUseMessage()))
                .isEqualTo(expectedAssistant);
        assertThat(context.getExpectedDurableFrontier())
                .isEqualTo(new DurableFrontier(402L, 9L));
    }

    @Test
    void successfulAck_executesAndReportsOnlyAckFrozenToolInput() {
        Map<String, Object> providerInput = nestedInput("original", true);
        Map<String, Object> expectedInput = nestedInput("original", true);
        CountingQueueProvider provider = new CountingQueueProvider(List.of(
                toolResponse(
                        "Run exact input.",
                        "reasoning-before-tool",
                        List.of(new ToolUseBlock(
                                "call-exact", RecordingExecutingTool.NAME, providerInput))),
                textResponse("done")));
        MutatingAckSink sink = new MutatingAckSink(() -> mutateNestedInput(providerInput));
        ImmediateScheduler scheduler = new ImmediateScheduler();
        RecordingBroadcaster broadcaster = new RecordingBroadcaster();
        RecordingExecutingTool tool = new RecordingExecutingTool();
        SkillRegistry registry = new SkillRegistry();
        registry.registerTool(tool);
        AgentLoopEngine engine = engine(registry, provider, sink, scheduler, broadcaster);
        LoopContext context = durableContext(EXPECTED_FRONTIER);

        LoopResult result = engine.run(
                agent(), "start", new ArrayList<>(), SESSION_ID, USER_ID, context);

        assertThat(result.getStatus()).isEqualTo("completed");
        assertThat(provider.calls).hasValue(2);
        assertThat(sink.commitCalls).hasValue(1);
        assertThat(scheduler.submissions).hasValue(1);
        assertThat(tool.executions).hasValue(1);
        assertThat(tool.inputs).containsExactly(FrozenJson.capture(expectedInput));
        assertThat(broadcaster.startedInputs)
                .containsExactly(FrozenJson.capture(expectedInput));
        assertThat(result.getToolCalls()).hasSize(1);
        assertThat(FrozenJson.capture(result.getToolCalls().get(0).getInput()))
                .isEqualTo(FrozenJson.capture(expectedInput));
        assertThat(context.getExpectedDurableFrontier())
                .isEqualTo(new DurableFrontier(402L, 9L));
    }

    @Test
    void maliciousToolStartedBroadcasterCannotChangeAckExecutionOrAssistantHistory() {
        Map<String, Object> providerInput = nestedInput("broadcast-original", true);
        Map<String, Object> expectedInput = nestedInput("broadcast-original", true);
        CountingQueueProvider provider = new CountingQueueProvider(List.of(
                toolResponse(
                        "Run broadcast-safe input.",
                        "broadcast-safe reasoning",
                        List.of(new ToolUseBlock(
                                "call-broadcast", RecordingExecutingTool.NAME, providerInput))),
                textResponse("done")));
        MutatingAckSink sink = new MutatingAckSink(() -> { });
        ImmediateScheduler scheduler = new ImmediateScheduler();
        RecordingBroadcaster broadcaster = new MutatingToolStartedBroadcaster();
        RecordingExecutingTool tool = new RecordingExecutingTool();
        SkillRegistry registry = new SkillRegistry();
        registry.registerTool(tool);
        AgentLoopEngine engine = engine(registry, provider, sink, scheduler, broadcaster);
        LoopContext context = durableContext(EXPECTED_FRONTIER);

        LoopResult result = engine.run(
                agent(), "start", new ArrayList<>(), SESSION_ID, USER_ID, context);

        assertThat(tool.executions).hasValue(1);
        assertThat(tool.inputs).containsExactly(FrozenJson.capture(expectedInput));
        assertThat(broadcaster.startedInputs)
                .containsExactly(FrozenJson.capture(expectedInput));
        assertThat(result.getToolCalls()).hasSize(1);
        assertThat(FrozenJson.capture(result.getToolCalls().get(0).getInput()))
                .isEqualTo(FrozenJson.capture(expectedInput));
        assertPersistedAssistantUnchanged(result, sink.ack.assistant().message());
    }

    @Test
    void maliciousToolCannotChangeAssistantHistoryOrRecordedAckInput() {
        Map<String, Object> providerInput = nestedInput("tool-original", true);
        Map<String, Object> expectedInput = nestedInput("tool-original", true);
        CountingQueueProvider provider = new CountingQueueProvider(List.of(
                toolResponse(
                        "Run tool-safe input.",
                        "tool-safe reasoning",
                        List.of(new ToolUseBlock(
                                "call-tool", MutatingExecutingTool.NAME, providerInput))),
                textResponse("done")));
        MutatingAckSink sink = new MutatingAckSink(() -> { });
        ImmediateScheduler scheduler = new ImmediateScheduler();
        RecordingBroadcaster broadcaster = new RecordingBroadcaster();
        MutatingExecutingTool tool = new MutatingExecutingTool();
        SkillRegistry registry = new SkillRegistry();
        registry.registerTool(tool);
        AgentLoopEngine engine = engine(registry, provider, sink, scheduler, broadcaster);
        LoopContext context = durableContext(EXPECTED_FRONTIER);

        LoopResult result = engine.run(
                agent(), "start", new ArrayList<>(), SESSION_ID, USER_ID, context);

        assertThat(tool.executions).hasValue(1);
        assertThat(tool.inputsBeforeMutation).containsExactly(FrozenJson.capture(expectedInput));
        assertThat(broadcaster.startedInputs)
                .containsExactly(FrozenJson.capture(expectedInput));
        assertThat(result.getToolCalls()).hasSize(1);
        assertThat(FrozenJson.capture(result.getToolCalls().get(0).getInput()))
                .isEqualTo(FrozenJson.capture(expectedInput));
        assertPersistedAssistantUnchanged(result, sink.ack.assistant().message());
    }

    @ParameterizedTest(name = "malformed ACK {0} fails before any durable consumer")
    @EnumSource(MalformedAck.class)
    void malformedAck_failsBeforeHistoryBroadcastOrScheduling(MalformedAck malformedAck) {
        Map<String, Object> input = askInput();
        CountingQueueProvider provider = new CountingQueueProvider(List.of(toolResponse(
                "Need a decision.",
                "reasoning",
                List.of(new ToolUseBlock("ask-malformed", "ask_user", input)))));
        MalformedAckSink sink = new MalformedAckSink(malformedAck);
        RecordingScheduler scheduler = new RecordingScheduler();
        RecordingBroadcaster broadcaster = new RecordingBroadcaster();
        AtomicReference<LoopContext> effectiveContext = new AtomicReference<>();
        LoopHook replacingHook = new LoopHook() {
            @Override
            public LoopContext beforeLoop(LoopContext original) {
                LoopContext replacement = replacementContext(original);
                replacement.setDurabilityScope(original.getDurabilityScope());
                replacement.setExpectedDurableFrontier(original.getExpectedDurableFrontier());
                effectiveContext.set(replacement);
                return replacement;
            }
        };
        AgentLoopEngine engine = engine(
                new SkillRegistry(), provider, sink, scheduler, broadcaster,
                List.of(replacingHook));
        engine.setPendingAskRegistry(new PendingAskRegistry());
        LoopContext context = durableContext(EXPECTED_FRONTIER);

        Throwable failure = catchThrowable(() -> engine.run(
                agent(), "start", new ArrayList<>(), SESSION_ID, USER_ID, context));

        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Durable intent acknowledgement")
                .hasNoCause();
        assertThat(provider.calls).hasValue(1);
        assertThat(sink.commitCalls).hasValue(1);
        assertThat(context.getMessages())
                .extracting(Message::getRole)
                .containsExactly(Message.Role.USER);
        assertThat(broadcaster.appended).isEmpty();
        assertThat(broadcaster.toolStarted).hasValue(0);
        assertThat(broadcaster.toolFinished).hasValue(0);
        assertThat(scheduler.submissions).hasValue(0);
        assertThat(context.getExpectedDurableFrontier()).isEqualTo(EXPECTED_FRONTIER);
        assertThat(effectiveContext.get()).isNotNull();
        assertThat(effectiveContext.get().getExpectedDurableFrontier())
                .isEqualTo(EXPECTED_FRONTIER);
    }

    @Test
    void enabledDurabilityWithoutExpectedFrontier_failsClosedBeforeProviderOrConsumers() {
        CapturingFailingSink sink = new CapturingFailingSink();
        RecordingScheduler scheduler = new RecordingScheduler();
        RecordingBroadcaster broadcaster = new RecordingBroadcaster();
        CountingQueueProvider provider = new CountingQueueProvider(List.of(toolResponse(
                null,
                null,
                List.of(new ToolUseBlock("call-1", "UnregisteredProbe", Map.of())))));
        AgentLoopEngine engine = engine(
                new SkillRegistry(), provider, sink, scheduler, broadcaster);
        LoopContext context = durableContext(null);

        Throwable failure = catchThrowable(() -> engine.run(
                agent(), "start", new ArrayList<>(), SESSION_ID, USER_ID, context));

        assertThat(failure).isInstanceOf(IllegalStateException.class);
        assertThat(provider.calls).hasValue(0);
        assertThat(sink.commitCalls).hasValue(0);
        assertThat(sink.command).isNull();
        assertThat(context.getMessages())
                .extracting(Message::getRole)
                .containsExactly(Message.Role.USER);
        assertThat(broadcaster.appended).isEmpty();
        assertThat(broadcaster.toolStarted).hasValue(0);
        assertThat(broadcaster.toolFinished).hasValue(0);
        assertThat(scheduler.submissions).hasValue(0);
        assertThat(context.getExpectedDurableFrontier()).isNull();
    }

    @Test
    void replacementHookMissingAuthority_inheritsItOrFailsBeforeProvider() {
        AtomicReference<LoopContext> effectiveContext = new AtomicReference<>();
        LoopHook replacingHook = new LoopHook() {
            @Override
            public LoopContext beforeLoop(LoopContext original) {
                LoopContext replacement = replacementContext(original);
                effectiveContext.set(replacement);
                return replacement;
            }
        };
        MutatingAckSink sink = new MutatingAckSink(() -> { });
        RecordingScheduler scheduler = new RecordingScheduler();
        RecordingBroadcaster broadcaster = new RecordingBroadcaster();
        CountingQueueProvider provider = new CountingQueueProvider(List.of(toolResponse(
                "Need a decision.",
                "reasoning",
                List.of(new ToolUseBlock("ask-hook", "ask_user", askInput())))));
        AgentLoopEngine engine = engine(
                new SkillRegistry(), provider, sink, scheduler, broadcaster, List.of(replacingHook));
        engine.setPendingAskRegistry(new PendingAskRegistry());
        LoopContext external = durableContext(EXPECTED_FRONTIER);

        Throwable failure = catchThrowable(() -> engine.run(
                agent(), "start", new ArrayList<>(), SESSION_ID, USER_ID, external));

        if (failure == null) {
            assertThat(sink.commitCalls).hasValue(1);
            assertThat(sink.ack.preIntentFrontier()).isEqualTo(EXPECTED_FRONTIER);
            assertThat(sink.ack.stepId()).isEqualTo(sink.command.stepId());
            assertThat(sink.command.origin()).isEqualTo(external.getDurabilityScope());
            assertThat(sink.command.expectedPreIntentFrontier()).isEqualTo(EXPECTED_FRONTIER);
        } else {
            assertThat(failure).isInstanceOf(IllegalStateException.class);
            assertThat(provider.calls).hasValue(0);
            assertThat(sink.commitCalls).hasValue(0);
        }
        assertThat(scheduler.submissions).hasValue(0);
        assertThat(effectiveContext.get()).isNotNull();
    }

    @Test
    void replacementHookCannotForgeDurabilityAuthority() {
        LoopHook forgingHook = new LoopHook() {
            @Override
            public LoopContext beforeLoop(LoopContext original) {
                LoopContext replacement = replacementContext(original);
                replacement.setDurabilityScope(new LoopDurabilityScope(
                        SESSION_ID,
                        USER_ID,
                        999L,
                        "00000000-0000-0000-0000-000000000498",
                        1001L,
                        "forged-instance"));
                replacement.setExpectedDurableFrontier(new DurableFrontier(998L, 997L));
                return replacement;
            }
        };
        MutatingAckSink sink = new MutatingAckSink(() -> { });
        RecordingScheduler scheduler = new RecordingScheduler();
        RecordingBroadcaster broadcaster = new RecordingBroadcaster();
        CountingQueueProvider provider = new CountingQueueProvider(List.of(toolResponse(
                "Need a decision.",
                "reasoning",
                List.of(new ToolUseBlock("ask-forged", "ask_user", askInput())))));
        AgentLoopEngine engine = engine(
                new SkillRegistry(), provider, sink, scheduler, broadcaster, List.of(forgingHook));
        engine.setPendingAskRegistry(new PendingAskRegistry());
        LoopContext external = durableContext(EXPECTED_FRONTIER);

        Throwable failure = catchThrowable(() -> engine.run(
                agent(), "start", new ArrayList<>(), SESSION_ID, USER_ID, external));

        assertThat(failure).isInstanceOf(IllegalStateException.class);
        assertThat(provider.calls).hasValue(0);
        assertThat(sink.commitCalls).hasValue(0);
        assertThat(scheduler.submissions).hasValue(0);
        assertThat(external.getExpectedDurableFrontier()).isEqualTo(EXPECTED_FRONTIER);
    }

    @Test
    void replacementHookSuccessfulAckAdvancesExternalAndEffectiveFrontier() {
        AtomicReference<LoopContext> effectiveContext = new AtomicReference<>();
        LoopHook replacingHook = new LoopHook() {
            @Override
            public LoopContext beforeLoop(LoopContext original) {
                LoopContext replacement = replacementContext(original);
                replacement.setDurabilityScope(original.getDurabilityScope());
                replacement.setExpectedDurableFrontier(original.getExpectedDurableFrontier());
                effectiveContext.set(replacement);
                return replacement;
            }
        };
        MutatingAckSink sink = new MutatingAckSink(() -> { });
        RecordingScheduler scheduler = new RecordingScheduler();
        RecordingBroadcaster broadcaster = new RecordingBroadcaster();
        CountingQueueProvider provider = new CountingQueueProvider(List.of(toolResponse(
                "Need a decision.",
                "reasoning",
                List.of(new ToolUseBlock("ask-replacement", "ask_user", askInput())))));
        AgentLoopEngine engine = engine(
                new SkillRegistry(), provider, sink, scheduler, broadcaster, List.of(replacingHook));
        engine.setPendingAskRegistry(new PendingAskRegistry());
        LoopContext external = durableContext(EXPECTED_FRONTIER);

        LoopResult result = engine.run(
                agent(), "start", new ArrayList<>(), SESSION_ID, USER_ID, external);

        DurableFrontier committed = new DurableFrontier(402L, 9L);
        assertThat(result.getStatus()).isEqualTo("waiting_user");
        assertThat(provider.calls).hasValue(1);
        assertThat(sink.commitCalls).hasValue(1);
        assertThat(external.getExpectedDurableFrontier()).isEqualTo(committed);
        assertThat(effectiveContext.get().getExpectedDurableFrontier()).isEqualTo(committed);
    }

    private static AgentLoopEngine engine(
            SkillRegistry registry,
            LlmProvider provider,
            ConversationDurabilitySink sink,
            ToolExecutionScheduler scheduler,
            ChatEventBroadcaster broadcaster) {
        return engine(registry, provider, sink, scheduler, broadcaster, List.of());
    }

    private static AgentLoopEngine engine(
            SkillRegistry registry,
            LlmProvider provider,
            ConversationDurabilitySink sink,
            ToolExecutionScheduler scheduler,
            ChatEventBroadcaster broadcaster,
            List<LoopHook> loopHooks) {
        LlmProviderFactory factory = new LlmProviderFactory();
        factory.registerProvider("fake", provider);
        AgentLoopEngine engine = new AgentLoopEngine(
                factory, "fake", registry, loopHooks, List.of(), List.of());
        engine.setConversationDurabilitySink(sink);
        engine.setToolExecutionScheduler(scheduler);
        engine.setBroadcaster(broadcaster);
        return engine;
    }

    private static LoopContext durableContext(DurableFrontier expectedFrontier) {
        LoopContext context = new LoopContext();
        context.setTraceId(TRACE_ID);
        context.setDurabilityScope(new LoopDurabilityScope(
                SESSION_ID,
                USER_ID,
                31L,
                LOOP_ID,
                37L,
                "instance-red-2"));
        context.setExpectedDurableFrontier(expectedFrontier);
        return context;
    }

    private static LoopContext replacementContext(LoopContext original) {
        LoopContext replacement = new LoopContext();
        replacement.setAgentDefinition(original.getAgentDefinition());
        replacement.setSessionId(original.getSessionId());
        replacement.setUserId(original.getUserId());
        replacement.setMessages(original.getMessages());
        replacement.setTraceId(original.getTraceId());
        return replacement;
    }

    private static AgentDefinition agent() {
        AgentDefinition agent = new AgentDefinition();
        agent.setName("durable-intent-ack-red");
        agent.setModelId("fake:model");
        agent.setSystemPrompt("test");
        agent.setConfig(Map.of("max_loops", 3, "execution_mode", "ask"));
        return agent;
    }

    private static LlmResponse toolResponse(
            String text, String reasoning, List<ToolUseBlock> calls) {
        LlmResponse response = new LlmResponse();
        response.setContent(text);
        response.setReasoningContent(reasoning);
        response.setStopReason("tool_use");
        response.setToolUseBlocks(calls);
        return response;
    }

    private static LlmResponse textResponse(String text) {
        LlmResponse response = new LlmResponse();
        response.setContent(text);
        response.setStopReason("end_turn");
        return response;
    }

    private static Message assistantMessage(
            String text, String reasoning, List<ToolUseBlock> calls) {
        Message assistant = new Message();
        assistant.setRole(Message.Role.ASSISTANT);
        assistant.setReasoningContent(reasoning);
        List<ContentBlock> blocks = new ArrayList<>();
        if (text != null && !text.isEmpty()) {
            blocks.add(ContentBlock.text(text));
        }
        for (ToolUseBlock call : calls) {
            blocks.add(ContentBlock.toolUse(call.getId(), call.getName(), call.getInput()));
        }
        assistant.setContent(blocks);
        return assistant;
    }

    private static Map<String, Object> nestedInput(String label, boolean includeNull) {
        LinkedHashMap<String, Object> limits = new LinkedHashMap<>();
        limits.put("max", 3);
        limits.put("enabled", true);
        ArrayList<Object> selectors = new ArrayList<>();
        selectors.add("alpha");
        selectors.add(Map.of("标签", label));
        if (includeNull) selectors.add(null);
        LinkedHashMap<String, Object> input = new LinkedHashMap<>();
        input.put("query", label + "-你好");
        input.put("limits", limits);
        input.put("selectors", selectors);
        return input;
    }

    @SuppressWarnings("unchecked")
    private static void mutateNestedInput(Map<String, Object> input) {
        input.put("query", "mutated");
        ((Map<String, Object>) input.get("limits")).put("max", 999);
        ((List<Object>) input.get("selectors")).add("late");
    }

    private static Map<String, Object> askInput() {
        LinkedHashMap<String, Object> first = new LinkedHashMap<>();
        first.put("label", "staging");
        first.put("description", "Use staging");
        ArrayList<Object> options = new ArrayList<>();
        options.add(first);
        options.add("production");
        LinkedHashMap<String, Object> input = new LinkedHashMap<>();
        input.put("question", "Which environment?");
        input.put("context", "Deployment target");
        input.put("options", options);
        input.put("allowOther", false);
        return input;
    }

    @SuppressWarnings("unchecked")
    private static void mutateAskInput(Map<String, Object> input) {
        input.put("question", "mutated question");
        List<Object> options = (List<Object>) input.get("options");
        ((Map<String, Object>) options.get(0)).put("label", "mutated staging");
        options.add("late option");
    }

    private static void assertPersistedAssistantUnchanged(
            LoopResult result, MessageSnapshot expectedAssistant) {
        assertThat(result.getMessages().stream()
                .filter(message -> message.getRole() == Message.Role.ASSISTANT)
                .map(MessageSnapshot::capture))
                .contains(expectedAssistant);
    }

    private static IntentCommitAck validAck(IntentCommitCommand command) {
        PersistedMessageOccurrence occurrence = new PersistedMessageOccurrence(
                401L,
                8L,
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
                501L,
                command.stepId(),
                occurrence,
                command.expectedPreIntentFrontier(),
                command.manifest(),
                "a".repeat(64),
                "b".repeat(64),
                command.manifest().replaySafety());
    }

    private static InteractiveIntentAck validInteractiveAck(
            InteractiveIntentCommand command, IntentCommitAck intent) {
        String interactionKind = command.plan().selectedControl().kind()
                == com.skillforge.core.engine.durability.InteractiveStepPlanner.CallKind.ASK_USER
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
                intent.assistant().messageId() + 1L,
                intent.assistant().seqNo() + 1L,
                "00000000-0000-4000-8000-000000000497",
                0,
                MessageSnapshot.capture(Message.assistant(command.displayText())),
                "SYSTEM_EVENT",
                interactionKind,
                command.controlId(),
                null,
                metadata,
                command.intent().traceId());
        return new InteractiveIntentAck(intent, control, command.plan().selectedControl());
    }

    private static final class CapturingFailingSink implements ConversationDurabilitySink {
        private final AtomicInteger commitCalls = new AtomicInteger();
        private IntentCommitCommand command;

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public IntentCommitAck commitIntent(IntentCommitCommand command) {
            this.command = command;
            commitCalls.incrementAndGet();
            throw new IllegalStateException("intent database unavailable");
        }
    }

    private static final class MutatingAckSink implements ConversationDurabilitySink {
        private final Runnable mutateCandidate;
        private final AtomicInteger commitCalls = new AtomicInteger();
        private IntentCommitCommand command;
        private IntentCommitAck ack;

        private MutatingAckSink(Runnable mutateCandidate) {
            this.mutateCandidate = mutateCandidate;
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public IntentCommitAck commitIntent(IntentCommitCommand command) {
            commitCalls.incrementAndGet();
            this.command = command;
            ack = validAck(command);
            mutateCandidate.run();
            return ack;
        }

        @Override
        public InteractiveIntentAck commitInteractiveIntent(InteractiveIntentCommand command) {
            commitCalls.incrementAndGet();
            this.command = command.intent();
            ack = validAck(command.intent());
            mutateCandidate.run();
            return validInteractiveAck(command, ack);
        }

        @Override
        public ExecutionClaimAck claimExecution(ExecutionClaimCommand claim) {
            Instant claimedAt = Instant.parse("2026-09-02T00:00:00Z");
            return new ExecutionClaimAck(
                    claim.attemptId(),
                    claim.stepId(),
                    claim.claimRequestId(),
                    DurableToolAttemptState.EXECUTING,
                    claim.claimant(),
                    claim.expectedGeneration() + 1L,
                    claimedAt,
                    claimedAt.plusSeconds(60));
        }

        @Override
        public ToolResultCommitAck commitResults(ToolResultCommitCommand result) {
            List<PersistedMessageOccurrence> occurrences = new ArrayList<>();
            for (int ordinal = 0; ordinal < result.results().size(); ordinal++) {
                occurrences.add(new PersistedMessageOccurrence(
                        result.expectedPreResultFrontier().maxMessageId() + ordinal + 1L,
                        result.expectedPreResultFrontier().maxSeq() + ordinal + 1L,
                        result.resultBatchId().toString(),
                        ordinal,
                        result.results().get(ordinal),
                        "NORMAL",
                        "normal",
                        null,
                        null,
                        Map.of(),
                        result.traceId()));
            }
            PersistedMessageOccurrence last = occurrences.get(occurrences.size() - 1);
            List<PersistedBlockOccurrence> blocks = new ArrayList<>();
            for (int ordinal = 0; ordinal < occurrences.size(); ordinal++) {
                Message resultMessage = occurrences.get(ordinal).message().toMessage();
                @SuppressWarnings("unchecked")
                Map<String, Object> block = ((List<Map<String, Object>>)
                        resultMessage.getContent()).get(0);
                blocks.add(new PersistedBlockOccurrence(
                        occurrences.get(ordinal).messageId(),
                        occurrences.get(ordinal).seqNo(),
                        result.executionScope().sessionId(),
                        result.resultBatchId(),
                        ordinal,
                        0,
                        (String) block.get("tool_use_id"),
                        (String) block.get("content"),
                        Boolean.TRUE.equals(block.get("is_error")),
                        (String) block.get("error_type"),
                        result.traceId()));
            }
            return new ToolResultCommitAck(
                    result.attemptId(),
                    result.stepId(),
                    result.resultBatchId(),
                    result.executionScope(),
                    result.executionGeneration(),
                    occurrences,
                    blocks,
                    result.expectedPreResultFrontier(),
                    new DurableFrontier(last.messageId(), last.seqNo()),
                    result.assistantPayloadHash(),
                    result.manifestHash());
        }

        @Override
        public ArchivePreparationAck prepareResultArchive(ArchivePreparationCommand command) {
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
    }

    private static final class MalformedAckSink implements ConversationDurabilitySink {
        private final MalformedAck malformedAck;
        private final AtomicInteger commitCalls = new AtomicInteger();

        private MalformedAckSink(MalformedAck malformedAck) {
            this.malformedAck = malformedAck;
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public IntentCommitAck commitIntent(IntentCommitCommand command) {
            commitCalls.incrementAndGet();
            IntentCommitAck valid = validAck(command);
            return switch (malformedAck) {
                case STEP -> newAckWithStep(UUID.randomUUID(), valid);
                case BATCH -> replaceOccurrence(valid, new PersistedMessageOccurrence(
                        valid.assistant().messageId(),
                        valid.assistant().seqNo(),
                        "00000000-0000-4000-8000-000000000499",
                        0,
                        valid.assistant().message(),
                        valid.assistant().msgType(),
                        valid.assistant().messageType(),
                        valid.assistant().controlId(),
                        valid.assistant().answeredAt(),
                        valid.assistant().metadata(),
                        valid.assistant().traceId()));
                case ORDINAL -> replaceOccurrence(valid, new PersistedMessageOccurrence(
                        valid.assistant().messageId(),
                        valid.assistant().seqNo(),
                        valid.assistant().writeBatchId(),
                        1,
                        valid.assistant().message(),
                        valid.assistant().msgType(),
                        valid.assistant().messageType(),
                        valid.assistant().controlId(),
                        valid.assistant().answeredAt(),
                        valid.assistant().metadata(),
                        valid.assistant().traceId()));
                case ASSISTANT -> replaceOccurrence(valid, new PersistedMessageOccurrence(
                        valid.assistant().messageId(),
                        valid.assistant().seqNo(),
                        valid.assistant().writeBatchId(),
                        valid.assistant().writeBatchOrdinal(),
                        MessageSnapshot.capture(Message.assistant("different persisted assistant")),
                        valid.assistant().msgType(),
                        valid.assistant().messageType(),
                        valid.assistant().controlId(),
                        valid.assistant().answeredAt(),
                        valid.assistant().metadata(),
                        valid.assistant().traceId()));
                case MANIFEST -> {
                    ToolCallIntent original = command.manifest().calls().get(0);
                    ToolCallManifest different = new ToolCallManifest(
                            List.of(new ToolCallIntent(
                                    0,
                                    original.toolUseId(),
                                    "different_tool",
                                    original.input(),
                                    ReplaySafety.UNKNOWN)),
                            ReplaySafety.UNKNOWN);
                    yield new IntentCommitAck(
                            valid.attemptId(),
                            valid.stepId(),
                            valid.assistant(),
                            valid.preIntentFrontier(),
                            different,
                            valid.assistantPayloadHash(),
                            valid.manifestHash(),
                            different.replaySafety());
                }
                case FRONTIER -> new IntentCommitAck(
                        valid.attemptId(),
                        valid.stepId(),
                        valid.assistant(),
                        new DurableFrontier(999L, 998L),
                        valid.manifest(),
                        valid.assistantPayloadHash(),
                        valid.manifestHash(),
                        valid.replaySafety());
                case TRACE -> replaceOccurrence(valid, occurrenceWith(
                        valid, valid.assistant().messageId(), valid.assistant().seqNo(),
                        valid.assistant().msgType(), valid.assistant().messageType(),
                        valid.assistant().controlId(), valid.assistant().answeredAt(),
                        valid.assistant().metadata(), "different-trace"));
                case MSG_TYPE -> replaceOccurrence(valid, occurrenceWith(
                        valid, valid.assistant().messageId(), valid.assistant().seqNo(),
                        "COMPACT_SUMMARY", valid.assistant().messageType(),
                        valid.assistant().controlId(), valid.assistant().answeredAt(),
                        valid.assistant().metadata(), valid.assistant().traceId()));
                case MESSAGE_TYPE -> replaceOccurrence(valid, occurrenceWith(
                        valid, valid.assistant().messageId(), valid.assistant().seqNo(),
                        valid.assistant().msgType(), "ask_user",
                        valid.assistant().controlId(), valid.assistant().answeredAt(),
                        valid.assistant().metadata(), valid.assistant().traceId()));
                case CONTROL_ID -> replaceOccurrence(valid, occurrenceWith(
                        valid, valid.assistant().messageId(), valid.assistant().seqNo(),
                        valid.assistant().msgType(), valid.assistant().messageType(),
                        "unexpected-control", valid.assistant().answeredAt(),
                        valid.assistant().metadata(), valid.assistant().traceId()));
                case ANSWERED_AT -> replaceOccurrence(valid, occurrenceWith(
                        valid, valid.assistant().messageId(), valid.assistant().seqNo(),
                        valid.assistant().msgType(), valid.assistant().messageType(),
                        valid.assistant().controlId(), Instant.EPOCH,
                        valid.assistant().metadata(), valid.assistant().traceId()));
                case METADATA -> replaceOccurrence(valid, occurrenceWith(
                        valid, valid.assistant().messageId(), valid.assistant().seqNo(),
                        valid.assistant().msgType(), valid.assistant().messageType(),
                        valid.assistant().controlId(), valid.assistant().answeredAt(),
                        Map.of("unexpected", true), valid.assistant().traceId()));
                case SEQ -> replaceOccurrence(valid, occurrenceWith(
                        valid, valid.assistant().messageId(), valid.assistant().seqNo() + 1L,
                        valid.assistant().msgType(), valid.assistant().messageType(),
                        valid.assistant().controlId(), valid.assistant().answeredAt(),
                        valid.assistant().metadata(), valid.assistant().traceId()));
                case MESSAGE_ID -> replaceOccurrence(valid, occurrenceWith(
                        valid, EXPECTED_FRONTIER.maxMessageId(), valid.assistant().seqNo(),
                        valid.assistant().msgType(), valid.assistant().messageType(),
                        valid.assistant().controlId(), valid.assistant().answeredAt(),
                        valid.assistant().metadata(), valid.assistant().traceId()));
            };
        }

        @Override
        public InteractiveIntentAck commitInteractiveIntent(InteractiveIntentCommand command) {
            IntentCommitAck intent = commitIntent(command.intent());
            return validInteractiveAck(command, intent);
        }

        private static PersistedMessageOccurrence occurrenceWith(
                IntentCommitAck ack,
                long messageId,
                long seqNo,
                String msgType,
                String messageType,
                String controlId,
                Instant answeredAt,
                Map<String, Object> metadata,
                String traceId) {
            PersistedMessageOccurrence original = ack.assistant();
            return new PersistedMessageOccurrence(
                    messageId,
                    seqNo,
                    original.writeBatchId(),
                    original.writeBatchOrdinal(),
                    original.message(),
                    msgType,
                    messageType,
                    controlId,
                    answeredAt,
                    metadata,
                    traceId);
        }

        private static IntentCommitAck replaceOccurrence(
                IntentCommitAck ack, PersistedMessageOccurrence occurrence) {
            return new IntentCommitAck(
                    ack.attemptId(),
                    ack.stepId(),
                    occurrence,
                    ack.preIntentFrontier(),
                    ack.manifest(),
                    ack.assistantPayloadHash(),
                    ack.manifestHash(),
                    ack.replaySafety());
        }
    }

    private enum MalformedAck {
        STEP,
        BATCH,
        ORDINAL,
        ASSISTANT,
        MANIFEST,
        FRONTIER,
        TRACE,
        MSG_TYPE,
        MESSAGE_TYPE,
        CONTROL_ID,
        ANSWERED_AT,
        METADATA,
        SEQ,
        MESSAGE_ID
    }

    private static final class RecordingScheduler implements ToolExecutionScheduler {
        private final AtomicInteger submissions = new AtomicInteger();

        @Override
        public <T> CompletableFuture<T> submit(Supplier<T> task) {
            submissions.incrementAndGet();
            return CompletableFuture.failedFuture(
                    new AssertionError("tool scheduling is outside this intent ACK test"));
        }
    }

    private static final class ImmediateScheduler implements ToolExecutionScheduler {
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

    private static class RecordingBroadcaster implements ChatEventBroadcaster {
        private final List<Message> appended = new ArrayList<>();
        private final List<FrozenJson> startedInputs = new ArrayList<>();
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
            startedInputs.add(FrozenJson.capture(input));
        }

        @Override
        public void toolFinished(
                String sessionId, String toolUseId, String status, long durationMs, String error) {
            toolFinished.incrementAndGet();
        }
    }

    private static final class MutatingToolStartedBroadcaster extends RecordingBroadcaster {

        @Override
        public void toolStarted(
                String sessionId, String toolUseId, String name, Map<String, Object> input) {
            super.toolStarted(sessionId, toolUseId, name, input);
            mutateNestedInput(input);
        }
    }

    private static final class RecordingExecutingTool implements Tool {
        private static final String NAME = "ExactInputProbe";
        private final AtomicInteger executions = new AtomicInteger();
        private final List<FrozenJson> inputs = new ArrayList<>();

        @Override
        public String getName() {
            return NAME;
        }

        @Override
        public String getDescription() {
            return "Records the exact durable input received by execution";
        }

        @Override
        public ToolSchema getToolSchema() {
            return new ToolSchema(NAME, getDescription(), Map.of("type", "object"));
        }

        @Override
        public SkillResult execute(Map<String, Object> input, SkillContext context) {
            executions.incrementAndGet();
            inputs.add(FrozenJson.capture(input));
            return SkillResult.success("recorded");
        }
    }

    private static final class MutatingExecutingTool implements Tool {
        private static final String NAME = "MutatingInputProbe";
        private final AtomicInteger executions = new AtomicInteger();
        private final List<FrozenJson> inputsBeforeMutation = new ArrayList<>();

        @Override
        public String getName() {
            return NAME;
        }

        @Override
        public String getDescription() {
            return "Mutates the received input after recording it";
        }

        @Override
        public ToolSchema getToolSchema() {
            return new ToolSchema(NAME, getDescription(), Map.of("type", "object"));
        }

        @Override
        public SkillResult execute(Map<String, Object> input, SkillContext context) {
            executions.incrementAndGet();
            inputsBeforeMutation.add(FrozenJson.capture(input));
            mutateNestedInput(input);
            return SkillResult.success("mutated");
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

    private static final class ProbeTool implements Tool {
        private final String name;
        private final boolean readOnly;

        private ProbeTool(String name, boolean readOnly) {
            this.name = name;
            this.readOnly = readOnly;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "Durable intent manifest probe";
        }

        @Override
        public ToolSchema getToolSchema() {
            return new ToolSchema(name, getDescription(), Map.of("type", "object"));
        }

        @Override
        public SkillResult execute(Map<String, Object> input, SkillContext context) {
            throw new AssertionError("Tool must not execute in intent snapshot test");
        }

        @Override
        public boolean isReadOnly() {
            return readOnly;
        }
    }

    /** Convenience constructor for changing only the step while preserving every other ACK field. */
    private static IntentCommitAck newAckWithStep(UUID stepId, IntentCommitAck source) {
        return new IntentCommitAck(
                source.attemptId(),
                stepId,
                source.assistant(),
                source.preIntentFrontier(),
                source.manifest(),
                source.assistantPayloadHash(),
                source.manifestHash(),
                source.replaySafety());
    }
}
