package com.skillforge.server.channel.listener;

import com.skillforge.server.channel.ChannelConfigService;
import com.skillforge.server.channel.delivery.ReplyDeliveryService;
import com.skillforge.server.channel.event.ChannelSessionOutputEvent;
import com.skillforge.server.channel.registry.ChannelAdapterRegistry;
import com.skillforge.server.channel.spi.ChannelAdapter;
import com.skillforge.server.channel.spi.ChannelConfigDecrypted;
import com.skillforge.server.channel.spi.ChannelReply;
import com.skillforge.server.entity.ChannelConversationEntity;
import com.skillforge.server.repository.ChannelConversationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class ChannelReplyEventListener {

    private static final Logger log = LoggerFactory.getLogger(ChannelReplyEventListener.class);

    private final ChannelConversationRepository conversationRepo;
    private final ReplyDeliveryService deliveryService;
    private final ChannelAdapterRegistry adapterRegistry;
    private final ChannelConfigService configService;

    public ChannelReplyEventListener(ChannelConversationRepository conversationRepo,
                                     ReplyDeliveryService deliveryService,
                                     ChannelAdapterRegistry adapterRegistry,
                                     ChannelConfigService configService) {
        this.conversationRepo = conversationRepo;
        this.deliveryService = deliveryService;
        this.adapterRegistry = adapterRegistry;
        this.configService = configService;
    }

    @Async("channelRouterExecutor")
    @EventListener
    public void onSessionOutput(ChannelSessionOutputEvent event) {
        if (event.replyText() == null || event.replyText().isBlank()) {
            log.debug("Session [{}] has empty reply, skipping delivery", event.sessionId());
            return;
        }

        Optional<ChannelConversationEntity> conv =
                conversationRepo.findBySessionIdAndClosedAtIsNull(event.sessionId());
        if (conv.isEmpty()) {
            return; // non-channel session (plain UI chat)
        }

        String platform = conv.get().getPlatform();
        Optional<ChannelAdapter> adapter = adapterRegistry.get(platform);
        Optional<ChannelConfigDecrypted> config = configService.getDecryptedConfig(platform);
        if (adapter.isEmpty() || config.isEmpty()) {
            log.warn("Cannot deliver reply for session [{}]: platform [{}] adapter/config missing",
                    event.sessionId(), platform);
            return;
        }

        // Remove the typing-indicator reaction before sending the reply.
        if (event.ackReactionId() != null) {
            adapter.get().removeAck(event.platformMessageId(), event.ackReactionId(), config.get());
        }

        ChannelReply reply = new ChannelReply(
                event.platformMessageId(),
                platform,
                conv.get().getConversationId(),
                event.replyText(),
                true,
                null
        );

        deliveryService.deliver(reply, adapter.get(), config.get(), event.sessionId());
    }
}
