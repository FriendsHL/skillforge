package com.skillforge.server.websocket;

import com.fasterxml.jackson.databind.JsonNode; import com.fasterxml.jackson.databind.ObjectMapper; import org.junit.jupiter.api.Test; import org.mockito.ArgumentCaptor; import org.springframework.web.socket.TextMessage; import org.springframework.web.socket.WebSocketSession;
import java.net.URI; import java.util.List; import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat; import static org.mockito.Mockito.*;
class ChatWebSocketHandlerTaskSnapshotTest {
    @Test void emitsExactSnapshotEventNameAndEnvelope() throws Exception {
        ChatWebSocketHandler handler=new ChatWebSocketHandler(mock(UserWebSocketHandler.class),mock(com.skillforge.server.repository.CollabRunRepository.class),mock(com.skillforge.server.repository.SessionRepository.class),mock(org.springframework.context.ApplicationEventPublisher.class));
        WebSocketSession socket=mock(WebSocketSession.class); when(socket.getUri()).thenReturn(URI.create("ws://localhost/ws/chat/s1"));when(socket.isOpen()).thenReturn(true);handler.afterConnectionEstablished(socket);
        handler.sessionTasksSnapshot("s1",Map.of("sessionId","s1","summary",Map.of("total",1),"tasks",List.of(),"generatedAt","2026-08-05T00:00:00Z"));
        ArgumentCaptor<TextMessage> message=ArgumentCaptor.forClass(TextMessage.class);verify(socket).sendMessage(message.capture());JsonNode json=new ObjectMapper().readTree(message.getValue().getPayload());
        assertThat(json.get("type").asText()).isEqualTo("session_tasks_snapshot");assertThat(json.get("sessionId").asText()).isEqualTo("s1");assertThat(json.has("summary")).isTrue();assertThat(json.has("tasks")).isTrue();assertThat(json.has("generatedAt")).isTrue();
    }
}
