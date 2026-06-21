package com.skillforge.server.channel.listener;

import com.skillforge.server.channel.ChannelConfigService;
import com.skillforge.server.channel.delivery.ReplyDeliveryService;
import com.skillforge.server.channel.registry.ChannelAdapterRegistry;
import com.skillforge.server.channel.spi.ChannelAdapter;
import com.skillforge.server.channel.spi.ChannelConfigDecrypted;
import com.skillforge.server.channel.spi.ChannelReply;
import com.skillforge.server.entity.ChannelConversationEntity;
import com.skillforge.server.repository.ChannelConversationRepository;
import com.skillforge.server.service.event.SessionLoopFinishedEvent;
import com.skillforge.server.websocket.ChatWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * CHANNEL-ASYNC-DELIVERY: delivers channel-bound agent-loop output for loops that
 * were NOT triggered by a channel-inbound message or web-WS turn.
 *
 * <p>The inbound path delivers via {@code ChannelSessionOutputEvent} (published by
 * {@code ChatWebSocketHandler.sessionStatus} using {@code channelContexts}, which is
 * only populated at inbound registration). Async-resumed loops (SubAgent / Team result
 * injection, scheduled tasks, startup recovery) never repopulate that map, so their
 * output was stranded (persisted but never sent back to the channel).
 *
 * <p>This listener consumes the generic {@link SessionLoopFinishedEvent} (fired in every
 * loop teardown) and delivers iff the inbound path did NOT own the turn. Ownership is
 * read-and-removed atomically from {@link ChatWebSocketHandler#removeChannelTurnHandled}:
 * non-null → inbound owns it (delivered or chose not to) → skip; null → async/non-channel
 * → take ownership and deliver (when a channel conversation exists).
 *
 * <p>Dedup is airtight because any inbound {@code registerChannelTurn} leaves a marker
 * present until this listener removes it, and same-session loops are serialized (the
 * compaction stripe lock), so the inbound loop's listener never races an async loop's.
 *
 * <p>Runs on the {@code channelRouterExecutor} pool (same as the inbound delivery
 * listener) so it never blocks the loop teardown finally block.
 */
@Component
public class ChannelAsyncDeliveryListener {

    private static final Logger log = LoggerFactory.getLogger(ChannelAsyncDeliveryListener.class);

    private final ChatWebSocketHandler chatWebSocketHandler;
    private final ChannelConversationRepository conversationRepo;
    private final ReplyDeliveryService deliveryService;
    private final ChannelAdapterRegistry adapterRegistry;
    private final ChannelConfigService configService;

    public ChannelAsyncDeliveryListener(ChatWebSocketHandler chatWebSocketHandler,
                                        ChannelConversationRepository conversationRepo,
                                        ReplyDeliveryService deliveryService,
                                        ChannelAdapterRegistry adapterRegistry,
                                        ChannelConfigService configService) {
        this.chatWebSocketHandler = chatWebSocketHandler;
        this.conversationRepo = conversationRepo;
        this.deliveryService = deliveryService;
        this.adapterRegistry = adapterRegistry;
        this.configService = configService;
    }

    @Async("channelRouterExecutor")
    @EventListener
    public void onLoopFinished(SessionLoopFinishedEvent event) {
        // Defensive: never let a listener exception bubble (it runs async, but keep the
        // contract explicit). Any failure is logged and swallowed.
        try {
            deliverAsyncTurn(event);
        } catch (Exception e) {
            log.error("Async channel delivery failed for session [{}]: {}",
                    event.sessionId(), e.getMessage(), e);
        }
    }

    private void deliverAsyncTurn(SessionLoopFinishedEvent event) {
        String sessionId = event.sessionId();

        // 1. Dedup: if the inbound path registered this turn, it owns delivery → skip.
        //    null means no inbound turn (async-resumed / non-channel session).
        Boolean handled = chatWebSocketHandler.removeChannelTurnHandled(sessionId);
        if (handled != null) {
            return;
        }

        // 2. Skip non-user-facing terminal states. This guard operates on
        //    SessionLoopFinishedEvent.finalStatus, whose values are the loop terminal
        //    states {completed / cancelled / error / aborted_by_hook / waiting_user} — NOT
        //    the "idle" string that broadcaster.sessionStatus(...) emits to the WS. "idle"
        //    never appears here. The guard's purpose is the ASYNC delivery path only;
        //    inbound turns are already excluded above by the non-null handled marker, so
        //    this status filtering protects async-resumed loops from pushing non-user-facing
        //    output. NOTE: waiting_user is intentionally NOT skipped (OQ-1 ratified): an
        //    async turn that pauses with an ask should deliver the ask text to the channel
        //    as a normal message.
        String status = event.finalStatus();
        if ("error".equals(status) || "aborted_by_hook".equals(status) || "cancelled".equals(status)) {
            return;
        }

        // 3. Skip empty output.
        String text = event.finalMessage();
        if (text == null || text.isBlank()) {
            return;
        }

        // 4. Only deliver for channel-bound sessions. A child SubAgent session has no
        //    conversation row (the channel binds to the parent root session), so this
        //    returns empty for children — which is what prevents delivering child output;
        //    the parent's merged output is delivered when the parent loop finishes.
        Optional<ChannelConversationEntity> convOpt =
                conversationRepo.findBySessionIdAndClosedAtIsNull(sessionId);
        if (convOpt.isEmpty()) {
            return;
        }
        ChannelConversationEntity conv = convOpt.get();
        String platform = conv.getPlatform();

        // 5. Resolve adapter + config.
        Optional<ChannelAdapter> adapter = adapterRegistry.get(platform);
        Optional<ChannelConfigDecrypted> config = configService.getDecryptedConfig(platform);
        if (adapter.isEmpty() || config.isEmpty()) {
            log.warn("Cannot async-deliver reply for session [{}]: platform [{}] adapter/config missing",
                    sessionId, platform);
            return;
        }

        // 6. Build the reply. Async turns have no real inbound platform message id; synthesize
        //    a unique one. t_channel_delivery.inbound_message_id has a unique index (V17) +
        //    VARCHAR(1024) (V156); "async:" + UUID (~42 chars) is unique per turn and decodes
        //    to null in WeixinChannelAdapter (non-"wx1|" prefix) → falls back to conversationId
        //    (== from_user_id), the correct push recipient.
        String syntheticId = "async:" + UUID.randomUUID();
        ChannelReply reply = new ChannelReply(
                syntheticId,
                platform,
                conv.getConversationId(),
                text,
                true,
                null
        );

        // 7. Deliver DIRECTLY (do NOT republish ChannelSessionOutputEvent — that path would
        //    call removeAck with a null platformMessageId for async turns).
        deliveryService.deliver(reply, adapter.get(), config.get(), sessionId);

        // 8. Trace.
        log.info("Async channel delivery for session [{}]: platform [{}], conv [{}], status [{}], {} chars",
                sessionId, platform, conv.getConversationId(), status, text.length());
    }
}
