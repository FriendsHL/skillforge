package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.AgentLoopEngine;
import com.skillforge.core.engine.CancellationRegistry;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.engine.LoopContext;
import com.skillforge.core.engine.LoopResult;
import com.skillforge.core.engine.confirm.PendingConfirmationRegistry;
import com.skillforge.core.engine.confirm.SessionConfirmCache;
import com.skillforge.core.engine.durability.DurableFrontier;
import com.skillforge.core.engine.durability.LoopDurabilityScope;
import com.skillforge.core.engine.durability.MessageSnapshot;
import com.skillforge.core.engine.durability.PersistedMessageOccurrence;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.Message;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.observability.api.LlmTraceStore;
import com.skillforge.server.config.SessionHistoryProperties;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.memory.SessionDigestExtractor;
import com.skillforge.server.repository.ModelUsageRepository;
import com.skillforge.server.session.DurableSessionRecoveryCoordinator;
import com.skillforge.server.session.SessionDurableCompletionReconciler;
import com.skillforge.server.session.SessionLoopAdmissionService;
import com.skillforge.server.session.SessionLoopLeaseHeartbeat;
import com.skillforge.server.subagent.SubAgentRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Locks ChatService's durable path away from legacy wholesale message reconciliation. */
class ChatServiceDurablePersistenceRoutingTest {

    @Test
    void repeatedTerminalContinuationsStayIterativeAndFinishOnlyTheWinningTail() {
        AgentService agentService = mock(AgentService.class);
        SessionService sessionService = mock(SessionService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        ModelUsageRepository usageRepository = mock(ModelUsageRepository.class);
        SessionTitleService titleService = mock(SessionTitleService.class);
        SubAgentRegistry subAgentRegistry = mock(SubAgentRegistry.class);
        CancellationRegistry cancellationRegistry = mock(CancellationRegistry.class);
        CompactionService compactionService = mock(CompactionService.class);
        SessionLoopAdmissionService admissionService = mock(SessionLoopAdmissionService.class);
        SessionDurableCompletionReconciler completionReconciler =
                mock(SessionDurableCompletionReconciler.class);
        SessionLoopLeaseHeartbeat leaseHeartbeat = mock(SessionLoopLeaseHeartbeat.class);
        SessionLoopLeaseHeartbeat.Handle heartbeatHandle =
                mock(SessionLoopLeaseHeartbeat.Handle.class);
        when(leaseHeartbeat.start(any())).thenReturn(heartbeatHandle);
        when(compactionService.lockFor(anyString())).thenAnswer(invocation -> new Object());
        when(compactionService.resolveContextWindowForSession(any())).thenReturn(200_000);

        ThreadPoolExecutor synchronous = new ThreadPoolExecutor(
                0, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>(16)) {
            @Override
            public void execute(Runnable command) {
                command.run();
            }
        };
        ChatService service = new ChatService(
                agentService,
                sessionService,
                mock(SkillRegistry.class),
                engine,
                usageRepository,
                null,
                synchronous,
                titleService,
                subAgentRegistry,
                cancellationRegistry,
                compactionService,
                null,
                null,
                new ObjectMapper(),
                mock(SessionDigestExtractor.class),
                new com.skillforge.server.hook.NoopLifecycleHookDispatcher(),
                new SessionConfirmCache(),
                new PendingConfirmationRegistry(),
                sessionId -> sessionId,
                mock(LlmTraceStore.class),
                mock(org.springframework.context.ApplicationEventPublisher.class),
                null);
        SessionHistoryProperties properties = new SessionHistoryProperties();
        properties.setEnabled(true);
        service.configureSessionDurability(
                properties, admissionService, completionReconciler, leaseHeartbeat,
                mock(DurableSessionRecoveryCoordinator.class));

        String sessionId = UUID.randomUUID().toString();
        long userId = 1401L;
        SessionEntity session = new SessionEntity();
        session.setId(sessionId);
        session.setUserId(userId);
        session.setAgentId(1402L);
        session.setStatus("active");
        session.setRuntimeStatus("idle");
        session.setHistoryEpoch(3L);
        when(sessionService.getSession(sessionId)).thenReturn(session);
        when(sessionService.getFullHistory(sessionId)).thenReturn(new ArrayList<>());
        when(sessionService.getContextMessages(sessionId))
                .thenReturn(new ArrayList<>(List.of(Message.assistant("persisted terminal"))));

        AgentEntity agent = new AgentEntity();
        agent.setId(1402L);
        agent.setExecutionMode("auto");
        when(agentService.getAgent(1402L)).thenReturn(agent);
        AgentDefinition definition = new AgentDefinition();
        definition.setName("iterative-durable-agent");
        definition.setModelId("fake:model");
        definition.setSystemPrompt("system");
        when(agentService.toAgentDefinition(agent)).thenReturn(definition);

        Message userMessage = Message.user("question");
        LoopDurabilityScope scope = new LoopDurabilityScope(
                sessionId, userId, 3L, UUID.randomUUID().toString(), 1L, "instance-1");
        PersistedMessageOccurrence userOccurrence = new PersistedMessageOccurrence(
                701L, 0L, UUID.randomUUID().toString(), 0,
                MessageSnapshot.capture(userMessage), "NORMAL", "normal",
                null, null, Map.of(), "user-trace");
        DurableFrontier initialFrontier = new DurableFrontier(701L, 0L);
        when(admissionService.admit(
                anyString(), anyLong(), any(UUID.class), anyString(),
                any(MessageSnapshot.class), anyString()))
                .thenReturn(new SessionLoopAdmissionService.AdmissionAck(
                        scope, userOccurrence, initialFrontier,
                        Instant.now().plusSeconds(600)));

        List<Long> runLoopDepths = new ArrayList<>();
        AtomicInteger engineCalls = new AtomicInteger();
        when(engine.run(
                any(AgentDefinition.class), any(), any(), anyList(),
                anyString(), anyLong(), any(LoopContext.class))).thenAnswer(invocation -> {
                    int ordinal = engineCalls.incrementAndGet();
                    long depth = StackWalker.getInstance().walk(frames -> frames
                            .filter(frame -> frame.getClassName().equals(ChatService.class.getName()))
                            .filter(frame -> frame.getMethodName().equals("runLoop"))
                            .count());
                    runLoopDepths.add(depth);
                    Message terminal = Message.assistant("answer-" + ordinal);
                    LoopResult result = new LoopResult();
                    result.setMessages(new ArrayList<>(List.of(terminal)));
                    result.setToolCalls(new ArrayList<>());
                    result.setDeferredBroadcastMessages(List.of(terminal));
                    return result;
                });

        AtomicInteger reconciliations = new AtomicInteger();
        when(completionReconciler.reconcile(
                any(), any(), any(UUID.class), any(MessageSnapshot.class), anyString(),
                anyLong(), anyLong())).thenAnswer(invocation -> {
                    int ordinal = reconciliations.incrementAndGet();
                    DurableFrontier pre = invocation.getArgument(1);
                    DurableFrontier post = new DurableFrontier(701L + ordinal, pre.maxSeq() + 1L);
                    MessageSnapshot snapshot = invocation.getArgument(3);
                    PersistedMessageOccurrence terminal = new PersistedMessageOccurrence(
                            post.maxMessageId(), post.maxSeq(),
                            invocation.<UUID>getArgument(2).toString(), 0,
                            snapshot, "NORMAL", "normal", null, null, Map.of(),
                            "completion-" + ordinal);
                    boolean continueAgain = ordinal < 5;
                    return new SessionDurableCompletionReconciler.CompletionAck(
                            invocation.getArgument(2), pre, post, terminal,
                            continueAgain,
                            continueAgain ? Instant.now().plusSeconds(600) : null);
                });

        service.chatAsync(sessionId, "question", userId);

        assertThat(engineCalls).hasValue(5);
        assertThat(reconciliations).hasValue(5);
        assertThat(runLoopDepths).hasSize(5).allMatch(runLoopDepths.get(0)::equals);
        verify(heartbeatHandle, times(5)).close();
        verify(subAgentRegistry, times(1)).onSessionLoopFinished(
                org.mockito.ArgumentMatchers.eq(sessionId), isNull(),
                org.mockito.ArgumentMatchers.eq("completed"), anyInt(), anyLong());
        synchronous.shutdownNow();
    }

    @Test
    void enabledDurabilityUsesAtomicAdmissionAndAppendOnlyCompletion_notLegacyUpdateMessages() {
        AgentService agentService = mock(AgentService.class);
        SessionService sessionService = mock(SessionService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        ModelUsageRepository usageRepository = mock(ModelUsageRepository.class);
        ChatEventBroadcaster broadcaster = mock(ChatEventBroadcaster.class);
        SessionTitleService titleService = mock(SessionTitleService.class);
        SubAgentRegistry subAgentRegistry = mock(SubAgentRegistry.class);
        CancellationRegistry cancellationRegistry = mock(CancellationRegistry.class);
        CompactionService compactionService = mock(CompactionService.class);
        SessionLoopAdmissionService admissionService = mock(SessionLoopAdmissionService.class);
        SessionDurableCompletionReconciler completionReconciler =
                mock(SessionDurableCompletionReconciler.class);
        SessionLoopLeaseHeartbeat leaseHeartbeat = mock(SessionLoopLeaseHeartbeat.class);
        SessionLoopLeaseHeartbeat.Handle heartbeatHandle =
                mock(SessionLoopLeaseHeartbeat.Handle.class);
        when(leaseHeartbeat.start(any())).thenReturn(heartbeatHandle);
        when(compactionService.lockFor(anyString())).thenAnswer(invocation -> new Object());
        when(compactionService.resolveContextWindowForSession(any())).thenReturn(200_000);

        ThreadPoolExecutor synchronous = new ThreadPoolExecutor(
                0, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>(16)) {
            @Override
            public void execute(Runnable command) {
                command.run();
            }
        };
        ChatService service = new ChatService(
                agentService,
                sessionService,
                mock(SkillRegistry.class),
                engine,
                usageRepository,
                broadcaster,
                synchronous,
                titleService,
                subAgentRegistry,
                cancellationRegistry,
                compactionService,
                null,
                null,
                new ObjectMapper(),
                mock(SessionDigestExtractor.class),
                new com.skillforge.server.hook.NoopLifecycleHookDispatcher(),
                new SessionConfirmCache(),
                new PendingConfirmationRegistry(),
                sessionId -> sessionId,
                mock(LlmTraceStore.class),
                mock(org.springframework.context.ApplicationEventPublisher.class),
                null);
        SessionHistoryProperties properties = new SessionHistoryProperties();
        properties.setEnabled(true);
        service.configureSessionDurability(
                properties, admissionService, completionReconciler, leaseHeartbeat,
                mock(DurableSessionRecoveryCoordinator.class));

        String sessionId = UUID.randomUUID().toString();
        long userId = 1301L;
        SessionEntity session = new SessionEntity();
        session.setId(sessionId);
        session.setUserId(userId);
        session.setAgentId(1302L);
        session.setStatus("active");
        session.setRuntimeStatus("idle");
        session.setHistoryEpoch(2L);
        when(sessionService.getSession(sessionId)).thenReturn(session);
        when(sessionService.getFullHistory(sessionId)).thenReturn(new ArrayList<>());
        when(sessionService.getContextMessages(sessionId)).thenReturn(new ArrayList<>());

        AgentEntity agent = new AgentEntity();
        agent.setId(1302L);
        agent.setExecutionMode("auto");
        when(agentService.getAgent(1302L)).thenReturn(agent);
        AgentDefinition definition = new AgentDefinition();
        definition.setName("durable-agent");
        definition.setModelId("fake:model");
        definition.setSystemPrompt("system");
        when(agentService.toAgentDefinition(agent)).thenReturn(definition);

        Message userMessage = Message.user("question");
        LoopDurabilityScope scope = new LoopDurabilityScope(
                sessionId, userId, 2L, UUID.randomUUID().toString(), 1L, "instance-1");
        PersistedMessageOccurrence occurrence = new PersistedMessageOccurrence(
                501L,
                0L,
                UUID.randomUUID().toString(),
                0,
                MessageSnapshot.capture(userMessage),
                "NORMAL",
                "normal",
                null,
                null,
                Map.of(),
                "user-trace");
        DurableFrontier frontier = new DurableFrontier(501L, 0L);
        SessionLoopAdmissionService.AdmissionAck admission =
                new SessionLoopAdmissionService.AdmissionAck(
                        scope, occurrence, frontier, Instant.now().plusSeconds(600));
        when(admissionService.admit(
                anyString(), anyLong(), any(UUID.class), anyString(),
                any(MessageSnapshot.class), anyString())).thenReturn(admission);

        Message terminal = Message.assistant("answer");
        LoopResult result = new LoopResult();
        result.setMessages(new ArrayList<>(List.of(userMessage, terminal)));
        result.setToolCalls(new ArrayList<>());
        result.setDeferredBroadcastMessages(List.of(terminal));
        result.setTotalInputTokens(11L);
        result.setTotalOutputTokens(7L);
        when(engine.run(
                any(AgentDefinition.class), anyString(), any(Message.class), anyList(),
                anyString(), anyLong(), any(LoopContext.class))).thenReturn(result);
        PersistedMessageOccurrence terminalOccurrence = new PersistedMessageOccurrence(
                502L, 1L, UUID.randomUUID().toString(), 0,
                MessageSnapshot.capture(Message.assistant("answer")),
                "NORMAL", "normal", null, null, Map.of(), "completion-trace");
        when(completionReconciler.reconcile(
                any(), any(), any(UUID.class), any(MessageSnapshot.class), anyString(),
                anyLong(), anyLong()))
                .thenAnswer(invocation -> new SessionDurableCompletionReconciler.CompletionAck(
                        invocation.getArgument(2), frontier,
                        new DurableFrontier(terminalOccurrence.messageId(), terminalOccurrence.seqNo()),
                        terminalOccurrence));

        service.chatAsync(sessionId, "question", userId);

        verify(admissionService).admit(
                anyString(), anyLong(), any(UUID.class), anyString(),
                any(MessageSnapshot.class), anyString());
        ArgumentCaptor<LoopContext> contextCaptor = ArgumentCaptor.forClass(LoopContext.class);
        verify(engine).run(
                any(AgentDefinition.class), anyString(), any(Message.class), anyList(),
                anyString(), anyLong(), contextCaptor.capture());
        assertThat(contextCaptor.getValue().getDurabilityScope()).isEqualTo(scope);
        assertThat(contextCaptor.getValue().getExpectedDurableFrontier()).isEqualTo(frontier);
        verify(completionReconciler).reconcile(
                org.mockito.ArgumentMatchers.eq(scope),
                org.mockito.ArgumentMatchers.eq(frontier),
                any(UUID.class),
                org.mockito.ArgumentMatchers.eq(MessageSnapshot.capture(terminal)),
                anyString(),
                org.mockito.ArgumentMatchers.eq(11L),
                org.mockito.ArgumentMatchers.eq(7L));
        InOrder persistenceBeforeVisibility = inOrder(completionReconciler, broadcaster);
        persistenceBeforeVisibility.verify(completionReconciler).reconcile(
                org.mockito.ArgumentMatchers.eq(scope),
                org.mockito.ArgumentMatchers.eq(frontier),
                any(UUID.class),
                org.mockito.ArgumentMatchers.eq(MessageSnapshot.capture(terminal)),
                anyString(),
                org.mockito.ArgumentMatchers.eq(11L),
                org.mockito.ArgumentMatchers.eq(7L));
        persistenceBeforeVisibility.verify(broadcaster).messageAppended(
                org.mockito.ArgumentMatchers.eq(sessionId),
                anyString(),
                any(Message.class));
        verify(sessionService, never()).addSessionUsage(sessionId, 11L, 7L);
        verify(heartbeatHandle).assertAuthoritative();
        verify(heartbeatHandle).close();
        verify(admissionService, never()).release(scope);
        verify(sessionService, never()).updateSessionMessages(
                anyString(), anyList(), anyLong(), anyLong(), anyString());
    }

}
