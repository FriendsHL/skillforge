package com.skillforge.server.controller;

import com.skillforge.core.engine.CancellationRegistry;
import com.skillforge.core.engine.PendingAskRegistry;
import com.skillforge.core.engine.confirm.Decision;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ownership / validation gate tests for the UNIFIED confirmation endpoint
 * {@code POST /api/chat/{sessionId}/confirmation} (ACP-EXTERNAL-AGENT P1c-2,
 * Seam 2). The endpoint applies {@code requireOwnedSession} to BOTH the engine
 * and ACP/cc paths before delegating to {@link ChatService#answerConfirmation},
 * which discriminates internally (covered by ChatServiceConfirmationRoutingTest).
 *
 * <p>Plain unit test — services mocked, no Spring context.
 */
class ChatControllerConfirmationTest {

    private static final String SESSION_ID = "sub-1";
    private static final long OWNER = 1L;
    private static final long ATTACKER = 2L;
    private static final String CONF_ID = "conf-1";

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

    private SessionEntity sessionOwnedBy(Long userId) {
        SessionEntity s = new SessionEntity();
        s.setId(SESSION_ID);
        s.setUserId(userId);
        return s;
    }

    private Map<String, Object> body(Long userId, Object decision) {
        Map<String, Object> b = new LinkedHashMap<>();
        if (userId != null) b.put("userId", userId);
        b.put("confirmationId", CONF_ID);
        if (decision != null) b.put("decision", decision);
        return b;
    }

    @Test
    @DisplayName("(c) ownership: a different user answering another user's confirmation → 403, ChatService NOT called")
    void differentUser_rejected() {
        when(sessionService.getSession(SESSION_ID)).thenReturn(sessionOwnedBy(OWNER));

        ResponseEntity<?> resp = controller.confirm(SESSION_ID, body(ATTACKER, "approved"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(chatService, never()).answerConfirmation(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("(c) ownership: missing userId → 400")
    void missingUserId_rejected() {
        ResponseEntity<?> resp = controller.confirm(SESSION_ID, body(null, "approved"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(chatService, never()).answerConfirmation(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("(c) ownership: unknown session → 404 (existence not leaked), ChatService NOT called")
    void unknownSession_404() {
        when(sessionService.getSession(SESSION_ID)).thenThrow(new RuntimeException("not found"));

        ResponseEntity<?> resp = controller.confirm(SESSION_ID, body(OWNER, "approved"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(chatService, never()).answerConfirmation(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("(e) decision=timeout from the wire → 400, ChatService NOT called")
    void timeoutDecision_rejected() {
        when(sessionService.getSession(SESSION_ID)).thenReturn(sessionOwnedBy(OWNER));

        ResponseEntity<?> resp = controller.confirm(SESSION_ID, body(OWNER, "timeout"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(chatService, never()).answerConfirmation(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("owner answering own confirmation → delegates to ChatService.answerConfirmation, 200")
    void owner_delegates() {
        when(sessionService.getSession(SESSION_ID)).thenReturn(sessionOwnedBy(OWNER));

        ResponseEntity<?> resp = controller.confirm(SESSION_ID, body(OWNER, "approved"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(chatService).answerConfirmation(SESSION_ID, CONF_ID, Decision.APPROVED, OWNER);
    }

    @Test
    @DisplayName("(d/f) unknown / unbound confirmation: ChatService throws IllegalArgumentException → 410 GONE")
    void unknownConfirmation_410() {
        when(sessionService.getSession(SESSION_ID)).thenReturn(sessionOwnedBy(OWNER));
        doThrow(new IllegalArgumentException("unknown confirmation"))
                .when(chatService).answerConfirmation(eq(SESSION_ID), eq(CONF_ID), any(), eq(OWNER));

        ResponseEntity<?> resp = controller.confirm(SESSION_ID, body(OWNER, "approved"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.GONE);
    }
}
