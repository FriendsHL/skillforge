package com.skillforge.server.channel.platform.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.channel.spi.ChannelAdapter;
import com.skillforge.server.channel.spi.ChannelConfigDecrypted;
import com.skillforge.server.channel.spi.ChannelMessage;
import com.skillforge.server.channel.spi.ChannelReply;
import com.skillforge.server.channel.spi.DeliveryResult;
import com.skillforge.server.channel.spi.WebhookContext;
import com.skillforge.server.channel.spi.WebhookVerificationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.Optional;

/**
 * Telegram Bot API ChannelAdapter.
 * Parses Update objects from webhook body. Skips edited_message / my_chat_member etc.
 */
@Component
public class TelegramChannelAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(TelegramChannelAdapter.class);

    private final TelegramWebhookVerifier verifier;
    private final TelegramBotClient client;
    private final ObjectMapper objectMapper;

    public TelegramChannelAdapter(TelegramBotClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.verifier = new TelegramWebhookVerifier();
    }

    @Override
    public String platformId() {
        return "telegram";
    }

    @Override
    public String displayName() {
        return "Telegram";
    }

    @Override
    public void verifyWebhook(WebhookContext ctx, ChannelConfigDecrypted config)
            throws WebhookVerificationException {
        verifier.verify(ctx, config.webhookSecret());
    }

    @Override
    public Optional<ChannelMessage> parseIncoming(byte[] rawBody, ChannelConfigDecrypted config) {
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            JsonNode message = root.path("message");
            if (message.isMissingNode() || message.isNull()) {
                // edited_message / channel_post / my_chat_member — ignore
                return Optional.empty();
            }

            long updateId = root.path("update_id").asLong();
            long messageId = message.path("message_id").asLong();
            JsonNode chat = message.path("chat");
            String chatId = chat.path("id").asText(null);
            String chatType = chat.path("type").asText("private");

            JsonNode from = message.path("from");
            if (from.path("is_bot").asBoolean(false)) {
                return Optional.empty();
            }
            String userId = from.path("id").asText(null);

            long date = message.path("date").asLong();
            Instant ts = date > 0 ? Instant.ofEpochSecond(date) : Instant.now();

            String dedupId = "tg-" + updateId;
            String text = null;
            String attachmentUrl = null;
            ChannelMessage.MessageType type;

            if (message.hasNonNull("text")) {
                text = message.path("text").asText("");
                boolean atBot = "group".equals(chatType) || "supergroup".equals(chatType);
                type = atBot ? ChannelMessage.MessageType.AT_BOT : ChannelMessage.MessageType.TEXT;
            } else if (message.hasNonNull("photo")) {
                type = ChannelMessage.MessageType.IMAGE;
            } else if (message.hasNonNull("document")) {
                type = ChannelMessage.MessageType.FILE;
                attachmentUrl = message.path("document").path("file_id").asText(null);
            } else if (message.hasNonNull("voice")) {
                type = ChannelMessage.MessageType.VOICE;
                attachmentUrl = message.path("voice").path("file_id").asText(null);
            } else {
                type = ChannelMessage.MessageType.UNSUPPORTED;
            }

            return Optional.of(new ChannelMessage(
                    "telegram",
                    chatId,
                    userId,
                    dedupId,
                    type,
                    text,
                    attachmentUrl,
                    ts,
                    Collections.emptyMap()
            ));
        } catch (Exception e) {
            log.warn("Telegram parse failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public ResponseEntity<?> handleVerificationChallenge(byte[] rawBody) {
        // Telegram has no URL-verification challenge
        return ResponseEntity.ok().build();
    }

    @Override
    public DeliveryResult deliver(ChannelReply reply, ChannelConfigDecrypted config) {
        return client.sendMessage(reply, config);
    }
}
