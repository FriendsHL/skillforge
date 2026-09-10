package com.skillforge.server.controller;

import com.skillforge.server.config.AuthInterceptor;
import com.skillforge.server.config.PlatformAccessPrincipal;
import com.skillforge.server.config.SessionHistoryProperties;
import com.skillforge.server.session.UnknownOutcomeResolutionDiscoveryService;
import com.skillforge.server.session.UnknownOutcomeResolutionActor;
import com.skillforge.server.session.UnknownOutcomeResolutionException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Trusted control-plane discovery for the exact unknown outcome blocking a Session. */
@RestController
@RequestMapping("/api/sessions")
public class SessionToolAttemptDiscoveryController {

    private final SessionHistoryProperties properties;
    private final UnknownOutcomeResolutionDiscoveryService discoveryService;

    public SessionToolAttemptDiscoveryController(
            SessionHistoryProperties properties,
            UnknownOutcomeResolutionDiscoveryService discoveryService) {
        this.properties = properties;
        this.discoveryService = discoveryService;
    }

    @GetMapping("/{sessionId}/tool-attempts/unknown-outcome")
    public ResponseEntity<?> findUnknownOutcome(
            @PathVariable String sessionId, HttpServletRequest request) {
        if (!properties.isEnabled()) return ResponseEntity.notFound().build();
        Object rawPrincipal = request != null
                ? request.getAttribute(AuthInterceptor.PRINCIPAL_ATTRIBUTE)
                : null;
        if (!(rawPrincipal instanceof PlatformAccessPrincipal principal)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "Trusted platform authority is required"));
        }
        try {
            UnknownOutcomeResolutionActor actor =
                    UnknownOutcomeResolutionActor.authenticated(
                            principal.actorId(), principal.explicitSessionPermissions());
            return discoveryService.find(sessionId, actor)
                    .<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.noContent().build());
        } catch (UnknownOutcomeResolutionException unavailable) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "Unknown outcome resolution is not available"));
        }
    }
}
