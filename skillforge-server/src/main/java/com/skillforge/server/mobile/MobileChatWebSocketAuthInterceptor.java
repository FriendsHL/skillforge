package com.skillforge.server.mobile;

import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.SessionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class MobileChatWebSocketAuthInterceptor implements HandshakeInterceptor {

    static final String SESSION_ID_ATTRIBUTE = "sessionId";
    private static final String SCOPE_CHAT_READ = "chat:read";

    private final MobileDeviceService deviceService;
    private final SessionRepository sessionRepository;

    public MobileChatWebSocketAuthInterceptor(MobileDeviceService deviceService,
                                              SessionRepository sessionRepository) {
        this.deviceService = deviceService;
        this.sessionRepository = sessionRepository;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        URI uri = request.getURI();
        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        String rawToken = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring("Bearer ".length()).trim()
                : null;
        if (rawToken == null || rawToken.isBlank()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        Optional<MobileDevicePrincipal> principal = deviceService.authenticate(rawToken);
        if (principal.isEmpty()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        if (!hasScope(principal.get(), SCOPE_CHAT_READ)) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }

        String sessionId = extractSessionId(uri);
        if (sessionId == null || sessionId.isBlank()) {
            response.setStatusCode(HttpStatus.NOT_FOUND);
            return false;
        }

        Optional<SessionEntity> session = sessionRepository.findById(sessionId);
        if (session.isEmpty()) {
            response.setStatusCode(HttpStatus.NOT_FOUND);
            return false;
        }
        if (!isMobileOwnedSession(session.get(), principal.get())) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }

        attributes.put(MobileAuthInterceptor.PRINCIPAL_ATTRIBUTE, principal.get());
        attributes.put(SESSION_ID_ATTRIBUTE, sessionId);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
        // no-op
    }

    private boolean hasScope(MobileDevicePrincipal principal, String scope) {
        Set<String> scopes = principal.scopes() != null ? principal.scopes() : Set.of();
        return scopes.contains(scope);
    }

    private boolean isMobileOwnedSession(SessionEntity session, MobileDevicePrincipal principal) {
        if (Long.valueOf(0L).equals(session.getUserId())) {
            return true;
        }
        return session.getUserId() != null && session.getUserId().equals(principal.userId());
    }

    private String extractSessionId(URI uri) {
        String path = uri.getPath();
        if (path == null) {
            return null;
        }
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash < 0 || lastSlash == path.length() - 1) {
            return null;
        }
        return URLDecoder.decode(path.substring(lastSlash + 1), StandardCharsets.UTF_8);
    }

}
