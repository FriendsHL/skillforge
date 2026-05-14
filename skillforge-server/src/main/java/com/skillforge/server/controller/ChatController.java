package com.skillforge.server.controller;

import com.skillforge.core.engine.CancellationRegistry;
import com.skillforge.core.engine.PendingAskRegistry;
import com.skillforge.core.engine.confirm.Decision;
import com.skillforge.core.engine.confirm.PendingConfirmationRegistry;
import com.skillforge.server.dto.ChatRequest;
import com.skillforge.server.dto.SessionCompactionCheckpointDto;
import com.skillforge.server.dto.CreateSessionRequest;
import com.skillforge.server.dto.SessionMessageDto;
import com.skillforge.server.dto.ContextBreakdownDto;
import com.skillforge.server.entity.ChannelConversationEntity;
import com.skillforge.server.entity.CompactionEventEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.dto.SessionReplayDto;
import com.skillforge.server.repository.ChannelConversationRepository;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.ChatAttachmentEntity;
import com.skillforge.server.exception.AgentNotFoundException;
import com.skillforge.server.service.AgentService;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.service.ChatAttachmentService;
import com.skillforge.server.service.CompactionService;
import com.skillforge.server.service.ContextBreakdownService;
import com.skillforge.server.service.ReplayService;
import com.skillforge.server.service.SessionService;
import com.skillforge.server.subagent.SubAgentRegistry;
import com.skillforge.server.subagent.SubAgentRegistry.SubAgentRun;
import com.skillforge.server.repository.ChatAttachmentRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    /** 默认渠道：没有 channel conversation 绑定的 session 视为 web。 */
    private static final String DEFAULT_CHANNEL_PLATFORM = "web";

    /** V73 / OBS-COLUMNS: max page size for the admin chat-attachments query (hard cap). */
    private static final int ADMIN_ATTACHMENTS_MAX_LIMIT = 500;

    private final ChatService chatService;
    private final ChatAttachmentService chatAttachmentService;
    private final ChatAttachmentRepository chatAttachmentRepository;
    private final SessionService sessionService;
    private final AgentService agentService;
    private final LlmProperties llmProperties;
    private final PendingAskRegistry pendingAskRegistry;
    private final PendingConfirmationRegistry pendingConfirmationRegistry;
    private final SubAgentRegistry subAgentRegistry;
    private final CancellationRegistry cancellationRegistry;
    private final CompactionService compactionService;
    private final ReplayService replayService;
    private final ChannelConversationRepository channelConversationRepository;
    private final ContextBreakdownService contextBreakdownService;

    public ChatController(ChatService chatService,
                          ChatAttachmentService chatAttachmentService,
                          ChatAttachmentRepository chatAttachmentRepository,
                          SessionService sessionService,
                          AgentService agentService,
                          LlmProperties llmProperties,
                          PendingAskRegistry pendingAskRegistry,
                          PendingConfirmationRegistry pendingConfirmationRegistry,
                          SubAgentRegistry subAgentRegistry,
                          CancellationRegistry cancellationRegistry,
                          CompactionService compactionService,
                          ReplayService replayService,
                          ChannelConversationRepository channelConversationRepository,
                          ContextBreakdownService contextBreakdownService) {
        this.chatService = chatService;
        this.chatAttachmentService = chatAttachmentService;
        this.chatAttachmentRepository = chatAttachmentRepository;
        this.sessionService = sessionService;
        this.agentService = agentService;
        this.llmProperties = llmProperties;
        this.pendingAskRegistry = pendingAskRegistry;
        this.pendingConfirmationRegistry = pendingConfirmationRegistry;
        this.subAgentRegistry = subAgentRegistry;
        this.cancellationRegistry = cancellationRegistry;
        this.compactionService = compactionService;
        this.replayService = replayService;
        this.channelConversationRepository = channelConversationRepository;
        this.contextBreakdownService = contextBreakdownService;
    }

    /**
     * 为 session 列表批量注入 channelPlatform（"web"/"feishu"/...）。
     * active 行优先，没有 active 行才 fall back 到最近的 closed 行。
     */
    private void enrichChannelPlatform(List<SessionEntity> sessions) {
        if (sessions == null || sessions.isEmpty()) return;
        List<String> sessionIds = sessions.stream()
                .map(SessionEntity::getId)
                .filter(id -> id != null && !id.isEmpty())
                .collect(Collectors.toList());
        if (sessionIds.isEmpty()) {
            sessions.forEach(s -> s.setChannelPlatform(DEFAULT_CHANNEL_PLATFORM));
            return;
        }
        List<ChannelConversationEntity> convs =
                channelConversationRepository.findBySessionIdIn(sessionIds);
        Map<String, String> platformBySessionId = new HashMap<>();
        Map<String, Boolean> activeBySessionId = new HashMap<>();
        for (ChannelConversationEntity c : convs) {
            String sid = c.getSessionId();
            boolean isActive = c.getClosedAt() == null;
            // active 行优先；已记录 active 就不被 closed 行覆盖
            if (Boolean.TRUE.equals(activeBySessionId.get(sid))) continue;
            platformBySessionId.put(sid, c.getPlatform());
            if (isActive) activeBySessionId.put(sid, true);
        }
        for (SessionEntity s : sessions) {
            String p = platformBySessionId.getOrDefault(s.getId(), DEFAULT_CHANNEL_PLATFORM);
            s.setChannelPlatform(p);
        }
    }

    private void enrichChannelPlatform(SessionEntity session) {
        if (session == null) return;
        enrichChannelPlatform(Collections.singletonList(session));
    }

    /**
     * 内部: 校验 session 存在 + 属于 userId。
     * 返回状态码:
     *   - 200 OK + SessionEntity : session 存在且归属匹配
     *   - 400 Bad Request        : userId 缺失
     *   - 403 Forbidden          : session 存在但归属不匹配
     *   - 404 Not Found          : session 不存在
     */
    private ResponseEntity<SessionEntity> requireOwnedSession(String sessionId, Long userId) {
        if (userId == null) {
            return ResponseEntity.badRequest().build();
        }
        SessionEntity session;
        try {
            session = sessionService.getSession(sessionId);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
        if (session.getUserId() == null || !session.getUserId().equals(userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(session);
    }

    private Map<String, Object> toSessionMutationResponse(SessionEntity session) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", session.getId());
        out.put("userId", session.getUserId());
        out.put("agentId", session.getAgentId());
        out.put("title", session.getTitle());
        out.put("status", session.getStatus());
        out.put("runtimeStatus", session.getRuntimeStatus());
        out.put("messageCount", session.getMessageCount());
        out.put("parentSessionId", session.getParentSessionId());
        out.put("updatedAt", session.getUpdatedAt());
        return out;
    }

    /**
     * 发送用户消息。立即返回 202,实际结果通过 WebSocket 推送。
     */
    @PostMapping("/{sessionId}")
    public ResponseEntity<Map<String, Object>> sendMessage(@PathVariable String sessionId,
                                                            @RequestBody ChatRequest request) {
        ResponseEntity<SessionEntity> check = requireOwnedSession(sessionId, request.userId());
        if (!check.getStatusCode().is2xxSuccessful()) {
            return ResponseEntity.status(check.getStatusCode()).build();
        }
        boolean hasMessage = request.message() != null && !request.message().isBlank();
        boolean hasAttachments = request.attachmentIds() != null && !request.attachmentIds().isEmpty();
        if (!hasMessage && !hasAttachments) {
            return ResponseEntity.badRequest().body(Map.of("error", "message or attachmentIds required"));
        }
        try {
            chatService.chatAsync(sessionId, request.message(), request.userId(), request.attachmentIds());
        } catch (RejectedExecutionException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "Server is busy, please try again later");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body);
        } catch (IllegalArgumentException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(body);
        } catch (IllegalStateException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("status", "accepted");
        return ResponseEntity.accepted().body(body);
    }

    @PostMapping("/sessions/{sessionId}/attachments")
    public ResponseEntity<Map<String, Object>> uploadAttachment(@PathVariable String sessionId,
                                                               @RequestParam Long userId,
                                                               @RequestParam(value = "file", required = false) MultipartFile file) {
        // MULTIMODAL-MVP gate order (matters):
        //   1) session ownership (404/403/400) — never leak session existence
        //   2) agent.modelId is vision-capable (409 MAIN_MODEL_NOT_VISION_CAPABLE)
        //   3) file presence (400) — fail before AttachmentService.upload() opens a stream,
        //      so no orphan files land in the storage root when the gate fails.
        ResponseEntity<SessionEntity> check = requireOwnedSession(sessionId, userId);
        if (!check.getStatusCode().is2xxSuccessful()) {
            return ResponseEntity.status(check.getStatusCode()).build();
        }
        SessionEntity session = check.getBody();
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "file is required"));
        }
        // Wave 3 WORD-EXCEL: gate vision-capability ONLY for kinds that actually
        // need vision (image / pdf). word / excel / csv are text-extraction
        // paths and work fine on non-vision-capable agents. previewKind reads
        // only the leading bytes — no DB / disk side effects — so it's safe to
        // run before the upload-side magic-byte validation in upload(). When
        // previewKind returns null (unrecognized), fall through to upload() and
        // let it produce the canonical "Unsupported or unrecognized" rejection.
        String previewKind = chatAttachmentService.previewKind(file);
        if (previewKind == null || "image".equals(previewKind) || "pdf".equals(previewKind)) {
            ResponseEntity<Map<String, Object>> gate = requireVisionCapableModel(session);
            if (gate != null) {
                return gate;
            }
        }
        try {
            var attachment = chatAttachmentService.upload(sessionId, userId, file);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", attachment.getId());
            body.put("sessionId", attachment.getSessionId());
            body.put("kind", attachment.getKind());
            body.put("mimeType", attachment.getMimeType());
            body.put("filename", attachment.getFilename());
            body.put("sizeBytes", attachment.getSizeBytes());
            body.put("pageCount", attachment.getPageCount());
            body.put("status", attachment.getStatus());
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * MULTIMODAL-MVP Phase 2: stream raw bytes for an attachment so the chat UI
     * can render image thumbnails / PDF previews inline. Same ownership chain
     * as the upload endpoint — caller must own the session, and the attachment
     * must belong to the same session + user. 200 streams body with
     * Content-Type from the server-detected MIME and Cache-Control: private so
     * intermediaries don't cache. 404 covers both "missing" and "ownership
     * mismatch" intentionally so we don't leak existence to non-owners.
     *
     * <p>Auth flows through the project's standard Bearer token interceptor;
     * because {@code <img src>} tags cannot carry the Authorization header,
     * the FE fetches via axios (interceptor adds Bearer) then renders the
     * response as a blob URL — keeping the token out of URLs / logs.</p>
     */
    @GetMapping("/attachments/{attachmentId}/data")
    public ResponseEntity<byte[]> getAttachmentData(@PathVariable String attachmentId,
                                                    @RequestParam Long userId,
                                                    @RequestParam(required = false) String sessionId) {
        if (userId == null) {
            return ResponseEntity.badRequest().build();
        }
        ChatAttachmentEntity attachment = chatAttachmentService.findReadable(attachmentId, sessionId, userId);
        if (attachment == null) {
            return ResponseEntity.notFound().build();
        }
        if (sessionId != null) {
            // Defense-in-depth: caller passed a session hint, require they actually
            // own that session before we hand back bytes. requireOwnedSession
            // returns 403 on cross-user mismatch and 404 on missing.
            ResponseEntity<SessionEntity> ownership = requireOwnedSession(sessionId, userId);
            if (!ownership.getStatusCode().is2xxSuccessful()) {
                return ResponseEntity.status(ownership.getStatusCode()).build();
            }
        }
        byte[] bytes;
        try {
            bytes = chatAttachmentService.readBytes(attachment);
        } catch (IllegalStateException e) {
            return ResponseEntity.internalServerError().build();
        }
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        String mime = attachment.getMimeType();
        if (mime != null && !mime.isBlank()) {
            headers.setContentType(org.springframework.http.MediaType.parseMediaType(mime));
        } else {
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM);
        }
        headers.setContentLength(bytes.length);
        // Private + 1d — these blobs are user-scoped, never share across users
        // and rarely change in place (filename can mutate but bytes don't).
        headers.setCacheControl("private, max-age=86400");
        // Avoid Content-Disposition: attachment so browsers render inline. The
        // filename hint helps users who click "Save image as".
        headers.add("Content-Disposition",
                "inline; filename=\"" + attachment.getFilename().replace("\"", "_") + "\"");
        return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
    }

    /**
     * V73 / MULTIMODAL-OBSERVABILITY-COLUMNS: admin query endpoint to filter
     * the {@code t_chat_attachment} table by {@code error_code} /
     * {@code processing_mode} / {@code session_id}. Lets operators answer "why
     * did this PDF not extract?" / "show me everything in PDF_TEXT_EMPTY state
     * today" without grep-ing server logs.
     *
     * <p><b>RBAC follow-up</b>: this endpoint is currently gated only by the
     * project's userId convention — there is no admin-role check (the project
     * has no full RBAC model yet). Add admin-role enforcement when the broader
     * permissions story lands. For now this is acceptable because (a) only the
     * caller's userId appears in {@code Authorization: Bearer ...} responses
     * elsewhere — but this endpoint intentionally exposes other users' rows.
     * In dev / single-tenant deployments that is fine; in multi-tenant it MUST
     * be locked down.</p>
     *
     * <p>Wire field names use camelCase ({@code errorCode} / {@code errorMessage}
     * / {@code processingMode} / {@code extractedTextChars}) — see java.md
     * footgun #6 (FE-BE contract drift).</p>
     */
    @GetMapping("/admin/chat-attachments")
    public ResponseEntity<List<Map<String, Object>>> listAttachments(
            @RequestParam Long userId,
            @RequestParam(required = false) String errorCode,
            @RequestParam(required = false) String processingMode,
            @RequestParam(required = false) String sessionId,
            @RequestParam(defaultValue = "100") int limit) {
        if (userId == null) {
            return ResponseEntity.badRequest().build();
        }
        if (limit <= 0) {
            return ResponseEntity.badRequest().build();
        }
        // Clamp to hard cap rather than 400 — admin tool convenience.
        int effectiveLimit = Math.min(limit, ADMIN_ATTACHMENTS_MAX_LIMIT);
        // Normalize empty strings to null so the JPQL "IS NULL OR equals"
        // pattern treats "filter not provided" and "filter is blank" the same.
        String ec = (errorCode != null && !errorCode.isBlank()) ? errorCode : null;
        String pm = (processingMode != null && !processingMode.isBlank()) ? processingMode : null;
        String sid = (sessionId != null && !sessionId.isBlank()) ? sessionId : null;

        List<ChatAttachmentEntity> rows = chatAttachmentRepository.findByFilters(
                ec, pm, sid, PageRequest.of(0, effectiveLimit));

        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (ChatAttachmentEntity row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", row.getId());
            m.put("sessionId", row.getSessionId());
            m.put("userId", row.getUserId());
            m.put("kind", row.getKind());
            m.put("mimeType", row.getMimeType());
            m.put("filename", row.getFilename());
            m.put("sizeBytes", row.getSizeBytes());
            m.put("pageCount", row.getPageCount());
            m.put("status", row.getStatus());
            // V73 OBS fields
            m.put("processingMode", row.getProcessingMode());
            m.put("errorCode", row.getErrorCode());
            m.put("errorMessage", row.getErrorMessage());
            m.put("extractedTextChars", row.getExtractedTextChars());
            m.put("createdAt", row.getCreatedAt());
            m.put("boundAt", row.getBoundAt());
            out.add(m);
        }
        return ResponseEntity.ok(out);
    }

    /**
     * ATTACHMENT-CLEANUP (Wave1-B): admin manual trigger for the orphan-cleanup sweep.
     *
     * <p>Same userId convention as the {@code listAttachments} admin endpoint above —
     * the project does not have a full RBAC model yet, and {@code userId} is used
     * purely as a non-empty caller-identity check (NOT a role check). Add proper
     * admin-role enforcement when the broader permissions story lands; tracked
     * alongside the listAttachments follow-up.</p>
     *
     * <p>Effective URL: {@code POST /api/chat/admin/chat-attachments/cleanup}. Query
     * params:</p>
     * <ul>
     *   <li>{@code userId} — required, non-empty caller-identity check</li>
     *   <li>{@code thresholdHours} — default 24; override for tests / one-offs</li>
     *   <li>{@code dryRun} — default false; true → no deletes, only would-be counts</li>
     * </ul>
     *
     * <p>Response body mirrors {@link ChatAttachmentService.CleanupResult} plus
     * {@code dryRun} and {@code thresholdHours} echoes for caller-side logging.</p>
     */
    @PostMapping("/admin/chat-attachments/cleanup")
    public ResponseEntity<Map<String, Object>> runCleanup(
            @RequestParam Long userId,
            @RequestParam(defaultValue = "24") int thresholdHours,
            @RequestParam(defaultValue = "false") boolean dryRun) {
        if (userId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "userId is required"));
        }
        if (thresholdHours <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "thresholdHours must be > 0"));
        }
        ChatAttachmentService.CleanupResult result =
                chatAttachmentService.cleanupOrphans(thresholdHours, dryRun);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orphanRowsDeleted", result.orphanRowsDeleted());
        body.put("filesDeleted", result.filesDeleted());
        body.put("errors", result.errors());
        body.put("dryRun", dryRun);
        body.put("thresholdHours", thresholdHours);
        return ResponseEntity.ok(body);
    }

    /**
     * MULTIMODAL-MVP redesign (2026-05-14): BE must independently reject uploads
     * when the session agent's main {@code modelId} is not vision-capable, even
     * if the FE upload-button gate is bypassed (curl / replayed request / stale FE).
     *
     * <p>This is the simplified design: there is no separate "multimodal model"
     * field on the agent. The agent's single {@code modelId} must be in
     * {@link LlmProperties#supportsVision(String)} for uploads to be allowed.</p>
     *
     * @return null when the gate passes; a 409 ResponseEntity with body
     *         {@code {"code":"MAIN_MODEL_NOT_VISION_CAPABLE","error":...}}
     *         when blocked; 404 ResponseEntity when the agent lookup itself fails.
     */
    private ResponseEntity<Map<String, Object>> requireVisionCapableModel(SessionEntity session) {
        if (session == null || session.getAgentId() == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "code", "MAIN_MODEL_NOT_VISION_CAPABLE",
                    "error", "Session is not bound to an agent"));
        }
        AgentEntity agent;
        try {
            agent = agentService.getAgent(session.getAgentId());
        } catch (AgentNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "agent not found: " + session.getAgentId()));
        }
        String modelId = agent.getModelId();
        if (modelId == null || modelId.isBlank() || !llmProperties.supportsVision(modelId)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "code", "MAIN_MODEL_NOT_VISION_CAPABLE",
                    "error", "Agent model is not vision-capable: " + modelId));
        }
        return null;
    }

    /**
     * 取消正在运行的 loop。200 已取消, 409 当前无 loop 运行。
     */
    @PostMapping("/{sessionId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelChat(@PathVariable String sessionId,
                                                           @RequestParam Long userId) {
        ResponseEntity<SessionEntity> check = requireOwnedSession(sessionId, userId);
        if (!check.getStatusCode().is2xxSuccessful()) {
            return ResponseEntity.status(check.getStatusCode()).build();
        }
        boolean ok = cancellationRegistry.cancel(sessionId);
        // Additionally wake any pending install confirmation so the engine main thread
        // exits its latch immediately instead of waiting up to 30 min.
        try {
            pendingConfirmationRegistry.completeAllForSession(sessionId, Decision.DENIED);
        } catch (Exception ignored) {
        }
        if (!ok) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "No running loop for this session"));
        }
        return ResponseEntity.ok(Map.of("status", "cancelling"));
    }

    /**
     * 前端回答 install confirmation 卡片 (approve / deny)。
     * Body: { confirmationId: string, decision: "approved" | "denied", userId: number }
     */
    @PostMapping("/{sessionId}/confirmation")
    public ResponseEntity<Map<String, Object>> confirm(@PathVariable String sessionId,
                                                       @RequestBody Map<String, Object> body) {
        Object userIdObj = body.get("userId");
        Long userId = null;
        if (userIdObj instanceof Number) {
            userId = ((Number) userIdObj).longValue();
        } else if (userIdObj instanceof String) {
            try { userId = Long.parseLong((String) userIdObj); } catch (NumberFormatException ignored) {}
        }
        ResponseEntity<SessionEntity> check = requireOwnedSession(sessionId, userId);
        if (!check.getStatusCode().is2xxSuccessful()) {
            return ResponseEntity.status(check.getStatusCode()).build();
        }
        Object cid = body.get("confirmationId");
        Object dec = body.get("decision");
        if (cid == null || dec == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "confirmationId and decision required"));
        }
        Decision decision;
        try {
            decision = Decision.fromJson(dec.toString());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid decision: " + dec));
        }
        if (decision == Decision.TIMEOUT) {
            return ResponseEntity.badRequest().body(Map.of("error", "TIMEOUT is reserved for server"));
        }
        try {
            chatService.answerConfirmation(sessionId, cid.toString(), decision, userId);
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(Map.of("error", "confirmation has expired or does not exist"));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 前端回答 ask_user 问题。
     */
    @PostMapping("/{sessionId}/answer")
    public ResponseEntity<Map<String, Object>> answer(@PathVariable String sessionId,
                                                       @RequestBody Map<String, Object> body) {
        Object userIdObj = body.get("userId");
        Long userId = null;
        if (userIdObj instanceof Number) {
            userId = ((Number) userIdObj).longValue();
        } else if (userIdObj instanceof String) {
            try { userId = Long.parseLong((String) userIdObj); } catch (NumberFormatException ignored) {}
        }
        ResponseEntity<SessionEntity> check = requireOwnedSession(sessionId, userId);
        if (!check.getStatusCode().is2xxSuccessful()) {
            return ResponseEntity.status(check.getStatusCode()).build();
        }
        Object askIdObj = body.get("askId");
        Object answerObj = body.get("answer");
        String askId = askIdObj == null ? null : askIdObj.toString();
        String answer = answerObj == null ? null : answerObj.toString();
        if (askId == null || answer == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "askId and answer required"));
        }
        try {
            chatService.answerAsk(sessionId, askId, answer, userId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(Map.of("error", "ask has expired or does not exist"));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));
        }
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /**
     * 切换某个 Session 当前的 executionMode(不影响 Agent 默认)。
     */
    @PatchMapping("/sessions/{sessionId}/mode")
    public ResponseEntity<SessionEntity> setSessionMode(@PathVariable String sessionId,
                                                         @RequestParam(required = false) Long userId,
                                                         @RequestBody Map<String, String> body) {
        String mode = body.get("mode");
        if (mode == null || (!mode.equals("ask") && !mode.equals("auto"))) {
            return ResponseEntity.badRequest().build();
        }
        ResponseEntity<SessionEntity> check = requireOwnedSession(sessionId, userId);
        if (!check.getStatusCode().is2xxSuccessful()) {
            return ResponseEntity.status(check.getStatusCode()).build();
        }
        SessionEntity session = check.getBody();
        session.setExecutionMode(mode);
        return ResponseEntity.ok(sessionService.saveSession(session));
    }

    @PostMapping("/sessions")
    public ResponseEntity<SessionEntity> createSession(@RequestBody CreateSessionRequest request) {
        // Legacy compatibility: older FE flows may still send sourceScenarioId.
        // M3c analysis flows now use dedicated /api/eval/*/analyze endpoints.
        SessionEntity session = sessionService.createSession(
                request.userId(), request.agentId(), request.sourceScenarioId());
        return ResponseEntity.ok(session);
    }

    @GetMapping("/sessions")
    public ResponseEntity<List<SessionEntity>> listSessions(@RequestParam Long userId) {
        List<SessionEntity> sessions = sessionService.listUserSessions(userId);
        enrichChannelPlatform(sessions);
        return ResponseEntity.ok(sessions);
    }

    @GetMapping("/sessions/{id}")
    public ResponseEntity<SessionEntity> getSession(@PathVariable String id,
                                                     @RequestParam(required = false) Long userId) {
        ResponseEntity<SessionEntity> resp = requireOwnedSession(id, userId);
        if (resp.getStatusCode().is2xxSuccessful()) {
            enrichChannelPlatform(resp.getBody());
        }
        return resp;
    }

    /**
     * Estimated breakdown of the tokens currently occupying this session's context window.
     * Values are approximate (TokenEstimator ±10%); used by the right-rail Context panel.
     */
    @GetMapping("/sessions/{id}/context-breakdown")
    public ResponseEntity<ContextBreakdownDto> getContextBreakdown(
            @PathVariable String id,
            @RequestParam(required = false) Long userId) {
        ResponseEntity<SessionEntity> check = requireOwnedSession(id, userId);
        if (!check.getStatusCode().is2xxSuccessful()) {
            return ResponseEntity.status(check.getStatusCode()).build();
        }
        return ResponseEntity.ok(contextBreakdownService.breakdown(check.getBody(), userId));
    }

    @GetMapping("/sessions/{id}/messages")
    public ResponseEntity<List<SessionMessageDto>> getSessionMessages(@PathVariable String id,
                                                                      @RequestParam(required = false) Long userId) {
        ResponseEntity<SessionEntity> check = requireOwnedSession(id, userId);
        if (!check.getStatusCode().is2xxSuccessful()) {
            return ResponseEntity.status(check.getStatusCode()).build();
        }
        List<SessionMessageDto> messages = sessionService.getFullHistoryDtos(id);
        return ResponseEntity.ok(messages);
    }

    /**
     * 列出某个父 session 下的所有子 session(SubAgent 派发的)。
     */
    @GetMapping("/sessions/{id}/children")
    public ResponseEntity<List<Map<String, Object>>> getChildSessions(@PathVariable String id,
                                                                       @RequestParam(required = false) Long userId) {
        ResponseEntity<SessionEntity> check = requireOwnedSession(id, userId);
        if (!check.getStatusCode().is2xxSuccessful()) {
            return ResponseEntity.status(check.getStatusCode()).build();
        }
        List<SessionEntity> children = sessionService.listChildSessions(id);
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (SessionEntity s : children) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("agentId", s.getAgentId());
            m.put("title", s.getTitle());
            m.put("depth", s.getDepth());
            m.put("runtimeStatus", s.getRuntimeStatus());
            m.put("messageCount", s.getMessageCount());
            m.put("createdAt", s.getCreatedAt());
            m.put("updatedAt", s.getUpdatedAt());
            m.put("subAgentRunId", s.getSubAgentRunId());
            out.add(m);
        }
        return ResponseEntity.ok(out);
    }

    /**
     * 用户主动触发全量压缩 (C1 user-manual)。
     * Body: {"level": "full", "reason": "optional"}。
     * 409 当 session runtimeStatus=running。
     */
    @PostMapping("/sessions/{id}/compact")
    public ResponseEntity<?> compactSession(@PathVariable String id,
                                             @RequestParam Long userId,
                                             @RequestBody Map<String, Object> body) {
        ResponseEntity<SessionEntity> check = requireOwnedSession(id, userId);
        if (!check.getStatusCode().is2xxSuccessful()) {
            return ResponseEntity.status(check.getStatusCode()).build();
        }
        String level = body.get("level") != null ? body.get("level").toString() : "full";
        if (!"full".equalsIgnoreCase(level)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Only level=full is supported for manual compaction"));
        }
        String reason = body.get("reason") != null ? body.get("reason").toString() : "user manual";
        try {
            CompactionEventEntity event = compactionService.compact(id, "full", "user-manual", reason);
            return ResponseEntity.ok(event);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 列出该 session 的压缩历史事件, 按 id 降序 (最新在前)。
     */
    @GetMapping("/sessions/{id}/compactions")
    public ResponseEntity<List<CompactionEventEntity>> listCompactions(@PathVariable String id,
                                                                        @RequestParam Long userId) {
        ResponseEntity<SessionEntity> check = requireOwnedSession(id, userId);
        if (!check.getStatusCode().is2xxSuccessful()) {
            return ResponseEntity.status(check.getStatusCode()).build();
        }
        return ResponseEntity.ok(compactionService.listEvents(id));
    }

    @GetMapping("/sessions/{id}/checkpoints")
    public ResponseEntity<List<SessionCompactionCheckpointDto>> listCheckpoints(@PathVariable String id,
                                                                                 @RequestParam Long userId,
                                                                                 @RequestParam(defaultValue = "20") int size) {
        ResponseEntity<SessionEntity> check = requireOwnedSession(id, userId);
        if (!check.getStatusCode().is2xxSuccessful()) {
            return ResponseEntity.status(check.getStatusCode()).build();
        }
        return ResponseEntity.ok(compactionService.listCheckpoints(id, size));
    }

    @GetMapping("/sessions/{id}/checkpoints/{checkpointId}")
    public ResponseEntity<?> getCheckpoint(@PathVariable String id,
                                           @PathVariable String checkpointId,
                                           @RequestParam Long userId) {
        ResponseEntity<SessionEntity> check = requireOwnedSession(id, userId);
        if (!check.getStatusCode().is2xxSuccessful()) {
            return ResponseEntity.status(check.getStatusCode()).build();
        }
        try {
            return ResponseEntity.ok(compactionService.getCheckpoint(id, checkpointId));
        } catch (CompactionService.CheckpointNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/sessions/{id}/checkpoints/{checkpointId}/branch")
    public ResponseEntity<?> branchFromCheckpoint(@PathVariable String id,
                                                  @PathVariable String checkpointId,
                                                  @RequestParam Long userId,
                                                  @RequestBody(required = false) Map<String, Object> body) {
        ResponseEntity<SessionEntity> check = requireOwnedSession(id, userId);
        if (!check.getStatusCode().is2xxSuccessful()) {
            return ResponseEntity.status(check.getStatusCode()).build();
        }
        String title = null;
        if (body != null && body.get("title") instanceof String t) {
            title = t;
        }
        try {
            SessionEntity branch = compactionService.createBranchFromCheckpoint(id, checkpointId, title);
            return ResponseEntity.status(HttpStatus.CREATED).body(toSessionMutationResponse(branch));
        } catch (CompactionService.CheckpointNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/sessions/{id}/checkpoints/{checkpointId}/restore")
    public ResponseEntity<?> restoreFromCheckpoint(@PathVariable String id,
                                                   @PathVariable String checkpointId,
                                                   @RequestParam Long userId) {
        ResponseEntity<SessionEntity> check = requireOwnedSession(id, userId);
        if (!check.getStatusCode().is2xxSuccessful()) {
            return ResponseEntity.status(check.getStatusCode()).build();
        }
        try {
            SessionEntity restored = compactionService.restoreFromCheckpoint(id, checkpointId);
            return ResponseEntity.ok(toSessionMutationResponse(restored));
        } catch (CompactionService.CheckpointNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/sessions/{id}/prune-tools")
    public ResponseEntity<?> pruneSessionToolOutputs(@PathVariable String id,
                                                     @RequestParam Long userId,
                                                     @RequestBody(required = false) Map<String, Object> body) {
        ResponseEntity<SessionEntity> check = requireOwnedSession(id, userId);
        if (!check.getStatusCode().is2xxSuccessful()) {
            return ResponseEntity.status(check.getStatusCode()).build();
        }
        SessionEntity session = check.getBody();
        if (session != null && "running".equals(session.getRuntimeStatus())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Cannot prune tool outputs while session is running"));
        }
        int limit = 200;
        if (body != null && body.get("limit") instanceof Number n) {
            limit = n.intValue();
        }
        if (limit <= 0 || limit > 5000) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "limit must be between 1 and 5000"));
        }
        try {
            int pruned = sessionService.pruneToolOutputs(id, limit);
            return ResponseEntity.ok(Map.of("sessionId", id, "prunedCount", pruned, "limit", limit));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Session Replay：返回结构化的 turn → iteration → tool call 时间线。
     */
    @GetMapping("/sessions/{id}/replay")
    public ResponseEntity<SessionReplayDto> getSessionReplay(@PathVariable String id,
                                                              @RequestParam(required = false) Long userId) {
        ResponseEntity<SessionEntity> check = requireOwnedSession(id, userId);
        if (!check.getStatusCode().is2xxSuccessful()) {
            return ResponseEntity.status(check.getStatusCode()).build();
        }
        SessionReplayDto replay = replayService.buildReplay(id);
        return ResponseEntity.ok(replay);
    }

    /**
     * 列出某个父 session 派发过的所有 SubAgent run(来自 SubAgentRegistry)。
     */
    @GetMapping("/sessions/{id}/subagent-runs")
    public ResponseEntity<List<Map<String, Object>>> getSubAgentRuns(@PathVariable String id,
                                                                      @RequestParam(required = false) Long userId) {
        ResponseEntity<SessionEntity> check = requireOwnedSession(id, userId);
        if (!check.getStatusCode().is2xxSuccessful()) {
            return ResponseEntity.status(check.getStatusCode()).build();
        }
        List<SubAgentRun> runs = subAgentRegistry.listRunsForParent(id);
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (SubAgentRun r : runs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("runId", r.runId);
            m.put("childSessionId", r.childSessionId);
            m.put("childAgentId", r.childAgentId);
            m.put("childAgentName", r.childAgentName);
            m.put("task", r.task);
            m.put("status", r.status);
            m.put("finalMessage", r.finalMessage);
            m.put("spawnedAt", r.spawnedAt);
            m.put("completedAt", r.completedAt);
            out.add(m);
        }
        return ResponseEntity.ok(out);
    }

    /**
     * 单删 session。running 状态返回 400。
     */
    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<Map<String, Object>> deleteSession(@PathVariable String id,
                                                              @RequestParam(required = false) Long userId) {
        ResponseEntity<SessionEntity> check = requireOwnedSession(id, userId);
        if (!check.getStatusCode().is2xxSuccessful()) {
            return ResponseEntity.status(check.getStatusCode()).build();
        }
        try {
            sessionService.deleteSession(id);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deleted", 1);
        body.put("skipped", Collections.emptyList());
        return ResponseEntity.ok(body);
    }

    /**
     * 批量删除。running / 非本用户的 session 自动跳过，整体不报错。
     * Body: {"ids": ["id1", "id2"]}
     */
    @DeleteMapping("/sessions")
    public ResponseEntity<Map<String, Object>> deleteSessions(@RequestParam Long userId,
                                                               @RequestBody Map<String, Object> requestBody) {
        Object idsObj = requestBody.get("ids");
        if (!(idsObj instanceof List<?> rawList)) {
            return ResponseEntity.badRequest().body(Map.of("error", "ids must be a list"));
        }
        if (rawList.size() > 100) {
            return ResponseEntity.badRequest().body(Map.of("error", "Cannot delete more than 100 sessions at once"));
        }
        List<String> ids = new ArrayList<>(rawList.size());
        for (Object o : rawList) {
            if (o != null) {
                ids.add(o.toString());
            }
        }
        SessionService.DeleteResult result = sessionService.deleteSessions(ids, userId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deleted", result.deleted());
        body.put("skipped", result.skipped());
        return ResponseEntity.ok(body);
    }
}
