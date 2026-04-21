package com.skillforge.server.channel.router;

import com.skillforge.server.channel.registry.ChannelAdapterRegistry;
import com.skillforge.server.channel.spi.ChannelConfigDecrypted;
import com.skillforge.server.channel.spi.ChannelMessage;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.websocket.ChatWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
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
    private final ChannelAdapterRegistry adapterRegistry;

    @Autowired
    public ChannelSessionRouter(
            ChannelConversationResolver resolver,
            ChatService chatService,
            ChatWebSocketHandler chatWebSocketHandler,
            @Lazy ChannelAdapterRegistry adapterRegistry) {
        this.resolver = resolver;
        this.chatService = chatService;
        this.chatWebSocketHandler = chatWebSocketHandler;
        this.adapterRegistry = adapterRegistry;
    }

    @Async("channelRouterExecutor")
    public void routeAsync(ChannelMessage msg, ChannelConfigDecrypted config) {
        try {
            routeInternal(msg, config);
        } catch (Exception e) {
            log.error("Channel routing failed [{}] msg [{}]: {}",
                    msg.platform(), msg.platformMessageId(), e.getMessage(), e);
        }
    }

    private void routeInternal(ChannelMessage msg, ChannelConfigDecrypted config) {
        Long mappedUserId = resolver.resolveUser(msg);
        // resolveSession is @Transactional; retrying here (outside that transaction)
        // gives a fresh Hibernate session — required because a poisoned session from a
        // DataIntegrityViolationException cannot be reused within the same transaction.
        SessionRouteResult route;
        try {
            route = resolver.resolveSession(msg, config, mappedUserId);
        } catch (DataIntegrityViolationException race) {
            log.warn("Concurrent conversation creation hit unique constraint, retrying [{}] conv [{}]",
                    msg.platform(), msg.conversationId());
            route = resolver.resolveSession(msg, config, mappedUserId);
        }

        // Add typing-indicator reaction; reactionId is carried through to the listener
        // which removes it just before delivering the final reply.
        String ackReactionId = adapterRegistry.get(msg.platform())
                .map(adapter -> adapter.sendAck(msg, config))
                .orElse(null);

        // Register per-turn context before triggering the loop, so
        // sessionStatus("idle") finds the correct platformMessageId and ackReactionId.
        chatWebSocketHandler.registerChannelTurn(
                route.sessionId(), msg.platformMessageId(), ackReactionId);

        String text = msg.text() != null ? msg.text() : "";
        chatService.chatAsync(route.sessionId(), text, route.skillforgeUserId());
    }
}
