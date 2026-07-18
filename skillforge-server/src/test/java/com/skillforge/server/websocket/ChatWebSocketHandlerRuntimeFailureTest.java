package com.skillforge.server.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatWebSocketHandlerRuntimeFailureTest {

    @Test
    void sessionStatus_emitsExactStructuredFailureFact() throws Exception {
        ChatWebSocketHandler handler = new ChatWebSocketHandler(
                mock(UserWebSocketHandler.class),
                mock(com.skillforge.server.repository.CollabRunRepository.class),
                mock(com.skillforge.server.repository.SessionRepository.class),
                mock(org.springframework.context.ApplicationEventPublisher.class));
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getUri()).thenReturn(URI.create("ws://localhost/ws/chat/session-1"));
        when(socket.isOpen()).thenReturn(true);
        handler.afterConnectionEstablished(socket);

        handler.sessionStatus("session-1", "error", "retryable", "The model connection timed out.",
                "network", "NETWORK_TIMEOUT", true, "none");

        ArgumentCaptor<TextMessage> message = ArgumentCaptor.forClass(TextMessage.class);
        verify(socket).sendMessage(message.capture());
        JsonNode payload = new ObjectMapper().readTree(message.getValue().getPayload());
        assertThat(payload.get("type").asText()).isEqualTo("session_status");
        assertThat(payload.get("sessionId").asText()).isEqualTo("session-1");
        assertThat(payload.get("status").asText()).isEqualTo("error");
        assertThat(payload.get("step").asText()).isEqualTo("retryable");
        assertThat(payload.get("error").asText()).isEqualTo("The model connection timed out.");
        assertThat(payload.get("failureSource").asText()).isEqualTo("network");
        assertThat(payload.get("failureCode").asText()).isEqualTo("NETWORK_TIMEOUT");
        assertThat(payload.get("retryable").asBoolean()).isTrue();
        assertThat(payload.get("sideEffects").asText()).isEqualTo("none");
    }
}
