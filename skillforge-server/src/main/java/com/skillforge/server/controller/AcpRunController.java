package com.skillforge.server.controller;

import com.skillforge.core.engine.confirm.Decision;
import com.skillforge.core.engine.confirm.PendingConfirmation;
import com.skillforge.core.engine.confirm.PendingConfirmationRegistry;
import com.skillforge.server.acp.AcpAgentRunner;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * P1a-2 TRIGGER ENDPOINT (ACP-EXTERNAL-AGENT) — demo/verify AC-1 + AC-3.
 *
 * <p>{@code POST /api/acp/runs {prompt, model?, userId}} runs one cc prompt as a
 * SkillForge sub-session (streamed live) OWNED by the caller and returns
 * {@code {subSessionId}} so the dashboard can open it. This is NOT the real
 * dispatch path: the {@code RunExternalAgent} tool (parent agent dispatches) +
 * channel result回投 are P1c. Kept minimal and clearly marked.
 *
 * <p><b>Auth convention:</b> mirrors {@code ChatController} — the bearer token
 * (validated by {@code AuthInterceptor}) is a single shared access token with no
 * per-user identity, so {@code userId} is caller-asserted in the request and the
 * confirmation endpoint enforces ownership against the sub-session's owner.
 */
@RestController
@RequestMapping("/api/acp/runs")
public class AcpRunController {

    private static final Logger log = LoggerFactory.getLogger(AcpRunController.class);

    private final AcpAgentRunner runner;
    private final PendingConfirmationRegistry pendingConfirmationRegistry;
    private final SessionService sessionService;

    public AcpRunController(AcpAgentRunner runner,
                           PendingConfirmationRegistry pendingConfirmationRegistry,
                           SessionService sessionService) {
        this.runner = runner;
        this.pendingConfirmationRegistry = pendingConfirmationRegistry;
        this.sessionService = sessionService;
    }

    @PostMapping
    public ResponseEntity<?> run(@RequestBody RunRequest request) {
        if (request == null || request.prompt() == null || request.prompt().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "prompt is required"));
        }
        if (request.userId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "userId is required"));
        }
        try {
            // BLOCKER-1a: the run sub-session is owned by the authenticated caller.
            String subSessionId = runner.run(request.prompt(), request.model(), request.userId());
            return ResponseEntity.ok(Map.of("subSessionId", subSessionId));
        } catch (Exception e) {
            // WARN-4: log the detail server-side, but return a GENERIC message — e.getMessage()
            // can carry the cc spawn command line (npx --yes <package>) / internal paths.
            log.error("ACP run trigger failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "ACP run failed"));
        }
    }

    /**
     * Answer a bridged cc permission request (AC-3). The dashboard renders the
     * EXISTING confirmation card (broadcast via {@code confirmation_required}) and
     * POSTs the decision here. This wakes the {@link PendingConfirmationRegistry}
     * latch directly — the ACP run sub-session is a RECORD (not engine-driven), so
     * it is NOT routed through {@code ChatService.answerConfirmation} (which resumes
     * an engine loop). The bridge's wait thread then maps the decision to the ACP
     * permission outcome.
     *
     * <p><b>BLOCKER-1b — two ownership gates (fail closed):</b>
     * <ol>
     *   <li>{@code requireOwnedSession(sessionId, userId)} — only the run's owner
     *       may answer (any valid token could otherwise approve another user's cc
     *       permission prompt → authorize an arbitrary cc action);</li>
     *   <li>the pending confirmation's bound sessionId must equal the path
     *       {@code sessionId} — a confirmationId from a different session is
     *       rejected so an attacker who knows their own sessionId cannot answer a
     *       cross-session confirmation.</li>
     * </ol>
     */
    @PostMapping("/{sessionId}/confirmation")
    public ResponseEntity<?> answerConfirmation(@PathVariable String sessionId,
                                                @RequestBody ConfirmRequest request) {
        if (request == null || request.confirmationId() == null || request.confirmationId().isBlank()
                || request.decision() == null || request.decision().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "confirmationId and decision required"));
        }
        Decision decision;
        try {
            decision = Decision.fromJson(request.decision());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid decision"));
        }
        // security-W1: TIMEOUT is server-produced (latch expiry); never accept it from the wire.
        if (decision == Decision.TIMEOUT) {
            return ResponseEntity.badRequest().body(Map.of("error", "TIMEOUT is reserved for server"));
        }

        // Gate 1 (BLOCKER-1b): session ownership. Returns 400/403/404 — never leaks existence.
        ResponseEntity<Void> ownership = requireOwnedSession(sessionId, request.userId());
        if (ownership != null) {
            return ResponseEntity.status(ownership.getStatusCode()).build();
        }

        // Gate 2 (BLOCKER-1b): the confirmation must belong to THIS session. Fail closed:
        // unknown or mismatched confirmationId → 404 (do not reveal which it was).
        PendingConfirmation pc = pendingConfirmationRegistry.peek(request.confirmationId());
        if (pc == null || !sessionId.equals(pc.sessionId())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "confirmation has expired or does not exist"));
        }

        boolean woke = pendingConfirmationRegistry.complete(request.confirmationId(), decision, null);
        if (!woke) {
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(Map.of("error", "confirmation has expired or does not exist"));
        }
        // security-W2: audit-log the approver identity + target + decision.
        log.info("ACP confirmation answered: userId={} sessionId={} confirmationId={} decision={}",
                request.userId(), sessionId, request.confirmationId(), decision);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /**
     * Mirror of {@code ChatController.requireOwnedSession}: validates the session
     * exists and is owned by {@code userId}. Returns {@code null} when ownership is
     * OK; otherwise a response carrying the status to surface (400 missing userId /
     * 403 not owner / 404 missing) WITHOUT a body, so session existence is not leaked.
     */
    private ResponseEntity<Void> requireOwnedSession(String sessionId, Long userId) {
        if (userId == null) {
            return ResponseEntity.badRequest().build();
        }
        SessionEntity session;
        try {
            session = sessionService.getSession(sessionId);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
        // SYSTEM sessions (userId=0) are operator-accessible — matches ChatController.
        if (session.getUserId() != null && session.getUserId() == 0L) {
            return null;
        }
        if (session.getUserId() == null || !session.getUserId().equals(userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return null;
    }

    /** Request body for the trigger. {@code model} is optional; {@code userId} required. */
    public record RunRequest(String prompt, String model, Long userId) {
    }

    /** Request body for the permission answer (AC-3). {@code userId} required for ownership. */
    public record ConfirmRequest(String confirmationId, String decision, Long userId) {
    }
}
