package com.skillforge.server.controller;

import com.skillforge.server.acp.AcpAgentRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
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
 * per-user identity, so {@code userId} is caller-asserted in the request.
 *
 * <p><b>P1c-2 (Seam 2):</b> the separate ACP confirmation endpoint that used to
 * live here ({@code POST /api/acp/runs/{sessionId}/confirmation}) has been
 * UNIFIED into {@code ChatController POST /api/chat/{sessionId}/confirmation}
 * (→ {@code ChatService.answerConfirmation}), which discriminates the engine vs
 * ACP/cc path internally behind one ownership-gated door. The dashboard
 * confirmation card posts only to that single endpoint.
 */
@RestController
@RequestMapping("/api/acp/runs")
public class AcpRunController {

    private static final Logger log = LoggerFactory.getLogger(AcpRunController.class);

    private final AcpAgentRunner runner;

    public AcpRunController(AcpAgentRunner runner) {
        this.runner = runner;
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

    /** Request body for the trigger. {@code model} is optional; {@code userId} required. */
    public record RunRequest(String prompt, String model, Long userId) {
    }
}
