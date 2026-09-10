package com.skillforge.server.controller;

import com.skillforge.server.config.AuthInterceptor;
import com.skillforge.server.config.PlatformAccessPrincipal;
import com.skillforge.server.config.SessionHistoryProperties;
import com.skillforge.server.session.UnknownOutcomeResolutionAck;
import com.skillforge.server.session.UnknownOutcomeResolutionActor;
import com.skillforge.server.session.UnknownOutcomeResolutionException;
import com.skillforge.server.session.UnknownOutcomeResolutionRequest;
import com.skillforge.server.session.UnknownOutcomePostActionOrchestrator;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Trusted control-plane endpoint for acknowledging an uncertain Tool outcome. */
@RestController
@RequestMapping("/api/sessions")
public class SessionToolAttemptResolutionController {

    private final SessionHistoryProperties properties;
    private final UnknownOutcomePostActionOrchestrator postActionOrchestrator;

    public SessionToolAttemptResolutionController(
            SessionHistoryProperties properties,
            UnknownOutcomePostActionOrchestrator postActionOrchestrator) {
        this.properties = properties;
        this.postActionOrchestrator = postActionOrchestrator;
    }

    @PostMapping("/{sessionId}/tool-attempts/{attemptId}/resolve-unknown")
    public ResponseEntity<?> resolveUnknown(
            @PathVariable String sessionId,
            @PathVariable long attemptId,
            @RequestBody UnknownOutcomeResolutionRequest command,
            HttpServletRequest request) {
        if (!properties.isEnabled()) {
            return ResponseEntity.notFound().build();
        }
        Object rawPrincipal = request != null
                ? request.getAttribute(AuthInterceptor.PRINCIPAL_ATTRIBUTE)
                : null;
        if (!(rawPrincipal instanceof PlatformAccessPrincipal principal)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "Trusted platform authority is required"));
        }
        UnknownOutcomeResolutionActor actor =
                UnknownOutcomeResolutionActor.authenticated(
                        principal.actorId(),
                        principal.explicitSessionPermissions());
        try {
            UnknownOutcomeResolutionAck acknowledgement = postActionOrchestrator.resolve(
                    sessionId, attemptId, actor, command);
            return ResponseEntity.ok(acknowledgement);
        } catch (UnknownOutcomeResolutionException failure) {
            HttpStatus status = failure.code()
                    == UnknownOutcomeResolutionException.Code.PERSISTENCE_FAILED
                    ? HttpStatus.SERVICE_UNAVAILABLE
                    : HttpStatus.CONFLICT;
            return ResponseEntity.status(status).body(Map.of(
                    "error", failure.getMessage()));
        }
    }
}
