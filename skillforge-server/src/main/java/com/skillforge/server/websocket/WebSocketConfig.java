package com.skillforge.server.websocket;

import com.skillforge.server.config.WsAuthHandshakeInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler chatWebSocketHandler;
    private final UserWebSocketHandler userWebSocketHandler;
    private final WsAuthHandshakeInterceptor wsAuthHandshakeInterceptor;

    public WebSocketConfig(ChatWebSocketHandler chatWebSocketHandler,
                           UserWebSocketHandler userWebSocketHandler,
                           WsAuthHandshakeInterceptor wsAuthHandshakeInterceptor) {
        this.chatWebSocketHandler = chatWebSocketHandler;
        this.userWebSocketHandler = userWebSocketHandler;
        this.wsAuthHandshakeInterceptor = wsAuthHandshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Use ant pattern "*" (single segment) so the sessionId can be extracted from URI path
        registry.addHandler(chatWebSocketHandler, "/ws/chat/*")
                .addInterceptors(wsAuthHandshakeInterceptor)
                .setAllowedOriginPatterns("*");
        // Per-user channel: one connection per user, receives lightweight events
        // for all of that user's sessions (runtimeStatus / title / messageCount / created / deleted).
        registry.addHandler(userWebSocketHandler, "/ws/users/*")
                .addInterceptors(wsAuthHandshakeInterceptor)
                .setAllowedOriginPatterns("*");
    }
}
