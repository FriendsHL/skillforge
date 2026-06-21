package com.skillforge.server.channel.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.Message;
import com.skillforge.server.channel.ChannelConfigService;
import com.skillforge.server.channel.delivery.ProgressDeliveryConfig;
import com.skillforge.server.channel.delivery.ReplyDeliveryService;
import com.skillforge.server.channel.registry.ChannelAdapterRegistry;
import com.skillforge.server.channel.spi.ChannelAdapter;
import com.skillforge.server.channel.spi.ChannelConfigDecrypted;
import com.skillforge.server.channel.spi.ChannelReply;
import com.skillforge.server.entity.ChannelConversationEntity;
import com.skillforge.server.repository.ChannelConversationRepository;
import com.skillforge.server.service.event.AssistantTurnAppendedEvent;
import com.skillforge.server.service.event.SessionLoopFinishedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CHANNEL-MIDTURN-PROGRESS: pushes intermediate assistant narration to a
 * channel-bound (feishu / weixin) session during task execution, so the channel
 * isn't silent through a long multi-tool run. The web UI is unaffected — this
 * listener consumes a side-channel {@link AssistantTurnAppendedEvent} published
 * by {@code ChatWebSocketHandler.messageAppended} and never touches the WS path
 * or the agent loop.
 *
 * <p>Dedup with the terminal delivery path is structural (OQ-1): the agent loop
 * stops on an assistant turn that has NO tool_use, so the final answer is
 * (essentially always) a text-only turn. This listener handles ONLY turns that
 * carry ≥1 tool_use block (narration-before-action) and skips text-only turns,
 * leaving the final answer to the existing terminal delivery
 * ({@code ChannelAsyncDeliveryListener} / {@code ChannelReplyEventListener}).
 * Result: zero overlap, no double-send.
 *
 * <p>Per-conversation throttling (in-memory) caps the burst: drop if too soon
 * since the last send or if the per-run cap is reached. State is reset when the
 * loop finishes ({@link SessionLoopFinishedEvent}), which also keeps the map
 * bounded.
 *
 * <p>Best-effort: any failure is logged and swallowed — progress delivery must
 * never affect the agent loop.
 */
@Component
public class ChannelProgressDeliveryListener {

    private static final Logger log = LoggerFactory.getLogger(ChannelProgressDeliveryListener.class);

    private final ChannelConversationRepository conversationRepo;
    private final ReplyDeliveryService deliveryService;
    private final ChannelAdapterRegistry adapterRegistry;
    private final ChannelConfigService configService;
    private final ObjectMapper objectMapper;

    /** Per-conversation throttle state, keyed by conversationId. */
    private final ConcurrentHashMap<String, ThrottleState> throttle = new ConcurrentHashMap<>();

    private static final class ThrottleState {
        // Not volatile: every read/write happens inside synchronized(state) in the listener,
        // which already provides visibility + atomicity for the check-and-increment.
        long lastSentAt = 0L;
        int sentCount = 0;
    }

    public ChannelProgressDeliveryListener(ChannelConversationRepository conversationRepo,
                                           ReplyDeliveryService deliveryService,
                                           ChannelAdapterRegistry adapterRegistry,
                                           ChannelConfigService configService,
                                           ObjectMapper objectMapper) {
        this.conversationRepo = conversationRepo;
        this.deliveryService = deliveryService;
        this.adapterRegistry = adapterRegistry;
        this.configService = configService;
        this.objectMapper = objectMapper;
    }

    @Async("channelRouterExecutor")
    @EventListener
    public void onAssistantTurn(AssistantTurnAppendedEvent event) {
        try {
            handle(event);
        } catch (Exception e) {
            // best-effort: must NOT affect the agent loop.
            log.warn("Channel progress delivery failed for session [{}]: {}",
                    event.sessionId(), e.getMessage(), e);
        }
    }

    private void handle(AssistantTurnAppendedEvent event) {
        Message message = event.message();
        if (message == null) {
            return;
        }

        // 1. Dedup (OQ-1): only "narration-before-action" turns (≥1 tool_use block).
        //    A text-only assistant turn is the final answer — leave it to the terminal
        //    delivery path. This is what prevents double-sending the final answer.
        if (message.getToolUseBlocks().isEmpty()) {
            return;
        }

        // 2. Channel-bound session? Non-channel (plain web) sessions have no conversation
        //    row → no-op (zero behavior change for web-only sessions).
        Optional<ChannelConversationEntity> convOpt =
                conversationRepo.findBySessionIdAndClosedAtIsNull(event.sessionId());
        if (convOpt.isEmpty()) {
            return;
        }
        ChannelConversationEntity conv = convOpt.get();
        String platform = conv.getPlatform();
        String conversationId = conv.getConversationId();

        // 3. Resolve adapter + config (config also carries per-channel progress settings).
        Optional<ChannelAdapter> adapter = adapterRegistry.get(platform);
        Optional<ChannelConfigDecrypted> config = configService.getDecryptedConfig(platform);
        if (adapter.isEmpty() || config.isEmpty()) {
            // Early-fail detection: missing binding/adapter → no error, no loop impact.
            return;
        }
        ChannelConfigDecrypted cfg = config.get();

        ProgressDeliveryConfig pd = ProgressDeliveryConfig.parse(cfg.configJson(), platform, objectMapper);
        if (!pd.enabled()) {
            // Platform/channel opted out (weixin default off) → terminal-only delivery.
            return;
        }

        // 4. Extract the TEXT blocks only (tool_use I/O is excluded by getTextContent,
        //    which collects text blocks; tool_use blocks have no text). Skip empty/short.
        String text = message.getTextContent();
        if (text == null) {
            return;
        }
        String trimmed = text.trim();
        if (trimmed.length() < pd.minChars()) {
            return;
        }

        // 5. Throttle per-conversation: drop if too soon or per-run cap reached.
        long now = System.currentTimeMillis();
        ThrottleState state = throttle.computeIfAbsent(conversationId, k -> new ThrottleState());
        synchronized (state) {
            if (state.sentCount >= pd.maxPerRun()) {
                return;
            }
            if (state.lastSentAt != 0L && (now - state.lastSentAt) < pd.minIntervalMs()) {
                return;
            }
            // Reserve the slot before the (slower) network send so concurrent turns for
            // the same conversation can't both pass the gate. Same-session loops are
            // serialized upstream, but reserving here keeps the throttle correct even if
            // that ever changes.
            state.lastSentAt = now;
            state.sentCount++;
        }

        // 6. Prefix + deliver via the shared delivery service (persist + retry reused;
        //    no new HTTP/retry logic).
        String progressText = pd.prefix() + trimmed;
        String syntheticId = "progress:" + UUID.randomUUID();
        ChannelReply reply = new ChannelReply(
                syntheticId,
                platform,
                conversationId,
                progressText,
                true,
                null
        );
        deliveryService.deliver(reply, adapter.get(), cfg, event.sessionId());

        log.debug("Channel progress delivered for session [{}]: platform [{}], conv [{}], {} chars",
                event.sessionId(), platform, conversationId, trimmed.length());
    }

    /**
     * Reset per-conversation throttle state when the loop finishes: clears the per-run
     * count for the next run and keeps the throttle map from growing unbounded.
     */
    @Async("channelRouterExecutor")
    @EventListener
    public void onLoopFinished(SessionLoopFinishedEvent event) {
        try {
            Optional<ChannelConversationEntity> convOpt =
                    conversationRepo.findBySessionIdAndClosedAtIsNull(event.sessionId());
            convOpt.ifPresent(conv -> throttle.remove(conv.getConversationId()));
        } catch (Exception e) {
            log.warn("Channel progress throttle reset failed for session [{}]: {}",
                    event.sessionId(), e.getMessage());
        }
    }
}
