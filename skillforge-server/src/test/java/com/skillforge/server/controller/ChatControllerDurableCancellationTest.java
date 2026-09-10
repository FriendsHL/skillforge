package com.skillforge.server.controller;

import com.skillforge.core.engine.CancellationRegistry;
import com.skillforge.core.engine.PendingAskRegistry;
import com.skillforge.core.engine.confirm.PendingConfirmationRegistry;
import com.skillforge.server.channel.router.ChannelConversationResolver;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.server.config.AuthInterceptor;
import com.skillforge.server.config.PlatformAccessPrincipal;
import com.skillforge.server.config.SessionHistoryProperties;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.service.AgentService;
import com.skillforge.server.service.ChatAttachmentService;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.service.CompactionService;
import com.skillforge.server.service.ContextBreakdownService;
import com.skillforge.server.service.ReplayService;
import com.skillforge.server.service.SessionService;
import com.skillforge.server.session.DurableCancellationRejectedException;
import com.skillforge.server.session.DurableCancellationRetryableException;
import com.skillforge.server.session.SessionDurableCancellationService;
import com.skillforge.server.subagent.SubAgentRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import jakarta.servlet.http.HttpServletRequest;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatControllerDurableCancellationTest {

    private static final String SESSION_ID = "durable-cancel-session";
    private static final long USER_ID = 7101L;

    private SessionService sessionService;
    private CancellationRegistry cancellationRegistry;
    private PendingConfirmationRegistry confirmationRegistry;
    private SessionDurableCancellationService durableCancellationService;
    private SessionHistoryProperties historyProperties;
    private HttpServletRequest request;
    private ChatController controller;

    @BeforeEach
    void setUp() {
        sessionService = mock(SessionService.class);
        cancellationRegistry = mock(CancellationRegistry.class);
        confirmationRegistry = mock(PendingConfirmationRegistry.class);
        durableCancellationService = mock(SessionDurableCancellationService.class);
        historyProperties = new SessionHistoryProperties();
        historyProperties.setEnabled(true);
        request = mock(HttpServletRequest.class);
        when(request.getAttribute(AuthInterceptor.PRINCIPAL_ATTRIBUTE))
                .thenReturn(PlatformAccessPrincipal.platformAdmin());
        controller = new ChatController(
                mock(ChatService.class),
                mock(ChatAttachmentService.class),
                sessionService,
                mock(AgentService.class),
                mock(LlmProperties.class),
                mock(PendingAskRegistry.class),
                confirmationRegistry,
                mock(SubAgentRegistry.class),
                cancellationRegistry,
                mock(CompactionService.class),
                mock(ReplayService.class),
                mock(ChannelConversationResolver.class),
                mock(ContextBreakdownService.class),
                historyProperties,
                durableCancellationService);
        when(sessionService.getSession(SESSION_ID)).thenReturn(ownedSession());
    }

    @Test
    void cancel_durableModeSignalsMemoryOnlyAfterCommittedAck() {
        UUID requestId = UUID.randomUUID();
        SessionDurableCancellationService.CancellationAck ack = ack(requestId);
        when(durableCancellationService.cancel(any())).thenReturn(ack);
        when(cancellationRegistry.cancel(SESSION_ID)).thenReturn(false);

        ResponseEntity<Map<String, Object>> response =
                controller.cancelChat(SESSION_ID, 9999L, requestId, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("status", "cancelled")
                .containsEntry("requestId", requestId)
                .containsEntry("outcome", "CANCELLED_BEFORE_EXECUTION");
        InOrder order = inOrder(durableCancellationService, cancellationRegistry);
        order.verify(durableCancellationService).cancel(
                new SessionDurableCancellationService.CancellationCommand(
                        requestId, SESSION_ID, USER_ID));
        order.verify(cancellationRegistry).cancel(SESSION_ID);
        verify(confirmationRegistry).completeAllForSession(
                org.mockito.ArgumentMatchers.eq(SESSION_ID), any());
    }

    @Test
    void cancel_transientDurabilityFailureReturns503WithoutInMemoryCancellation() {
        when(durableCancellationService.cancel(any()))
                .thenThrow(new DurableCancellationRetryableException());

        ResponseEntity<Map<String, Object>> response =
                controller.cancelChat(SESSION_ID, USER_ID, UUID.randomUUID(), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry(
                "error", "Cancellation is temporarily unavailable; retry with the same requestId");
        verify(cancellationRegistry, never()).cancel(any());
        verify(confirmationRegistry, never()).completeAllForSession(any(), any());
    }

    @Test
    void cancel_staleDurableTargetReturnsConflictWithoutInMemoryCancellation() {
        when(durableCancellationService.cancel(any()))
                .thenThrow(new DurableCancellationRejectedException());

        ResponseEntity<Map<String, Object>> response =
                controller.cancelChat(SESSION_ID, USER_ID, UUID.randomUUID(), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry(
                "error", "No matching running loop for this cancellation request");
        verify(cancellationRegistry, never()).cancel(any());
        verify(confirmationRegistry, never()).completeAllForSession(any(), any());
    }

    @Test
    void cancel_legacyModeKeepsExistingInMemoryBehavior() {
        historyProperties.setEnabled(false);
        when(cancellationRegistry.cancel(SESSION_ID)).thenReturn(true);

        ResponseEntity<Map<String, Object>> response =
                controller.cancelChat(SESSION_ID, USER_ID, null, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "cancelling");
        verify(durableCancellationService, never()).cancel(any());
        verify(cancellationRegistry).cancel(SESSION_ID);
    }

    @Test
    void cancel_durableModeWithoutTrustedPrincipalFailsBeforeSessionLookup() {
        when(request.getAttribute(AuthInterceptor.PRINCIPAL_ATTRIBUTE)).thenReturn(null);

        ResponseEntity<Map<String, Object>> response =
                controller.cancelChat(SESSION_ID, 9999L, UUID.randomUUID(), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(sessionService, never()).getSession(any());
        verify(durableCancellationService, never()).cancel(any());
        verify(cancellationRegistry, never()).cancel(any());
    }

    private static SessionEntity ownedSession() {
        SessionEntity session = new SessionEntity();
        session.setId(SESSION_ID);
        session.setUserId(USER_ID);
        return session;
    }

    private static SessionDurableCancellationService.CancellationAck ack(UUID requestId) {
        return new SessionDurableCancellationService.CancellationAck(
                requestId, SESSION_ID, USER_ID, 3L,
                UUID.randomUUID().toString(), 7L, "owner",
                null, null,
                SessionDurableCancellationService.CancellationOutcome.CANCELLED_BEFORE_EXECUTION,
                Instant.now());
    }
}
