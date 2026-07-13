package com.skillforge.server.mobile;

import com.skillforge.core.engine.confirm.Decision;
import com.skillforge.core.engine.confirm.PendingConfirmationRegistry;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.server.dto.SessionMessageDto;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.exception.SessionNotFoundException;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.service.ChatAttachmentService;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;

@RestController
@RequestMapping("/api/mobile/client")
public class MobileChatController {

    private static final String SCOPE_CHAT_READ = "chat:read";
    private static final String SCOPE_CHAT_WRITE = "chat:write";
    private static final String SCOPE_CONFIRMATION_ANSWER = "confirmation:answer";
    private static final String SCOPE_ATTACHMENT_UPLOAD = "attachment:upload";

    private final SessionService sessionService;
    private final ChatService chatService;
    private final AgentRepository agentRepository;
    private final MobileAgentAccessService mobileAgentAccessService;
    private final ChatAttachmentService chatAttachmentService;
    private final LlmProperties llmProperties;
    private final PendingConfirmationRegistry pendingConfirmationRegistry;

    public MobileChatController(SessionService sessionService,
                                ChatService chatService,
                                AgentRepository agentRepository,
                                MobileAgentAccessService mobileAgentAccessService,
                                ChatAttachmentService chatAttachmentService,
                                LlmProperties llmProperties,
                                PendingConfirmationRegistry pendingConfirmationRegistry) {
        this.sessionService = sessionService;
        this.chatService = chatService;
        this.agentRepository = agentRepository;
        this.mobileAgentAccessService = mobileAgentAccessService;
        this.chatAttachmentService = chatAttachmentService;
        this.llmProperties = llmProperties;
        this.pendingConfirmationRegistry = pendingConfirmationRegistry;
    }

    @GetMapping("/sessions")
    public ResponseEntity<List<MobileSessionResponse>> listSessions(HttpServletRequest request) {
        MobileDevicePrincipal principal = requirePrincipal(request);
        requireScope(principal, SCOPE_CHAT_READ);
        return ResponseEntity.ok(sessionService.listUserSessions(principal.userId()).stream()
                .map(MobileSessionResponse::from)
                .toList());
    }

    @PostMapping("/sessions")
    public ResponseEntity<MobileSessionResponse> createSession(HttpServletRequest request,
                                                               @RequestBody(required = false) MobileCreateSessionRequest body) {
        MobileDevicePrincipal principal = requirePrincipal(request);
        requireScope(principal, SCOPE_CHAT_WRITE);
        Long agentId = resolveAgentId(body, principal.userId());
        return ResponseEntity.ok(MobileSessionResponse.from(sessionService.createSession(principal.userId(), agentId)));
    }

    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<MobileSessionResponse> getSession(@PathVariable String sessionId,
                                                            HttpServletRequest request) {
        MobileDevicePrincipal principal = requirePrincipal(request);
        requireScope(principal, SCOPE_CHAT_READ);
        return ResponseEntity.ok(MobileSessionResponse.from(requireMobileOwnedSession(sessionId, principal)));
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<List<SessionMessageDto>> getMessages(@PathVariable String sessionId,
                                                               HttpServletRequest request) {
        MobileDevicePrincipal principal = requirePrincipal(request);
        requireScope(principal, SCOPE_CHAT_READ);
        requireMobileOwnedSession(sessionId, principal);
        return ResponseEntity.ok(sessionService.getFullHistoryDtos(sessionId));
    }

    @GetMapping("/sessions/{sessionId}/pending-confirmations")
    public ResponseEntity<List<MobilePendingConfirmationResponse>> getPendingConfirmations(
            @PathVariable String sessionId,
            HttpServletRequest request) {
        MobileDevicePrincipal principal = requirePrincipal(request);
        requireScope(principal, SCOPE_CHAT_READ);
        requireMobileOwnedSession(sessionId, principal);
        return ResponseEntity.ok(pendingConfirmationRegistry.pendingForSession(sessionId).stream()
                .map(MobilePendingConfirmationResponse::from)
                .toList());
    }

    @PostMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<?> sendMessage(@PathVariable String sessionId,
                                         HttpServletRequest request,
                                         @RequestBody(required = false) MobileSendMessageRequest body) {
        MobileDevicePrincipal principal = requirePrincipal(request);
        requireScope(principal, SCOPE_CHAT_WRITE);
        requireMobileOwnedSession(sessionId, principal);

        String message = body != null ? body.message() : null;
        List<String> attachmentIds = body != null && body.attachmentIds() != null
                ? body.attachmentIds()
                : List.of();
        boolean hasMessage = message != null && !message.isBlank();
        boolean hasAttachments = !attachmentIds.isEmpty();
        if (!hasMessage && !hasAttachments) {
            return ResponseEntity.badRequest().body(Map.of("error", "message or attachmentIds required"));
        }

        try {
            chatService.chatAsync(sessionId, message, principal.userId(), attachmentIds);
        } catch (RejectedExecutionException e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "Server is busy, please try again later");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(error);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
        return ResponseEntity.accepted().body(new MobileAcceptedResponse(sessionId, "accepted"));
    }

    @PostMapping("/sessions/{sessionId}/answer")
    public ResponseEntity<?> answerAsk(@PathVariable String sessionId,
                                       HttpServletRequest request,
                                       @RequestBody(required = false) MobileAnswerRequest body) {
        MobileDevicePrincipal principal = requirePrincipal(request);
        requireScope(principal, SCOPE_CONFIRMATION_ANSWER);
        requireMobileOwnedSession(sessionId, principal);
        if (body == null || body.askId() == null || body.askId().isBlank()
                || body.answer() == null || body.answer().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "askId and answer required"));
        }
        try {
            chatService.answerAsk(sessionId, body.askId(), body.answer(), principal.userId());
            return ResponseEntity.ok(new MobileActionResponse("ok"));
        } catch (RejectedExecutionException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Server is busy, please try again later"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(Map.of("error", "ask has expired or does not exist"));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "ask cannot be answered in current state"));
        }
    }

    @PostMapping("/sessions/{sessionId}/confirmation")
    public ResponseEntity<?> answerConfirmation(@PathVariable String sessionId,
                                                HttpServletRequest request,
                                                @RequestBody(required = false) MobileConfirmationRequest body) {
        MobileDevicePrincipal principal = requirePrincipal(request);
        requireScope(principal, SCOPE_CONFIRMATION_ANSWER);
        requireMobileOwnedSession(sessionId, principal);
        if (body == null || body.confirmationId() == null || body.confirmationId().isBlank()
                || body.decision() == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "confirmationId and decision required"));
        }
        Decision decision;
        if ("approved".equals(body.decision())) {
            decision = Decision.APPROVED;
        } else if ("denied".equals(body.decision())) {
            decision = Decision.DENIED;
        } else {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid decision"));
        }
        try {
            chatService.answerConfirmation(
                    sessionId, body.confirmationId(), decision, principal.userId());
            return ResponseEntity.ok(new MobileActionResponse("ok"));
        } catch (RejectedExecutionException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Server is busy, please try again later"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(Map.of("error", "confirmation has expired or does not exist"));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "confirmation cannot be answered in current state"));
        }
    }

    @PostMapping(value = "/sessions/{sessionId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadAttachment(@PathVariable String sessionId,
                                              HttpServletRequest request,
                                              @RequestParam(value = "file", required = false) MultipartFile file) {
        MobileDevicePrincipal principal = requirePrincipal(request);
        requireScope(principal, SCOPE_ATTACHMENT_UPLOAD);
        SessionEntity session = requireMobileOwnedSession(sessionId, principal);
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "file is required"));
        }
        String previewKind = chatAttachmentService.previewKind(file);
        if (previewKind == null || "image".equals(previewKind) || "pdf".equals(previewKind)) {
            ResponseEntity<?> gate = requireVisionCapableModel(session);
            if (gate != null) {
                return gate;
            }
        }
        try {
            return ResponseEntity.ok(MobileAttachmentResponse.from(
                    chatAttachmentService.upload(sessionId, principal.userId(), file)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "attachment upload failed"));
        }
    }

    private ResponseEntity<?> requireVisionCapableModel(SessionEntity session) {
        if (session.getAgentId() == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "code", "MAIN_MODEL_NOT_VISION_CAPABLE",
                    "error", "Session is not bound to an agent"));
        }
        AgentEntity agent = agentRepository.findById(session.getAgentId()).orElse(null);
        if (agent == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "agent not found"));
        }
        if (!llmProperties.supportsVision(agent.getModelId())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "code", "MAIN_MODEL_NOT_VISION_CAPABLE",
                    "error", "Agent model is not vision-capable"));
        }
        return null;
    }

    private MobileDevicePrincipal requirePrincipal(HttpServletRequest request) {
        Object principal = request.getAttribute(MobileAuthInterceptor.PRINCIPAL_ATTRIBUTE);
        if (!(principal instanceof MobileDevicePrincipal mobilePrincipal)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return mobilePrincipal;
    }

    private void requireScope(MobileDevicePrincipal principal, String scope) {
        Set<String> scopes = principal.scopes() != null ? principal.scopes() : Set.of();
        if (!scopes.contains(scope)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    private Long resolveAgentId(MobileCreateSessionRequest body, Long userId) {
        if (body != null && body.agentId() != null) {
            return mobileAgentAccessService.requireSelectableAgent(body.agentId(), userId).getId();
        }
        return mobileAgentAccessService.requireSelectableDefaultAgent(userId).getId();
    }

    private SessionEntity requireMobileOwnedSession(String sessionId, MobileDevicePrincipal principal) {
        SessionEntity session;
        try {
            session = sessionService.getSession(sessionId);
        } catch (SessionNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (Long.valueOf(0L).equals(session.getUserId())) {
            return session;
        }
        if (session.getUserId() == null || !principal.userId().equals(session.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return session;
    }
}
