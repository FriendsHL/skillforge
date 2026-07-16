package com.skillforge.server.controller;

import com.skillforge.core.engine.CancellationRegistry;
import com.skillforge.core.engine.PendingAskRegistry;
import com.skillforge.core.engine.confirm.PendingConfirmationRegistry;
import com.skillforge.server.channel.router.ChannelConversationResolver;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.service.AgentService;
import com.skillforge.server.service.ChatAttachmentService;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.service.CompactionService;
import com.skillforge.server.service.ContextBreakdownService;
import com.skillforge.server.service.ReplayService;
import com.skillforge.server.service.SessionService;
import com.skillforge.server.subagent.SubAgentRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatControllerRetryTest {

    private static final String SESSION_ID = "session-1";
    private static final long OWNER = 7L;

    private ChatService chatService;
    private SessionService sessionService;
    private ChatController controller;

    @BeforeEach
    void setUp() {
        chatService = mock(ChatService.class);
        sessionService = mock(SessionService.class);
        controller = new ChatController(
                chatService,
                mock(ChatAttachmentService.class),
                sessionService,
                mock(AgentService.class),
                mock(LlmProperties.class),
                mock(PendingAskRegistry.class),
                mock(PendingConfirmationRegistry.class),
                mock(SubAgentRegistry.class),
                mock(CancellationRegistry.class),
                mock(CompactionService.class),
                mock(ReplayService.class),
                mock(ChannelConversationResolver.class),
                mock(ContextBreakdownService.class));
    }

    @Test
    void retry_ownerAccepted() {
        when(sessionService.getSession(SESSION_ID)).thenReturn(sessionOwnedBy(OWNER));

        ResponseEntity<Map<String, Object>> response = controller.retryFailedTurn(SESSION_ID, OWNER);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).containsEntry("status", "accepted");
        verify(chatService).retryFailedTurnAsync(SESSION_ID);
    }

    @Test
    void retry_differentUserForbidden() {
        when(sessionService.getSession(SESSION_ID)).thenReturn(sessionOwnedBy(OWNER));

        ResponseEntity<Map<String, Object>> response = controller.retryFailedTurn(SESSION_ID, 99L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(chatService, never()).retryFailedTurnAsync(SESSION_ID);
    }

    @Test
    void retry_nonRetryableConflict() {
        when(sessionService.getSession(SESSION_ID)).thenReturn(sessionOwnedBy(OWNER));
        doThrow(new IllegalStateException("session is not in error state"))
                .when(chatService).retryFailedTurnAsync(SESSION_ID);

        ResponseEntity<Map<String, Object>> response = controller.retryFailedTurn(SESSION_ID, OWNER);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("error", "session is not in error state");
    }

    @Test
    void retry_busyReturnsTooManyRequests() {
        when(sessionService.getSession(SESSION_ID)).thenReturn(sessionOwnedBy(OWNER));
        doThrow(new RejectedExecutionException("queue full"))
                .when(chatService).retryFailedTurnAsync(SESSION_ID);

        ResponseEntity<Map<String, Object>> response = controller.retryFailedTurn(SESSION_ID, OWNER);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    private SessionEntity sessionOwnedBy(long userId) {
        SessionEntity session = new SessionEntity();
        session.setId(SESSION_ID);
        session.setUserId(userId);
        return session;
    }
}
