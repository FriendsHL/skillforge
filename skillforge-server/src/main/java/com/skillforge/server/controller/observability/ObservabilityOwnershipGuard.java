package com.skillforge.server.controller.observability;

import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.service.SessionService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Plan §6.3 / §7.3 R3-W6 — ownership guard for observability endpoints.
 *
 * <p>Mirrors {@code ChatController.requireOwnedSession} but throws
 * {@link ResponseStatusException} so it can be reused across the four
 * observability controllers without duplicating the {@code ResponseEntity}
 * wrapping pattern.
 *
 * <p>Status semantics:
 * <ul>
 *   <li>400 — {@code userId} missing</li>
 *   <li>404 — session not found</li>
 *   <li>403 — session exists but does not belong to {@code userId}</li>
 *   <li>otherwise returns the loaded {@link SessionEntity}</li>
 * </ul>
 */
@Component
public class ObservabilityOwnershipGuard {

    private final SessionService sessionService;

    public ObservabilityOwnershipGuard(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    public SessionEntity requireOwned(String sessionId, Long userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId required");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId required");
        }
        SessionEntity session;
        try {
            session = sessionService.getSession(sessionId);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found");
        }
        if (session.getUserId() == null || !session.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Session not owned");
        }
        return session;
    }
}
