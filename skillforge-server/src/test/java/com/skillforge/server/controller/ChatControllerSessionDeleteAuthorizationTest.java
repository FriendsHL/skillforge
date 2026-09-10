package com.skillforge.server.controller;

import com.skillforge.core.engine.CancellationRegistry;
import com.skillforge.core.engine.PendingAskRegistry;
import com.skillforge.core.engine.confirm.PendingConfirmationRegistry;
import com.skillforge.server.channel.router.ChannelConversationResolver;
import com.skillforge.server.config.AuthInterceptor;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.exception.SessionNotFoundException;
import com.skillforge.server.service.AgentService;
import com.skillforge.server.service.AuthService;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Characterizes the shared-token Session-delete guard used by the W-R6-2 whole-Session cascade
 * exception. The query {@code userId} is caller-asserted; these tests do not claim RBAC or a
 * server-authenticated user principal.
 */
@DisplayName("ChatController whole-Session delete authorization compatibility")
class ChatControllerSessionDeleteAuthorizationTest {

    private static final String SESSION_ID = "session-delete-1";
    private static final long OWNER_USER_ID = 7L;

    private SessionService sessionService;
    private ChatController controller;

    @BeforeEach
    void setUp() {
        sessionService = mock(SessionService.class);
        controller = new ChatController(
                mock(ChatService.class),
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
    @DisplayName("matching caller-asserted owner userId allows whole-Session delete")
    void deleteSession_ownerUserId_deletes() {
        when(sessionService.getSession(SESSION_ID)).thenReturn(sessionOwnedBy(OWNER_USER_ID));

        ResponseEntity<Map<String, Object>> response =
                controller.deleteSession(SESSION_ID, OWNER_USER_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("deleted", 1);
        verify(sessionService).deleteSession(SESSION_ID);
    }

    @Test
    @DisplayName("different caller-asserted userId is forbidden before whole-Session delete")
    void deleteSession_crossOwner_isForbiddenWithoutDelete() {
        when(sessionService.getSession(SESSION_ID)).thenReturn(sessionOwnedBy(OWNER_USER_ID));

        ResponseEntity<Map<String, Object>> response = controller.deleteSession(SESSION_ID, 99L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNull();
        verify(sessionService, never()).deleteSession(SESSION_ID);
    }

    @Test
    @DisplayName("missing Session is not found and never reaches whole-Session delete")
    void deleteSession_missing_isNotFoundWithoutDelete() {
        when(sessionService.getSession(SESSION_ID))
                .thenThrow(new SessionNotFoundException(SESSION_ID));

        ResponseEntity<Map<String, Object>> response =
                controller.deleteSession(SESSION_ID, OWNER_USER_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
        verify(sessionService, never()).deleteSession(SESSION_ID);
    }

    @Test
    @DisplayName("SYSTEM owner=0 remains an operator compatibility exception, not admin RBAC")
    void deleteSession_systemSession_allowsAuthenticatedOperatorCompatibility() {
        when(sessionService.getSession(SESSION_ID)).thenReturn(sessionOwnedBy(0L));

        ResponseEntity<Map<String, Object>> response =
                controller.deleteSession(SESSION_ID, OWNER_USER_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(sessionService).deleteSession(SESSION_ID);
    }

    @Test
    @DisplayName("missing Bearer token is rejected before Session lookup or delete")
    void deleteSession_missingBearer_isUnauthorizedBeforeController() throws Exception {
        AuthService authService = mock(AuthService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new AuthInterceptor(authService))
                .build();

        mvc.perform(delete("/api/chat/sessions/{id}", SESSION_ID)
                        .param("userId", Long.toString(OWNER_USER_ID)))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(authService);
        verifyNoInteractions(sessionService);
    }

    private static SessionEntity sessionOwnedBy(long userId) {
        SessionEntity session = new SessionEntity();
        session.setId(SESSION_ID);
        session.setUserId(userId);
        return session;
    }
}
