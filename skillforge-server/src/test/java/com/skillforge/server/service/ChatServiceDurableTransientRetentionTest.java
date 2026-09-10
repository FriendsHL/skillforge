package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.AgentLoopEngine;
import com.skillforge.core.engine.CancellationRegistry;
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
import com.skillforge.server.session.DurableRecoveryRetryableException;
import com.skillforge.server.session.DurableSessionRecoveryCoordinator;
import com.skillforge.server.session.SessionDurableCompletionReconciler;
import com.skillforge.server.session.SessionLoopAdmissionService;
import com.skillforge.server.session.SessionLoopLeaseHeartbeat;
import com.skillforge.server.subagent.SubAgentRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A transient durability outage must leave the fenced Session available to recovery. */
class ChatServiceDurableTransientRetentionTest {

    @Test
    void heartbeatInfrastructureOutageDoesNotMarkOrReleaseDurableLoop() {
        AgentService agentService = mock(AgentService.class);
        SessionService sessionService = mock(SessionService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        CompactionService compactionService = mock(CompactionService.class);
        SessionLoopAdmissionService admissionService = mock(SessionLoopAdmissionService.class);
        SessionDurableCompletionReconciler completionReconciler =
                mock(SessionDurableCompletionReconciler.class);
        SessionLoopLeaseHeartbeat heartbeat = mock(SessionLoopLeaseHeartbeat.class);
        ThreadPoolExecutor synchronous = new ThreadPoolExecutor(
                0, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>(4)) {
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
                mock(ModelUsageRepository.class),
                null,
                synchronous,
                mock(SessionTitleService.class),
                mock(SubAgentRegistry.class),
                mock(CancellationRegistry.class),
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
        try {
            SessionHistoryProperties properties = new SessionHistoryProperties();
            properties.setEnabled(true);
            service.configureSessionDurability(
                    properties, admissionService, completionReconciler, heartbeat,
                    mock(DurableSessionRecoveryCoordinator.class));

            String sessionId = UUID.randomUUID().toString();
            long userId = 1501L;
            SessionEntity session = new SessionEntity();
            session.setId(sessionId);
            session.setUserId(userId);
            session.setAgentId(1502L);
            session.setStatus("active");
            session.setRuntimeStatus("idle");
            session.setHistoryEpoch(3L);
            when(sessionService.getSession(sessionId)).thenReturn(session);
            when(sessionService.getFullHistory(sessionId)).thenReturn(new ArrayList<>());
            when(sessionService.getContextMessages(sessionId)).thenReturn(new ArrayList<>());
            when(compactionService.lockFor(sessionId)).thenReturn(new Object());
            when(compactionService.resolveContextWindowForSession(any())).thenReturn(200_000);

            AgentEntity agent = new AgentEntity();
            agent.setId(1502L);
            agent.setExecutionMode("auto");
            when(agentService.getAgent(1502L)).thenReturn(agent);
            AgentDefinition definition = new AgentDefinition();
            definition.setName("transient-retention-agent");
            definition.setModelId("fake:model");
            definition.setSystemPrompt("system");
            when(agentService.toAgentDefinition(agent)).thenReturn(definition);

            Message input = Message.user("question");
            LoopDurabilityScope scope = new LoopDurabilityScope(
                    sessionId, userId, 3L, UUID.randomUUID().toString(), 4L, "owner");
            PersistedMessageOccurrence persisted = new PersistedMessageOccurrence(
                    701L, 0L, UUID.randomUUID().toString(), 0,
                    MessageSnapshot.capture(input), "NORMAL", "normal",
                    null, null, Map.of(), "trace");
            when(admissionService.admit(
                    anyString(), anyLong(), any(UUID.class), anyString(),
                    any(MessageSnapshot.class), anyString()))
                    .thenReturn(new SessionLoopAdmissionService.AdmissionAck(
                            scope, persisted, new DurableFrontier(701L, 0L),
                            Instant.now().plusSeconds(120)));
            when(heartbeat.start(scope)).thenThrow(new DurableRecoveryRetryableException());

            service.chatAsync(sessionId, "question", userId);

            verify(heartbeat).start(scope);
            verify(engine, never()).run(any(), anyString(), any(), any(),
                    anyString(), anyLong(), any());
            verify(admissionService, never()).failIfNoBlockingAttempt(any(), any());
            verify(admissionService, never()).release(any());
            verify(sessionService, never()).saveSession(any());
        } finally {
            synchronous.shutdownNow();
        }
    }
}
