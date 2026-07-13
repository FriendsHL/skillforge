package com.skillforge.server.mobile;

import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.SessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.net.URI;
import java.util.HashMap;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("MobileChatWebSocketAuthInterceptor")
class MobileChatWebSocketAuthInterceptorTest {

    @Test
    @DisplayName("allows valid mobile token for an owned session")
    void beforeHandshake_allowsValidTokenForOwnedSession() {
        MobileDeviceService deviceService = mock(MobileDeviceService.class);
        SessionRepository sessionRepository = mock(SessionRepository.class);
        MobileDevicePrincipal principal = new MobileDevicePrincipal(
                UUID.randomUUID(), 1L, "iPhone", Set.of("chat:read", "chat:write"));
        when(deviceService.authenticate("device-token")).thenReturn(Optional.of(principal));
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(session("session-1", 1L)));
        MobileChatWebSocketAuthInterceptor interceptor =
                new MobileChatWebSocketAuthInterceptor(deviceService, sessionRepository);
        ServerHttpRequest request = request("http://localhost/ws/mobile/chat/session-1", "device-token");
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        HashMap<String, Object> attributes = new HashMap<>();

        boolean allowed = interceptor.beforeHandshake(request, response, mock(WebSocketHandler.class), attributes);

        assertThat(allowed).isTrue();
        assertThat(attributes.get(MobileAuthInterceptor.PRINCIPAL_ATTRIBUTE)).isEqualTo(principal);
        assertThat(attributes.get("sessionId")).isEqualTo("session-1");
    }

    @Test
    @DisplayName("rejects missing or invalid mobile token")
    void beforeHandshake_rejectsMissingOrInvalidToken() {
        MobileDeviceService deviceService = mock(MobileDeviceService.class);
        SessionRepository sessionRepository = mock(SessionRepository.class);
        when(deviceService.authenticate("bad-token")).thenReturn(Optional.empty());
        MobileChatWebSocketAuthInterceptor interceptor =
                new MobileChatWebSocketAuthInterceptor(deviceService, sessionRepository);

        ServerHttpResponse missingResponse = mock(ServerHttpResponse.class);
        boolean missingAllowed = interceptor.beforeHandshake(
                request("http://localhost/ws/mobile/chat/session-1", null),
                missingResponse,
                mock(WebSocketHandler.class),
                new HashMap<>());
        assertThat(missingAllowed).isFalse();
        verify(missingResponse).setStatusCode(HttpStatus.UNAUTHORIZED);

        ServerHttpResponse invalidResponse = mock(ServerHttpResponse.class);
        boolean invalidAllowed = interceptor.beforeHandshake(
                request("http://localhost/ws/mobile/chat/session-1", "bad-token"),
                invalidResponse,
                mock(WebSocketHandler.class),
                new HashMap<>());
        assertThat(invalidAllowed).isFalse();
        verify(invalidResponse).setStatusCode(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(sessionRepository);
    }

    @Test
    @DisplayName("rejects sessions owned by a different user")
    void beforeHandshake_rejectsCrossUserSession() {
        MobileDeviceService deviceService = mock(MobileDeviceService.class);
        SessionRepository sessionRepository = mock(SessionRepository.class);
        MobileDevicePrincipal principal = new MobileDevicePrincipal(
                UUID.randomUUID(), 1L, "iPhone", Set.of("chat:read"));
        when(deviceService.authenticate("device-token")).thenReturn(Optional.of(principal));
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(session("session-1", 2L)));
        MobileChatWebSocketAuthInterceptor interceptor =
                new MobileChatWebSocketAuthInterceptor(deviceService, sessionRepository);
        ServerHttpResponse response = mock(ServerHttpResponse.class);

        boolean allowed = interceptor.beforeHandshake(
                request("http://localhost/ws/mobile/chat/session-1", "device-token"),
                response,
                mock(WebSocketHandler.class),
                new HashMap<>());

        assertThat(allowed).isFalse();
        verify(response).setStatusCode(HttpStatus.FORBIDDEN);
    }

    private ServerHttpRequest request(String uri, String token) {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create(uri));
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        when(request.getHeaders()).thenReturn(headers);
        return request;
    }

    private SessionEntity session(String id, Long userId) {
        SessionEntity session = new SessionEntity();
        session.setId(id);
        session.setUserId(userId);
        session.setAgentId(1L);
        return session;
    }
}
