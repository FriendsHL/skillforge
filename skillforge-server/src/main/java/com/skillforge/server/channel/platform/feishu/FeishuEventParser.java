package com.skillforge.server.channel.platform.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.channel.spi.ChannelConfigDecrypted;
import com.skillforge.server.channel.spi.ChannelMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.Optional;

/**
 * Parses Feishu Event Callback v2 payloads into {@link ChannelMessage}.
 * Filters bot self-messages (sender.sender_type == "app") to avoid loops.
 */
@Component
public class FeishuEventParser {

    private static final Logger log = LoggerFactory.getLogger(FeishuEventParser.class);

    private final ObjectMapper objectMapper;

    public FeishuEventParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Optional<ChannelMessage> parse(byte[] rawBody, ChannelConfigDecrypted config) {
        try {
            JsonNode root = objectMapper.readTree(rawBody);

            // URL verification challenge
            if (root.hasNonNull("challenge") && root.path("type").asText().equals("url_verification")) {
                return Optional.empty();
            }

            JsonNode event = root.path("event");
            if (event.isMissingNode() || event.isNull()) {
                return Optional.empty();
            }
            if (isBotSelfMessage(event)) {
                log.debug("Feishu: ignoring bot self-message");
                return Optional.empty();
            }

            JsonNode message = event.path("message");
            String messageId = message.path("message_id").asText(null);
            if (messageId == null || messageId.isBlank()) {
                log.debug("Feishu event missing message_id, skipping");
                return Optional.empty();
            }
            String chatId = message.path("chat_id").asText(null);
            String chatType = message.path("chat_type").asText("p2p");
            String msgType = message.path("message_type").asText("text");
            long createTime = parseLongSafe(message.path("create_time").asText(null));

            JsonNode sender = event.path("sender");
            String senderId = sender.path("sender_id").path("open_id").asText(null);

            String text = null;
            String attachmentUrl = null;
            ChannelMessage.MessageType type;

            switch (msgType) {
                case "text" -> {
                    String content = message.path("content").asText("");
                    String extracted = extractTextFromContentJson(content);
                    text = stripAtTags(extracted);
                    boolean atGroup = !"p2p".equals(chatType);
                    type = atGroup ? ChannelMessage.MessageType.AT_BOT : ChannelMessage.MessageType.TEXT;
                }
                case "image" -> {
                    type = ChannelMessage.MessageType.IMAGE;
                    attachmentUrl = extractFieldFromContentJson(message.path("content").asText(""), "image_key");
                }
                case "file" -> {
                    type = ChannelMessage.MessageType.FILE;
                    attachmentUrl = extractFieldFromContentJson(message.path("content").asText(""), "file_key");
                }
                default -> type = ChannelMessage.MessageType.UNSUPPORTED;
            }

            return Optional.of(new ChannelMessage(
                    "feishu",
                    chatId,
                    senderId,
                    messageId,
                    type,
                    text,
                    attachmentUrl,
                    createTime > 0 ? Instant.ofEpochMilli(createTime) : Instant.now(),
                    Collections.emptyMap()
            ));
        } catch (Exception e) {
            log.warn("Failed to parse Feishu event: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private boolean isBotSelfMessage(JsonNode event) {
        JsonNode senderType = event.path("sender").path("sender_type");
        return "app".equals(senderType.asText());
    }

    private long parseLongSafe(String s) {
        if (s == null) return 0;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String extractTextFromContentJson(String contentJson) {
        if (contentJson == null || contentJson.isBlank()) return "";
        try {
            JsonNode n = objectMapper.readTree(contentJson);
            return n.path("text").asText("");
        } catch (Exception e) {
            return contentJson;
        }
    }

    private String extractFieldFromContentJson(String contentJson, String field) {
        if (contentJson == null || contentJson.isBlank()) return null;
        try {
            JsonNode n = objectMapper.readTree(contentJson);
            String value = n.path(field).asText(null);
            return value == null || value.isBlank() ? null : value;
        } catch (Exception e) {
            return null;
        }
    }

    /** Remove &lt;at user_id=...&gt;&lt;/at&gt; tags from group text. */
    private String stripAtTags(String text) {
        if (text == null) return null;
        return text.replaceAll("<at[^>]*>[^<]*</at>", "").trim();
    }
}
