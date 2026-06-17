package com.skillforge.server.controller;

import com.skillforge.core.engine.CancellationRegistry;
import com.skillforge.core.engine.PendingAskRegistry;
import com.skillforge.core.engine.confirm.PendingConfirmationRegistry;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.server.entity.ChatAttachmentEntity;
import com.skillforge.server.channel.router.ChannelConversationResolver;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MULTIMODAL-MVP Phase 2: {@code GET /api/chat/attachments/{id}/data} streams
 * raw bytes for inline chat rendering. Ownership chain mirrors the upload path
 * (attachment → session → user). 404 covers missing + ownership mismatch
 * intentionally so we don't leak attachment existence to non-owners.
 */
@ExtendWith(MockitoExtension.class)
class ChatControllerAttachmentDataTest {

    private static final String ATT_ID = "att-1";
    private static final String SESSION_ID = "sess-1";
    private static final Long USER_ID = 42L;

    @Mock private ChatService chatService;
    @Mock private ChatAttachmentService chatAttachmentService;
    @Mock private SessionService sessionService;
    @Mock private AgentService agentService;
    @Mock private LlmProperties llmProperties;
    @Mock private PendingAskRegistry pendingAskRegistry;
    @Mock private PendingConfirmationRegistry pendingConfirmationRegistry;
    @Mock private SubAgentRegistry subAgentRegistry;
    @Mock private CancellationRegistry cancellationRegistry;
    @Mock private CompactionService compactionService;
    @Mock private ReplayService replayService;
    @Mock private ChannelConversationResolver channelConversationResolver;
    @Mock private ContextBreakdownService contextBreakdownService;

    private ChatController controller;

    @BeforeEach
    void setUp() {
        controller = new ChatController(
                chatService, chatAttachmentService,
                sessionService, agentService, llmProperties,
                pendingAskRegistry, pendingConfirmationRegistry, subAgentRegistry,
                cancellationRegistry, compactionService, replayService,
                channelConversationResolver, contextBreakdownService);
    }

    private ChatAttachmentEntity sampleAttachment() {
        ChatAttachmentEntity a = new ChatAttachmentEntity();
        a.setId(ATT_ID);
        a.setSessionId(SESSION_ID);
        a.setUserId(USER_ID);
        a.setKind("image");
        a.setMimeType("image/png");
        a.setFilename("hello.png");
        a.setSizeBytes(3L);
        a.setStoragePath("/tmp/fake-path-not-read.png");
        a.setStatus("bound");
        return a;
    }

    private SessionEntity ownedSession() {
        SessionEntity s = new SessionEntity();
        s.setId(SESSION_ID);
        s.setUserId(USER_ID);
        return s;
    }

    @Test
    @DisplayName("200 streams bytes with correct Content-Type when caller owns the session+attachment")
    void getAttachmentData_happy_streamsBytesAndContentType() {
        when(chatAttachmentService.findReadable(eq(ATT_ID), eq(SESSION_ID), eq(USER_ID)))
                .thenReturn(sampleAttachment());
        when(sessionService.getSession(SESSION_ID)).thenReturn(ownedSession());
        byte[] payload = new byte[]{(byte) 0x89, 'P', 'N', 'G'};
        when(chatAttachmentService.readBytes(any())).thenReturn(payload);

        ResponseEntity<byte[]> response = controller.getAttachmentData(ATT_ID, USER_ID, SESSION_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(payload);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(response.getHeaders().getCacheControl())
                .contains("private")
                .contains("max-age=86400");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(payload.length);
        assertThat(response.getHeaders().getFirst("Content-Disposition"))
                .contains("inline")
                .contains("hello.png");
    }

    @Test
    @DisplayName("404 when attachment is missing — does not call readBytes")
    void getAttachmentData_missing_returns404() {
        when(chatAttachmentService.findReadable(eq(ATT_ID), eq(SESSION_ID), eq(USER_ID)))
                .thenReturn(null);

        ResponseEntity<byte[]> response = controller.getAttachmentData(ATT_ID, USER_ID, SESSION_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(chatAttachmentService, never()).readBytes(any());
    }

    @Test
    @DisplayName("404 when attachment belongs to a different user (cross-user)")
    void getAttachmentData_crossUser_returns404() {
        // findReadable handles the user-mismatch internally and returns null —
        // we don't leak existence by responding 403; 404 is intentional.
        when(chatAttachmentService.findReadable(eq(ATT_ID), eq(SESSION_ID), eq(USER_ID)))
                .thenReturn(null);

        ResponseEntity<byte[]> response = controller.getAttachmentData(ATT_ID, USER_ID, SESSION_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("403 when sessionId given but session belongs to another user")
    void getAttachmentData_sessionNotOwned_returns403() {
        when(chatAttachmentService.findReadable(eq(ATT_ID), eq(SESSION_ID), eq(USER_ID)))
                .thenReturn(sampleAttachment());
        SessionEntity wrongOwner = new SessionEntity();
        wrongOwner.setId(SESSION_ID);
        wrongOwner.setUserId(999L);
        when(sessionService.getSession(SESSION_ID)).thenReturn(wrongOwner);

        ResponseEntity<byte[]> response = controller.getAttachmentData(ATT_ID, USER_ID, SESSION_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(chatAttachmentService, never()).readBytes(any());
    }

    @Test
    @DisplayName("400 when userId is missing")
    void getAttachmentData_missingUserId_returns400() {
        ResponseEntity<byte[]> response = controller.getAttachmentData(ATT_ID, null, SESSION_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(chatAttachmentService, never()).findReadable(any(), any(), any());
    }

    @Test
    @DisplayName("500 when bytes read fails")
    void getAttachmentData_readFails_returns500() {
        when(chatAttachmentService.findReadable(eq(ATT_ID), eq(SESSION_ID), eq(USER_ID)))
                .thenReturn(sampleAttachment());
        when(sessionService.getSession(SESSION_ID)).thenReturn(ownedSession());
        when(chatAttachmentService.readBytes(any()))
                .thenThrow(new IllegalStateException("disk read failed"));

        ResponseEntity<byte[]> response = controller.getAttachmentData(ATT_ID, USER_ID, SESSION_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
