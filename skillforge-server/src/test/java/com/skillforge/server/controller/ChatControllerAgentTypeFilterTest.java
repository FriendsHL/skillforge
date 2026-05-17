package com.skillforge.server.controller;

import com.skillforge.core.engine.CancellationRegistry;
import com.skillforge.core.engine.PendingAskRegistry;
import com.skillforge.core.engine.confirm.PendingConfirmationRegistry;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.ChannelConversationRepository;
import com.skillforge.server.repository.ChatAttachmentRepository;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SYSTEM-AGENT-TYPING Phase 2 visibility follow-up (2026-05-18) —
 * verifies {@code GET /api/chat/sessions?userId=&agentType=} routes correctly:
 * <ul>
 *   <li>{@code agentType='system'} → {@code sessionService.listSessionsByAgentType("system")} (no userId filter)</li>
 *   <li>{@code agentType='user'} or omitted → legacy {@code listUserSessions(userId)} (userId-scoped)</li>
 * </ul>
 *
 * <p>This is the BE wiring half of Phase B; FE tests cover the dashboard side.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatController GET /sessions agentType filter")
class ChatControllerAgentTypeFilterTest {

    private static final Long USER_ID = 1L;

    @Mock private ChatService chatService;
    @Mock private ChatAttachmentService chatAttachmentService;
    @Mock private ChatAttachmentRepository chatAttachmentRepository;
    @Mock private SessionService sessionService;
    @Mock private AgentService agentService;
    @Mock private LlmProperties llmProperties;
    @Mock private PendingAskRegistry pendingAskRegistry;
    @Mock private PendingConfirmationRegistry pendingConfirmationRegistry;
    @Mock private SubAgentRegistry subAgentRegistry;
    @Mock private CancellationRegistry cancellationRegistry;
    @Mock private CompactionService compactionService;
    @Mock private ReplayService replayService;
    @Mock private ChannelConversationRepository channelConversationRepository;
    @Mock private ContextBreakdownService contextBreakdownService;

    private ChatController controller;

    @BeforeEach
    void setUp() {
        controller = new ChatController(
                chatService, chatAttachmentService, chatAttachmentRepository,
                sessionService, agentService, llmProperties,
                pendingAskRegistry, pendingConfirmationRegistry, subAgentRegistry,
                cancellationRegistry, compactionService, replayService,
                channelConversationRepository, contextBreakdownService);
    }

    private SessionEntity stubSession(Long userId, Long agentId) {
        SessionEntity s = new SessionEntity();
        s.setId(UUID.randomUUID().toString());
        s.setUserId(userId);
        s.setAgentId(agentId);
        s.setTitle("agentType-filter-test");
        return s;
    }

    @Test
    @DisplayName("agentType='system' → listSessionsByAgentType('system'), never listUserSessions")
    void listSessions_system_routesToJoinPath() {
        SessionEntity sys1 = stubSession(0L, 200L);
        SessionEntity sys2 = stubSession(0L, 201L);
        when(sessionService.listSessionsByAgentType("system"))
                .thenReturn(List.of(sys1, sys2));
        // No channels for these sessions — enrichChannelPlatform fallback path.
        when(channelConversationRepository.findBySessionIdIn(anyList()))
                .thenReturn(Collections.emptyList());

        ResponseEntity<List<SessionEntity>> resp = controller.listSessions(USER_ID, "system");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsExactly(sys1, sys2);
        verify(sessionService).listSessionsByAgentType("system");
        verify(sessionService, never()).listUserSessions(USER_ID);
    }

    @Test
    @DisplayName("agentType='user' → legacy listUserSessions(userId), JOIN path untouched")
    void listSessions_user_routesToLegacyPath() {
        SessionEntity u1 = stubSession(USER_ID, 10L);
        when(sessionService.listUserSessions(USER_ID)).thenReturn(List.of(u1));
        when(channelConversationRepository.findBySessionIdIn(anyList()))
                .thenReturn(Collections.emptyList());

        ResponseEntity<List<SessionEntity>> resp = controller.listSessions(USER_ID, "user");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsExactly(u1);
        verify(sessionService).listUserSessions(USER_ID);
        verify(sessionService, never()).listSessionsByAgentType(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("agentType omitted (null) → legacy listUserSessions(userId)")
    void listSessions_nullAgentType_routesToLegacyPath() {
        SessionEntity u1 = stubSession(USER_ID, 10L);
        when(sessionService.listUserSessions(USER_ID)).thenReturn(List.of(u1));
        when(channelConversationRepository.findBySessionIdIn(anyList()))
                .thenReturn(Collections.emptyList());

        ResponseEntity<List<SessionEntity>> resp = controller.listSessions(USER_ID, null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsExactly(u1);
        verify(sessionService).listUserSessions(USER_ID);
        verify(sessionService, never()).listSessionsByAgentType(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("agentType unknown value (e.g. 'weird') → legacy path (safe default, never throws)")
    void listSessions_unknownAgentType_fallsBackToLegacy() {
        SessionEntity u1 = stubSession(USER_ID, 10L);
        when(sessionService.listUserSessions(USER_ID)).thenReturn(List.of(u1));
        when(channelConversationRepository.findBySessionIdIn(anyList()))
                .thenReturn(Collections.emptyList());

        ResponseEntity<List<SessionEntity>> resp = controller.listSessions(USER_ID, "weird");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(sessionService).listUserSessions(USER_ID);
        verify(sessionService, never()).listSessionsByAgentType(org.mockito.ArgumentMatchers.anyString());
    }
}
