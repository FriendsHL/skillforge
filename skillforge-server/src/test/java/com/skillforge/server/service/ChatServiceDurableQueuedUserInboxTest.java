package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.AgentLoopEngine;
import com.skillforge.core.engine.CancellationRegistry;
import com.skillforge.core.engine.confirm.PendingConfirmationRegistry;
import com.skillforge.core.engine.confirm.SessionConfirmCache;
import com.skillforge.core.engine.durability.MessageSnapshot;
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
import com.skillforge.server.session.SessionQueuedUserInboxService;
import com.skillforge.server.subagent.SubAgentRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceDurableQueuedUserInboxTest {

    private final String sessionId = "00000000-0000-0000-0000-000000000911";
    private final long userId = 91L;
    private SessionService sessionService;
    private AgentLoopEngine engine;
    private SessionQueuedUserInboxService inbox;
    private DurableSessionRecoveryCoordinator recovery;
    private ThreadPoolExecutor executor;
    private ChatService service;

    @BeforeEach
    void setUp() {
        AgentService agentService = mock(AgentService.class);
        sessionService = mock(SessionService.class);
        engine = mock(AgentLoopEngine.class);
        inbox = mock(SessionQueuedUserInboxService.class);
        recovery = mock(DurableSessionRecoveryCoordinator.class);
        CompactionService compactionService = mock(CompactionService.class);
        executor = new ThreadPoolExecutor(
                1, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>(4));
        service = new ChatService(
                agentService,
                sessionService,
                mock(SkillRegistry.class),
                engine,
                mock(ModelUsageRepository.class),
                null,
                executor,
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
                id -> id,
                mock(LlmTraceStore.class),
                mock(org.springframework.context.ApplicationEventPublisher.class),
                null);
        SessionHistoryProperties properties = new SessionHistoryProperties();
        properties.setEnabled(true);
        service.configureSessionDurability(
                properties,
                mock(SessionLoopAdmissionService.class),
                mock(SessionDurableCompletionReconciler.class),
                mock(SessionLoopLeaseHeartbeat.class),
                recovery);
        service.configureDurableQueuedUserInbox(inbox);

        SessionEntity session = new SessionEntity();
        session.setId(sessionId);
        session.setUserId(userId);
        session.setAgentId(92L);
        session.setRuntimeStatus("running");
        when(sessionService.getSession(sessionId)).thenReturn(session);
        AgentEntity agent = new AgentEntity();
        agent.setId(92L);
        when(agentService.getAgent(92L)).thenReturn(agent);
        when(compactionService.lockFor(sessionId)).thenReturn(new Object());
    }

    @AfterEach
    void tearDown() {
        if (executor != null) executor.shutdownNow();
    }

    @Test
    void runningInputCommitsStableInboxIdentityAndReturnsOnlyScheduledAck() {
        UUID requestId = UUID.randomUUID();
        MessageSnapshot exact = MessageSnapshot.capture(Message.user("queued update"));
        when(inbox.acceptOrRoute(sessionId, userId, requestId, exact))
                .thenReturn(new SessionQueuedUserInboxService.AcceptanceAck(
                        SessionQueuedUserInboxService.AcceptanceMode.QUEUED_LIVE,
                        requestId, 301L, exact, null));

        ChatService.ChatSubmissionAck first = service.submitUserMessage(
                sessionId, "queued update", userId, List.of(), requestId);
        ChatService.ChatSubmissionAck ackLossRetry = service.submitUserMessage(
                sessionId, "queued update", userId, List.of(), requestId);

        assertThat(first).isEqualTo(ackLossRetry);
        assertThat(first.status()).isEqualTo("scheduled");
        assertThat(first.requestId()).isEqualTo(requestId);
        verify(inbox, times(2)).acceptOrRoute(sessionId, userId, requestId, exact);
        verify(sessionService, never()).appendNormalMessages(
                anyString(), any(), anyString());
        verify(engine, never()).run(
                any(), anyString(), any(Message.class), any(),
                anyString(), anyLong(), any());
    }

    @Test
    void runningAttachmentFailsClosedBeforeInboxAcceptance() {
        assertThatThrownBy(() -> service.submitUserMessage(
                sessionId, "with attachment", userId,
                List.of("attachment-1"), UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Attachments cannot be queued while the session is running");

        verify(inbox, never()).acceptOrRoute(anyString(), anyLong(), any(), any());
    }

    @Test
    void expiredAcceptanceSchedulesFencedRecoveryWithoutChangingTheAck() {
        UUID requestId = UUID.randomUUID();
        MessageSnapshot exact = MessageSnapshot.capture(Message.user("recover me"));
        when(inbox.acceptOrRoute(sessionId, userId, requestId, exact))
                .thenReturn(new SessionQueuedUserInboxService.AcceptanceAck(
                        SessionQueuedUserInboxService.AcceptanceMode.QUEUED_RECOVERY_REQUIRED,
                        requestId, 302L, exact, null));
        when(recovery.recover(sessionId, userId))
                .thenThrow(new IllegalStateException("simulated recovery fence conflict"));

        ChatService.ChatSubmissionAck acknowledgement = service.submitUserMessage(
                sessionId, "recover me", userId, List.of(), requestId);

        assertThat(acknowledgement)
                .isEqualTo(new ChatService.ChatSubmissionAck(requestId, "scheduled"));
        verify(recovery).recover(sessionId, userId);
    }

    @Test
    void missingRequestIdentityIsGeneratedBeforeAcceptance() {
        when(inbox.acceptOrRoute(anyString(), anyLong(), any(UUID.class), any()))
                .thenAnswer(invocation -> {
                    UUID generated = invocation.getArgument(2);
                    MessageSnapshot exact = invocation.getArgument(3);
                    return new SessionQueuedUserInboxService.AcceptanceAck(
                            SessionQueuedUserInboxService.AcceptanceMode.QUEUED_LIVE,
                            generated, 303L, exact, null);
                });

        ChatService.ChatSubmissionAck acknowledgement = service.submitUserMessage(
                sessionId, "generated", userId, List.of(), null);

        ArgumentCaptor<UUID> identity = ArgumentCaptor.forClass(UUID.class);
        verify(inbox).acceptOrRoute(
                anyString(), anyLong(), identity.capture(), any(MessageSnapshot.class));
        assertThat(acknowledgement.requestId()).isEqualTo(identity.getValue());
    }
}
