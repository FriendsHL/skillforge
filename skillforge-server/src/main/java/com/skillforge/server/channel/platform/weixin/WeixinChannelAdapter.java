package com.skillforge.server.channel.platform.weixin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.channel.platform.feishu.FeishuWsReconnectPolicy;
import com.skillforge.server.channel.router.ChannelSessionRouter;
import com.skillforge.server.channel.spi.ChannelAdapter;
import com.skillforge.server.channel.spi.ChannelConfigDecrypted;
import com.skillforge.server.channel.spi.ChannelMessage;
import com.skillforge.server.channel.spi.ChannelPushConnector;
import com.skillforge.server.channel.spi.ChannelReply;
import com.skillforge.server.channel.spi.DeliveryResult;
import com.skillforge.server.channel.spi.WebhookContext;
import com.skillforge.server.channel.spi.WebhookVerificationException;
import com.skillforge.server.repository.ChannelMessageDedupRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Tencent ClawBot (personal WeChat) channel adapter, route B-native: SkillForge implements the
 * iLink HTTP JSON protocol directly.
 *
 * <p>Connection model is <b>outbound long-poll</b> (no inbound webhook), so this is a
 * {@link ChannelPushConnector} (mirrors the Feishu websocket path) rather than a webhook adapter.
 * {@code verifyWebhook} / {@code handleVerificationChallenge} are no-ops; {@code parseIncoming} is
 * unused on the push path but implemented defensively.
 *
 * <p>Reverse-engineered protocol — real end-to-end (scan, send, receive) can only be verified with
 * a live WeChat ClawBot scan; it cannot be exercised in CI.
 */
@Component
public class WeixinChannelAdapter implements ChannelAdapter, ChannelPushConnector {

    private static final Logger log = LoggerFactory.getLogger(WeixinChannelAdapter.class);
    static final String PLATFORM = "weixin";

    private final WeixinIlinkClient client;
    private final WeixinMessageParser parser;
    private final ObjectMapper objectMapper;
    private final WeixinLongPollConnector connector;

    public WeixinChannelAdapter(
            WeixinIlinkClient client,
            WeixinMessageParser parser,
            WeixinCursorStore cursorStore,
            ChannelMessageDedupRepository dedupRepository,
            ChannelSessionRouter router,
            ObjectMapper objectMapper) {
        this.client = client;
        this.parser = parser;
        this.objectMapper = objectMapper;
        this.connector = new WeixinLongPollConnector(
                client,
                parser,
                cursorStore,
                dedupRepository,
                router,
                objectMapper,
                FeishuWsReconnectPolicy.defaultPolicy());
    }

    @Override
    public String platformId() {
        return PLATFORM;
    }

    @Override
    public String displayName() {
        return "WeChat (ClawBot)";
    }

    @Override
    public void verifyWebhook(WebhookContext ctx, ChannelConfigDecrypted config)
            throws WebhookVerificationException {
        // No webhook for weixin (outbound long-poll only). Nothing to verify.
    }

    @Override
    public Optional<ChannelMessage> parseIncoming(byte[] rawBody, ChannelConfigDecrypted config) {
        // Push path uses the long-poll loop; this is here for SPI completeness. Parse defensively.
        if (rawBody == null || rawBody.length == 0) {
            return Optional.empty();
        }
        try {
            JsonNode node = objectMapper.readTree(rawBody);
            return parser.parse(node);
        } catch (Exception e) {
            log.debug("weixin parseIncoming non-JSON body, ignoring");
            return Optional.empty();
        }
    }

    @Override
    public ResponseEntity<?> handleVerificationChallenge(byte[] rawBody) {
        // No URL-verification handshake for weixin.
        return ResponseEntity.ok().body(Map.of());
    }

    @Override
    public DeliveryResult deliver(ChannelReply reply, ChannelConfigDecrypted config) {
        // Recover to_user_id + context_token (INV-2). The reply pipeline drops rawFields, so we
        // decode them from the encoded platformMessageId carried as inboundMessageId.
        WeixinIlinkIds.Decoded decoded = WeixinIlinkIds.decode(reply.inboundMessageId());
        String toUserId = decoded != null && !decoded.fromUserId().isBlank()
                ? decoded.fromUserId()
                : reply.conversationId();   // fallback: conversationId == from_user_id
        String contextToken = decoded != null ? decoded.contextToken() : "";

        if (toUserId == null || toUserId.isBlank()) {
            return DeliveryResult.failed("weixin deliver: cannot resolve to_user_id");
        }

        String botToken = resolveBotToken(config);
        if (botToken == null || botToken.isBlank()) {
            return DeliveryResult.failed("weixin deliver: missing bot_token (re-scan required)");
        }
        String baseurl = resolveBaseurl(config);

        try {
            client.sendText(toUserId, contextToken, reply.markdownText(), botToken, baseurl);
            return DeliveryResult.ok();
        } catch (WeixinIlinkClient.WeixinIlinkException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            // Token-expiry / auth → permanent (re-scan needed); transient → retry.
            if (msg.contains("expired") || msg.contains("auth rejected")) {
                return DeliveryResult.failed("weixin send permanent failure: " + msg);
            }
            return DeliveryResult.retry(0, "weixin send failed: " + msg);
        } catch (Exception e) {
            return DeliveryResult.retry(0, "weixin send error: " + e.getMessage());
        }
    }

    @Override
    public void start(ChannelConfigDecrypted config) {
        connector.start(config);
    }

    @Override
    public void stop() {
        connector.stop();
    }

    private String resolveBotToken(ChannelConfigDecrypted config) {
        try {
            JsonNode creds = objectMapper.readTree(
                    config.credentialsJson() == null ? "{}" : config.credentialsJson());
            return creds.path("bot_token").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private String resolveBaseurl(ChannelConfigDecrypted config) {
        try {
            JsonNode creds = objectMapper.readTree(
                    config.credentialsJson() == null ? "{}" : config.credentialsJson());
            return creds.path("baseurl").asText(null);
        } catch (Exception e) {
            return null;
        }
    }
}
