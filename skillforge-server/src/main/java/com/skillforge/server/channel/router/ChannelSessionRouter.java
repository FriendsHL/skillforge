package com.skillforge.server.channel.router;

import com.skillforge.server.channel.spi.ChannelAdapter;
import com.skillforge.server.channel.spi.ChannelConfigDecrypted;
import com.skillforge.server.channel.spi.ChannelMessage;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.websocket.ChatWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Resolves (platform, conversation) → SkillForge session and dispatches to ChatService.
 * <p>
 * The transactional find-or-create lives on {@link ChannelConversationResolver} so that
 * {@code @Transactional} is applied via the Spring proxy. Calling it via {@code this.}
 * from the {@code @Async routeAsync} entrypoint would bypass the proxy and nullify the
 * PESSIMISTIC_WRITE lock that serializes concurrent "none exists → create" races.
 * <p>
 * B2-H2: {@link ChatWebSocketHandler#registerChannelTurn} is invoked on every turn
 * (not only at session creation) so each AgentLoop finish publishes the current turn's
 * platformMessageId.
 */
@Service
public class ChannelSessionRouter {

    private static final Logger log = LoggerFactory.getLogger(ChannelSessionRouter.class);

    private final ChannelConversationResolver resolver;
    private final ChatService chatService;
    private final ChatWebSocketHandler chatWebSocketHandler;

    public ChannelSessionRouter(
            ChannelConversationResolver resolver,
            ChatService chatService,
            ChatWebSocketHandler chatWebSocketHandler) {
        this.resolver = resolver;
        this.chatService = chatService;
        this.chatWebSocketHandler = chatWebSocketHandler;
    }

    @Async("channelRouterExecutor")
    public void routeAsync(ChannelMessage msg, ChannelAdapter adapter,
                           ChannelConfigDecrypted config) {
        try {
            SessionRouteResult route = resolver.resolveSession(msg, config);
            Long userId = resolver.resolveUser(msg);

            // Register per-turn context before triggering the loop, so
            // assistantStreamEnd finds the correct platformMessageId.
            chatWebSocketHandler.registerChannelTurn(
                    route.sessionId(), msg.platformMessageId());

            String text = msg.text() != null ? msg.text() : "";
            chatService.chatAsync(route.sessionId(), text, userId);
        } catch (Exception e) {
            log.error("Channel routing failed [{}] msg [{}]: {}",
                    msg.platform(), msg.platformMessageId(), e.getMessage(), e);
        }
    }
}
