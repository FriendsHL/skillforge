package com.skillforge.server.controller;

import com.skillforge.core.engine.CancellationRegistry;
import com.skillforge.core.engine.PendingAskRegistry;
import com.skillforge.core.engine.confirm.PendingConfirmationRegistry;
import com.skillforge.server.channel.router.ChannelConversationResolver;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.server.entity.ChatAttachmentEntity;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V73 / MULTIMODAL-OBSERVABILITY-COLUMNS: admin endpoint
 * {@code GET /api/chat/admin/chat-attachments?userId=&errorCode=&processingMode=&sessionId=&limit=}.
 *
 * <p>After the layering refactor the filter normalization + hard-cap clamp + JPQL
 * query live in {@link ChatAttachmentService#listAttachmentsByFilters} (covered by
 * {@code ChatAttachmentServiceListByFiltersTest}). This test covers the controller
 * wiring only: request validation (userId / limit), pass-through of raw filter +
 * limit args to the service, and the OBS-field response shape.</p>
 */
@ExtendWith(MockitoExtension.class)
class ChatControllerAttachmentsListTest {

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

    private ChatAttachmentEntity sample(String id, String mode, String errorCode,
                                        Integer chars, Instant createdAt) {
        ChatAttachmentEntity e = new ChatAttachmentEntity();
        e.setId(id);
        e.setSessionId("sess-" + id);
        e.setUserId(USER_ID);
        e.setKind("pdf");
        e.setMimeType("application/pdf");
        e.setFilename(id + ".pdf");
        e.setSizeBytes(2048L);
        e.setStoragePath("/tmp/" + id + ".pdf");
        e.setStatus("uploaded");
        e.setProcessingMode(mode);
        e.setErrorCode(errorCode);
        e.setExtractedTextChars(chars);
        e.setCreatedAt(createdAt);
        return e;
    }

    @Test
    @DisplayName("400 when userId is missing")
    void list_missingUserId_returns400() {
        ResponseEntity<List<Map<String, Object>>> resp =
                controller.listAttachments(null, null, null, null, 100);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(chatAttachmentService, never()).listAttachmentsByFilters(any(), any(), any(), any(Integer.class));
    }

    @Test
    @DisplayName("400 when limit is <= 0")
    void list_nonPositiveLimit_returns400() {
        ResponseEntity<List<Map<String, Object>>> resp =
                controller.listAttachments(USER_ID, null, null, null, 0);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(chatAttachmentService, never()).listAttachmentsByFilters(any(), any(), any(), any(Integer.class));
    }

    @Test
    @DisplayName("returns rows in service order with full OBS field payload")
    void list_returnsRowsWithObsFields() {
        // Service returns rows already in created_at DESC order (ORDER BY enforced
        // at the JPQL layer); the controller preserves that order in the response.
        Instant t0 = Instant.parse("2026-05-14T10:00:00Z");
        ChatAttachmentEntity row1 = sample("a", "PDF_TEXT_EMPTY",
                "PDF_TEXT_EMPTY_NEEDS_VISION", 0, t0.plusSeconds(30));
        ChatAttachmentEntity row2 = sample("b", "PDF_TEXT_TRUNCATED", null, 20_000, t0.plusSeconds(20));
        ChatAttachmentEntity row3 = sample("c", "IMAGE_BLOCK_INLINE", null, null, t0.plusSeconds(10));
        ChatAttachmentEntity row4 = sample("d", "PDF_TEXT", null, 1234, t0);

        when(chatAttachmentService.listAttachmentsByFilters(any(), any(), any(), eq(100)))
                .thenReturn(List.of(row1, row2, row3, row4));

        ResponseEntity<List<Map<String, Object>>> resp =
                controller.listAttachments(USER_ID, null, null, null, 100);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> body = resp.getBody();
        assertThat(body).hasSize(4);

        // Order preserved from service
        assertThat(body.get(0).get("id")).isEqualTo("a");
        assertThat(body.get(3).get("id")).isEqualTo("d");

        // OBS fields present on every row, camelCase wire keys
        Map<String, Object> first = body.get(0);
        assertThat(first).containsKeys(
                "id", "sessionId", "userId", "kind", "mimeType", "filename",
                "sizeBytes", "pageCount", "status",
                // V73 OBS fields:
                "processingMode", "errorCode", "errorMessage", "extractedTextChars",
                "createdAt", "boundAt");
        assertThat(first.get("processingMode")).isEqualTo("PDF_TEXT_EMPTY");
        assertThat(first.get("errorCode")).isEqualTo("PDF_TEXT_EMPTY_NEEDS_VISION");
        assertThat(first.get("extractedTextChars")).isEqualTo(0);

        // Row with no errorCode should still surface the camelCase key with null value
        Map<String, Object> second = body.get(1);
        assertThat(second).containsKey("errorCode");
        assertThat(second.get("errorCode")).isNull();
        assertThat(second.get("processingMode")).isEqualTo("PDF_TEXT_TRUNCATED");
        assertThat(second.get("extractedTextChars")).isEqualTo(20_000);
    }

    @Test
    @DisplayName("filter + limit args pass through to the service unchanged")
    void list_argsPassedThroughToService() {
        when(chatAttachmentService.listAttachmentsByFilters(
                eq("PDF_PARSE_FAILED"), eq("PDF_TEXT_EMPTY"), eq("sess-x"), eq(50)))
                .thenReturn(List.of());

        ResponseEntity<List<Map<String, Object>>> resp =
                controller.listAttachments(USER_ID, "PDF_PARSE_FAILED", "PDF_TEXT_EMPTY", "sess-x", 50);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isEmpty();

        verify(chatAttachmentService).listAttachmentsByFilters(
                eq("PDF_PARSE_FAILED"), eq("PDF_TEXT_EMPTY"), eq("sess-x"), eq(50));
    }
}
