package com.skillforge.server.channel.platform.weixin;

import com.fasterxml.jackson.databind.JsonNode;
import com.skillforge.server.channel.spi.ChannelMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Parses one iLink inbound message node into a normalized {@link ChannelMessage}.
 *
 * <p><b>context_token echo (INV-2).</b> The SkillForge reply pipeline
 * ({@code ChannelReplyEventListener} → {@code ReplyDeliveryService}) only carries
 * {@code inboundMessageId} / {@code conversationId} through to {@code deliver()}; it does NOT
 * propagate {@code rawFields}. So to make the inbound {@code context_token} available at reply
 * time (and survive a restart, since the delivery record persists {@code inboundMessageId}), we
 * encode {@code from_user_id} + {@code context_token} into the {@code platformMessageId} using a
 * reversible, delimiter-escaped format ({@link WeixinIlinkIds}). {@code conversationId} is set to
 * {@code from_user_id} for routing. The token is also mirrored into {@code rawFields} for
 * completeness/debugging.
 *
 * <p>Reverse-engineered protocol — parses defensively; unknown item types map to UNSUPPORTED.
 */
@Component
public class WeixinMessageParser {

    private static final Logger log = LoggerFactory.getLogger(WeixinMessageParser.class);

    static final String PLATFORM = "weixin";

    /** message_type values per the reverse-engineered spec. */
    static final int MSG_TYPE_USER = 1;
    static final int MSG_TYPE_BOT = 2;

    /** item_list element types. 1=text (handled in slice 1); 2/3/4/5 = media (UNSUPPORTED). */
    static final int ITEM_TYPE_TEXT = 1;

    /**
     * @return parsed message, or empty when the node should be skipped (bot self-message,
     *         malformed, or missing required identity fields).
     */
    public Optional<ChannelMessage> parse(JsonNode msg) {
        if (msg == null || msg.isMissingNode() || !msg.isObject()) {
            return Optional.empty();
        }

        // Filter out bot self-messages (message_type=2): we only route inbound user messages.
        int messageType = msg.path("message_type").asInt(MSG_TYPE_USER);
        if (messageType == MSG_TYPE_BOT) {
            return Optional.empty();
        }

        String fromUserId = msg.path("from_user_id").asText("");
        if (fromUserId.isBlank()) {
            log.debug("weixin inbound missing from_user_id, skipping");
            return Optional.empty();
        }
        String contextToken = msg.path("context_token").asText("");

        TextExtract extract = extractText(msg.path("item_list"));

        String platformMessageId = WeixinIlinkIds.encode(fromUserId, contextToken, extract.stableId);

        Map<String, Object> rawFields = new HashMap<>();
        rawFields.put("context_token", contextToken);
        rawFields.put("from_user_id", fromUserId);

        return Optional.of(new ChannelMessage(
                PLATFORM,
                fromUserId,            // conversationId = the personal-WeChat user (1:1 conversation)
                fromUserId,            // platformUserId
                platformMessageId,
                extract.type,
                extract.text,
                null,                  // attachmentUrl — media deferred to slice 2
                Instant.now(),
                rawFields));
    }

    private TextExtract extractText(JsonNode itemList) {
        if (itemList == null || !itemList.isArray() || itemList.isEmpty()) {
            return new TextExtract(ChannelMessage.MessageType.UNSUPPORTED, null, "noitem");
        }
        for (JsonNode item : itemList) {
            int type = item.path("type").asInt(-1);
            if (type == ITEM_TYPE_TEXT) {
                String text = item.path("text_item").path("text").asText("");
                // Derive a stable per-message id component from the text content so dedup is
                // deterministic across retries of the same logical message.
                String stableId = sha256Short(text);
                return new TextExtract(ChannelMessage.MessageType.TEXT, text, stableId);
            }
        }
        // Non-text (image/voice/file/video) — slice 1 marks UNSUPPORTED.
        return new TextExtract(ChannelMessage.MessageType.UNSUPPORTED, null, "media");
    }

    private static String sha256Short(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((s == null ? "" : s).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(digest).substring(0, 16);
        } catch (Exception e) {
            return Integer.toHexString((s == null ? "" : s).hashCode());
        }
    }

    private record TextExtract(ChannelMessage.MessageType type, String text, String stableId) {}
}
