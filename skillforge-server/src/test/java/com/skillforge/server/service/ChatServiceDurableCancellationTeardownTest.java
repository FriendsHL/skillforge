package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.AgentLoopEngine;
import com.skillforge.core.engine.CancellationRegistry;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.engine.LoopContext;
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
import com.skillforge.server.session.SessionDurableCancellationService;
import com.skillforge.server.session.SessionDurableCompletionReconciler;
import com.skillforge.server.session.SessionLoopAdmissionService;
import com.skillforge.server.session.SessionLoopLeaseHeartbeat;
import com.skillforge.server.subagent.SubAgentRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The stale loop must consume a committed cancellation receipt without changing its projection. */
class ChatServiceDurableCancellationTeardownTest {

    @Test
    void committedCancellationKeepsFriendlyTerminalFactAndDoesNotRebroadcastControllerProjection() {
        AgentService agentService = mock(AgentService.class);
        SessionService sessionService = mock(SessionService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        ChatEventBroadcaster broadcaster = mock(ChatEventBroadcaster.class);
        SubAgentRegistry subAgentRegistry = mock(SubAgentRegistry.class);
        CompactionService compactionService = mock(CompactionService.class);
        SessionLoopAdmissionService admissionService = mock(SessionLoopAdmissionService.class);
        SessionLoopLeaseHeartbeat leaseHeartbeat = mock(SessionLoopLeaseHeartbeat.class);
        SessionLoopLeaseHeartbeat.Handle heartbeat = mock(SessionLoopLeaseHeartbeat.Handle.class);
        SessionDurableCancellationService cancellationService =
                mock(SessionDurableCancellationService.class);
        when(leaseHeartbeat.start(any())).thenReturn(heartbeat);
        when(compactionService.lockFor(anyString())).thenAnswer(invocation -> new Object());
        when(compactionService.resolveContextWindowForSession(any())).thenReturn(200_000);

        ThreadPoolExecutor synchronous = new ThreadPoolExecutor(
                0, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>(4)) {
            @Override
            public void execute(Runnable command) {
                command.run();
            }
        };
        try {
            ChatService service = new ChatService(
                    agentService, sessionService, mock(SkillRegistry.class), engine,
                    mock(ModelUsageRepository.class), broadcaster, synchronous,
                    mock(SessionTitleService.class), subAgentRegistry,
                    mock(CancellationRegistry.class), compactionService, null, null,
                    new ObjectMapper(), mock(SessionDigestExtractor.class),
                    new com.skillforge.server.hook.NoopLifecycleHookDispatcher(),
                    new SessionConfirmCache(), new PendingConfirmationRegistry(),
                    sessionId -> sessionId, mock(LlmTraceStore.class),
                    mock(ApplicationEventPublisher.class), null);
            SessionHistoryProperties properties = new SessionHistoryProperties();
            properties.setEnabled(true);
            service.configureSessionDurability(
                    properties, admissionService,
                    mock(SessionDurableCompletionReconciler.class), leaseHeartbeat,
                    mock(DurableSessionRecoveryCoordinator.class));
            service.configureDurableCancellation(cancellationService);

            String sessionId = UUID.randomUUID().toString();
            long userId = 1701L;
            SessionEntity session = new SessionEntity();
            session.setId(sessionId);
            session.setUserId(userId);
            session.setAgentId(1702L);
            session.setStatus("active");
            session.setRuntimeStatus("idle");
            session.setHistoryEpoch(5L);
            when(sessionService.getSession(sessionId)).thenReturn(session);
            when(sessionService.getFullHistory(sessionId)).thenReturn(new ArrayList<>());
            when(sessionService.getContextMessages(sessionId)).thenReturn(new ArrayList<>());

            AgentEntity agent = new AgentEntity();
            agent.setId(1702L);
            agent.setExecutionMode("auto");
            when(agentService.getAgent(1702L)).thenReturn(agent);
            AgentDefinition definition = new AgentDefinition();
            definition.setName("cancelled-durable-agent");
            definition.setModelId("fake:model");
            definition.setSystemPrompt("system");
            when(agentService.toAgentDefinition(agent)).thenReturn(definition);

            Message input = Message.user("question");
            LoopDurabilityScope scope = new LoopDurabilityScope(
                    sessionId, userId, 5L, UUID.randomUUID().toString(), 8L, "owner-1");
            PersistedMessageOccurrence occurrence = new PersistedMessageOccurrence(
                    801L, 0L, UUID.randomUUID().toString(), 0,
                    MessageSnapshot.capture(input), "NORMAL", "normal",
                    null, null, Map.of(), "trace-1");
            when(admissionService.admit(
                    anyString(), anyLong(), any(UUID.class), anyString(),
                    any(MessageSnapshot.class), anyString()))
                    .thenReturn(new SessionLoopAdmissionService.AdmissionAck(
                            scope, occurrence, new DurableFrontier(801L, 0L),
                            Instant.now().plusSeconds(120)));
            when(engine.run(
                    any(AgentDefinition.class), anyString(), any(Message.class), any(),
                    anyString(), anyLong(), any(LoopContext.class)))
                    .thenThrow(new IllegalStateException("stale loop fence"));

            SessionDurableCancellationService.CancellationAck cancellation =
                    new SessionDurableCancellationService.CancellationAck(
                            UUID.randomUUID(), sessionId, userId, 5L,
                            scope.loopId(), scope.loopFence(), scope.ownerInstanceId(),
                            null, null,
                            SessionDurableCancellationService.CancellationOutcome
                                    .CANCELLED_BEFORE_EXECUTION,
                            Instant.now());
            when(cancellationService.findCommittedForTarget(scope))
                    .thenReturn(Optional.of(cancellation));

            service.chatAsync(sessionId, "question", userId);

            verify(subAgentRegistry).onSessionLoopFinished(
                    eq(sessionId), eq("Cancelled by user"), eq("cancelled"),
                    eq(0), anyLong());
            verify(broadcaster, never()).sessionStatus(
                    sessionId, "idle", "cancelled", null);
            verify(admissionService, never()).failIfNoBlockingAttempt(any(), any());
        } finally {
            synchronous.shutdownNow();
        }
    }
}
