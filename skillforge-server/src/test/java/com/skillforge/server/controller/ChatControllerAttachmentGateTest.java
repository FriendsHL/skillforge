package com.skillforge.server.controller;

import com.skillforge.core.engine.CancellationRegistry;
import com.skillforge.core.engine.PendingAskRegistry;
import com.skillforge.core.engine.confirm.PendingConfirmationRegistry;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.ChatAttachmentEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.exception.AgentNotFoundException;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MULTIMODAL-MVP redesign (2026-05-14): upload endpoint must reject when
 * {@code !llmProperties.supportsVision(agent.modelId)} BEFORE
 * {@link ChatAttachmentService#upload} touches disk (no orphan files).
 *
 * <p>The previous design checked a separate {@code agent.multimodalModelId};
 * the new design uses the agent's single {@code modelId} so users only pick
 * one model (FE picker tags vision-capable options with a "多模态" chip).</p>
 */
@ExtendWith(MockitoExtension.class)
class ChatControllerAttachmentGateTest {

    private static final String SESSION_ID = "sess-1";
    private static final Long USER_ID = 42L;
    private static final Long AGENT_ID = 7L;
    private static final String VISION_MODEL = "xiaomi-mimo:mimo-v2.5";
    private static final String TEXT_MODEL = "xiaomi-mimo:mimo-v2.5-pro";

    @Mock private ChatService chatService;
    @Mock private ChatAttachmentService chatAttachmentService;
    @Mock private ChatAttachmentRepository chatAttachmentRepository;
    @Mock private SessionService sessionService;
    @Mock private AgentService agentService;
    @Mock private PendingAskRegistry pendingAskRegistry;
    @Mock private PendingConfirmationRegistry pendingConfirmationRegistry;
    @Mock private SubAgentRegistry subAgentRegistry;
    @Mock private CancellationRegistry cancellationRegistry;
    @Mock private CompactionService compactionService;
    @Mock private ReplayService replayService;
    @Mock private ChannelConversationRepository channelConversationRepository;
    @Mock private ContextBreakdownService contextBreakdownService;

    private LlmProperties llmProperties;
    private ChatController controller;

    @BeforeEach
    void setUp() {
        // Real LlmProperties with one provider whose vision-models list contains
        // `mimo-v2.5` (matches application.yml). This exercises the actual
        // `supportsVision(modelId)` lookup path rather than mocking it away.
        llmProperties = new LlmProperties();
        LlmProperties.ProviderConfig mimo = new LlmProperties.ProviderConfig();
        mimo.setType("openai");
        mimo.setModel("mimo-v2.5-pro");
        mimo.setModels(List.of("mimo-v2.5-pro", "mimo-v2.5"));
        mimo.setVisionModels(List.of("mimo-v2.5"));
        Map<String, LlmProperties.ProviderConfig> providers = new LinkedHashMap<>();
        providers.put("xiaomi-mimo", mimo);
        llmProperties.setProviders(providers);
        llmProperties.setDefaultProvider("xiaomi-mimo");

        controller = new ChatController(
                chatService,
                chatAttachmentService,
                chatAttachmentRepository,
                sessionService,
                agentService,
                llmProperties,
                pendingAskRegistry,
                pendingConfirmationRegistry,
                subAgentRegistry,
                cancellationRegistry,
                compactionService,
                replayService,
                channelConversationRepository,
                contextBreakdownService);
    }

    private MultipartFile pngFile() {
        return new MockMultipartFile("file", "screen.png", "image/png", new byte[]{1, 2, 3});
    }

    private SessionEntity ownedSession() {
        SessionEntity session = new SessionEntity();
        session.setId(SESSION_ID);
        session.setUserId(USER_ID);
        session.setAgentId(AGENT_ID);
        return session;
    }

    private AgentEntity agentWithModel(String modelId) {
        AgentEntity agent = new AgentEntity();
        agent.setId(AGENT_ID);
        agent.setModelId(modelId);
        return agent;
    }

    @Test
    @DisplayName("upload returns 409 MAIN_MODEL_NOT_VISION_CAPABLE when agent.modelId is text-only")
    void upload_nonVisionMainModel_returns409() {
        when(sessionService.getSession(SESSION_ID)).thenReturn(ownedSession());
        when(agentService.getAgent(AGENT_ID)).thenReturn(agentWithModel(TEXT_MODEL));

        ResponseEntity<Map<String, Object>> response =
                controller.uploadAttachment(SESSION_ID, USER_ID, pngFile());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody())
                .containsEntry("code", "MAIN_MODEL_NOT_VISION_CAPABLE");
        assertThat(response.getBody()).containsKey("error");
        // Iron Law: gate runs BEFORE bytes are accepted — no AttachmentService.upload call.
        verify(chatAttachmentService, never()).upload(any(), any(), any());
    }

    @Test
    @DisplayName("upload returns 409 when agent.modelId is null or blank")
    void upload_blankMainModel_returns409() {
        when(sessionService.getSession(SESSION_ID)).thenReturn(ownedSession());
        when(agentService.getAgent(AGENT_ID)).thenReturn(agentWithModel("   "));

        ResponseEntity<Map<String, Object>> response =
                controller.uploadAttachment(SESSION_ID, USER_ID, pngFile());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody())
                .containsEntry("code", "MAIN_MODEL_NOT_VISION_CAPABLE");
        verify(chatAttachmentService, never()).upload(any(), any(), any());
    }

    @Test
    @DisplayName("upload returns 200 when agent.modelId is vision-capable")
    void upload_visionCapableMainModel_returns200() {
        when(sessionService.getSession(SESSION_ID)).thenReturn(ownedSession());
        // model is `mimo-v2.5` — provider name strips "xiaomi-mimo:" prefix in the
        // controller's supportsVision() lookup which iterates all providers'
        // visionModels lists matching exact model string.
        when(agentService.getAgent(AGENT_ID)).thenReturn(agentWithModel("mimo-v2.5"));

        ChatAttachmentEntity stored = new ChatAttachmentEntity();
        stored.setId("att-1");
        stored.setSessionId(SESSION_ID);
        stored.setUserId(USER_ID);
        stored.setKind("image");
        stored.setMimeType("image/png");
        stored.setFilename("screen.png");
        stored.setSizeBytes(3L);
        stored.setStatus("uploaded");
        when(chatAttachmentService.upload(eq(SESSION_ID), eq(USER_ID), any(MultipartFile.class)))
                .thenReturn(stored);

        ResponseEntity<Map<String, Object>> response =
                controller.uploadAttachment(SESSION_ID, USER_ID, pngFile());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("id", "att-1")
                .containsEntry("kind", "image")
                .containsEntry("mimeType", "image/png")
                .containsEntry("filename", "screen.png");
        verify(chatAttachmentService).upload(eq(SESSION_ID), eq(USER_ID), any(MultipartFile.class));
    }

    @Test
    @DisplayName("upload returns 403 when session not owned by caller")
    void upload_sessionNotOwned_returns403() {
        SessionEntity otherUserSession = new SessionEntity();
        otherUserSession.setId(SESSION_ID);
        otherUserSession.setUserId(999L); // different user
        otherUserSession.setAgentId(AGENT_ID);
        when(sessionService.getSession(SESSION_ID)).thenReturn(otherUserSession);

        ResponseEntity<Map<String, Object>> response =
                controller.uploadAttachment(SESSION_ID, USER_ID, pngFile());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // 403 fires before the vision gate; agent lookup must not happen — that
        // would leak session existence to the wrong user.
        verify(agentService, never()).getAgent(any());
        verify(chatAttachmentService, never()).upload(any(), any(), any());
    }

    @Test
    @DisplayName("upload returns 400 when file is missing")
    void upload_missingFile_returns400() {
        when(sessionService.getSession(SESSION_ID)).thenReturn(ownedSession());
        when(agentService.getAgent(AGENT_ID)).thenReturn(agentWithModel(VISION_MODEL));

        ResponseEntity<Map<String, Object>> response =
                controller.uploadAttachment(SESSION_ID, USER_ID, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "file is required");
        verify(chatAttachmentService, never()).upload(any(), any(), any());
    }

    @Test
    @DisplayName("upload returns 404 when session's agent has been deleted")
    void upload_missingAgent_returns404() {
        when(sessionService.getSession(SESSION_ID)).thenReturn(ownedSession());
        when(agentService.getAgent(AGENT_ID)).thenThrow(new AgentNotFoundException(AGENT_ID));

        ResponseEntity<Map<String, Object>> response =
                controller.uploadAttachment(SESSION_ID, USER_ID, pngFile());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(chatAttachmentService, never()).upload(any(), any(), any());
    }
}
