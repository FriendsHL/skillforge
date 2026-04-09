package com.skillforge.server.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /ws/users/{userId} 端点处理器。维护 userId -> WebSocketSession 集合。
 * 用于 Session 列表页一条连接接收该用户所有 session 的轻量事件
 * (runtimeStatus / title / messageCount / created / deleted),
 * 避免为 N 个 session 打开 N 条 WS 连接。
 */
@Component
public class UserWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(UserWebSocketHandler.class);

    private final Map<Long, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();
    // findAndRegisterModules() picks up jackson-datatype-jsr310 (already on classpath via
    // spring-boot-starter-web) so LocalDateTime in session payloads serializes cleanly.
    // disable WRITE_DATES_AS_TIMESTAMPS so the client gets ISO-8601 strings (new Date(...) parseable)
    // instead of numeric arrays like [2026,4,9,10,35,59].
    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = extractUserId(session);
        if (userId == null) {
            log.warn("User WS connection without userId, closing: {}", session.getUri());
            try {
                session.close(CloseStatus.BAD_DATA);
            } catch (IOException ignored) {
            }
            return;
        }
        sessions.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(session);
        log.info("User WS client connected: userId={}, total={}", userId, sessions.get(userId).size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long userId = extractUserId(session);
        if (userId != null) {
            Set<WebSocketSession> set = sessions.get(userId);
            if (set != null) {
                set.remove(session);
                if (set.isEmpty()) {
                    sessions.remove(userId);
                }
            }
        }
        log.info("User WS client disconnected: userId={}, status={}", userId, status);
    }

    private Long extractUserId(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null) return null;
        String path = uri.getPath();
        int idx = path.lastIndexOf('/');
        if (idx < 0 || idx == path.length() - 1) return null;
        try {
            return Long.parseLong(path.substring(idx + 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 把任意 payload 广播给某个 userId 的所有连接。线程安全。 */
    public void broadcast(Long userId, Map<String, Object> payload) {
        if (userId == null) return;
        Set<WebSocketSession> set = sessions.get(userId);
        if (set == null || set.isEmpty()) return;
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("Failed to serialize user WS payload: {}", e.getMessage());
            return;
        }
        TextMessage msg = new TextMessage(json);
        for (WebSocketSession s : set) {
            if (!s.isOpen()) continue;
            try {
                synchronized (s) {
                    s.sendMessage(msg);
                }
            } catch (Exception e) {
                log.warn("Failed to send user WS message to userId={}: {}", userId, e.getMessage());
            }
        }
    }
}
