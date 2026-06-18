package com.skillforge.server.channel.platform.weixin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.channel.spi.ChannelMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class WeixinMessageParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WeixinMessageParser parser = new WeixinMessageParser();

    private JsonNode json(String s) {
        try {
            return objectMapper.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("parses a text message and retains context_token (rawFields + encoded id)")
    void parse_textMessage_retainsContextToken() {
        JsonNode msg = json("""
                {
                  "from_user_id": "userA",
                  "to_user_id": "bot",
                  "message_type": 1,
                  "context_token": "ctx-token-123",
                  "item_list": [ {"type": 1, "text_item": {"text": "hi there"}} ]
                }
                """);

        Optional<ChannelMessage> parsed = parser.parse(msg);

        assertThat(parsed).isPresent();
        ChannelMessage cm = parsed.get();
        assertThat(cm.platform()).isEqualTo("weixin");
        assertThat(cm.platformUserId()).isEqualTo("userA");
        assertThat(cm.conversationId()).isEqualTo("userA");
        assertThat(cm.type()).isEqualTo(ChannelMessage.MessageType.TEXT);
        assertThat(cm.text()).isEqualTo("hi there");
        assertThat(cm.rawFields()).containsEntry("context_token", "ctx-token-123");

        // INV-2: context_token recoverable from the encoded platformMessageId (the reply pipeline
        // drops rawFields, so the deliver path relies on the encoded id).
        WeixinIlinkIds.Decoded decoded = WeixinIlinkIds.decode(cm.platformMessageId());
        assertThat(decoded).isNotNull();
        assertThat(decoded.fromUserId()).isEqualTo("userA");
        assertThat(decoded.contextToken()).isEqualTo("ctx-token-123");
    }

    @Test
    @DisplayName("filters out bot self-messages (message_type=2)")
    void parse_botSelfMessage_skipped() {
        JsonNode msg = json("""
                {
                  "from_user_id": "bot",
                  "message_type": 2,
                  "context_token": "x",
                  "item_list": [ {"type": 1, "text_item": {"text": "echo"}} ]
                }
                """);

        assertThat(parser.parse(msg)).isEmpty();
    }

    @Test
    @DisplayName("missing from_user_id is skipped")
    void parse_missingFromUserId_skipped() {
        JsonNode msg = json("""
                { "message_type": 1, "item_list": [ {"type": 1, "text_item": {"text": "x"}} ] }
                """);

        assertThat(parser.parse(msg)).isEmpty();
    }

    @Test
    @DisplayName("non-text item maps to UNSUPPORTED (slice 1 text-only)")
    void parse_imageItem_unsupported() {
        JsonNode msg = json("""
                {
                  "from_user_id": "userB",
                  "message_type": 1,
                  "context_token": "c",
                  "item_list": [ {"type": 2, "image_item": {"url": "http://x"}} ]
                }
                """);

        Optional<ChannelMessage> parsed = parser.parse(msg);

        assertThat(parsed).isPresent();
        assertThat(parsed.get().type()).isEqualTo(ChannelMessage.MessageType.UNSUPPORTED);
        assertThat(parsed.get().text()).isNull();
    }

    @Test
    @DisplayName("same logical message yields the same platformMessageId (dedup stability)")
    void parse_dedupId_stableAcrossCalls() {
        String raw = """
                {
                  "from_user_id": "userA",
                  "message_type": 1,
                  "context_token": "ctx-1",
                  "item_list": [ {"type": 1, "text_item": {"text": "same text"}} ]
                }
                """;

        String id1 = parser.parse(json(raw)).orElseThrow().platformMessageId();
        String id2 = parser.parse(json(raw)).orElseThrow().platformMessageId();

        assertThat(id1).isEqualTo(id2);
    }

    @Test
    @DisplayName("null / non-object node is skipped")
    void parse_nullNode_skipped() {
        assertThat(parser.parse(null)).isEmpty();
        assertThat(parser.parse(json("\"not-an-object\""))).isEmpty();
    }

    @Test
    @DisplayName("WeixinIlinkIds round-trips fromUserId + contextToken with delimiter-bearing values")
    void ids_roundTrip_withTrickyChars() {
        String encoded = WeixinIlinkIds.encode("user|with|pipes", "ctx with spaces|and|pipes", "sid");
        WeixinIlinkIds.Decoded decoded = WeixinIlinkIds.decode(encoded);

        assertThat(decoded).isNotNull();
        assertThat(decoded.fromUserId()).isEqualTo("user|with|pipes");
        assertThat(decoded.contextToken()).isEqualTo("ctx with spaces|and|pipes");
    }

    @Test
    @DisplayName("WeixinIlinkIds.decode returns null for foreign ids")
    void ids_decode_foreignId_null() {
        assertThat(WeixinIlinkIds.decode("om_feishu_123")).isNull();
        assertThat(WeixinIlinkIds.decode(null)).isNull();
    }

    @Test
    @DisplayName("WeixinIlinkIds.encode fails loud when the encoded id exceeds the safe bound")
    void ids_encode_oversized_throws() {
        String hugeToken = "x".repeat(WeixinIlinkIds.MAX_ENCODED_LEN);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> WeixinIlinkIds.encode("user", hugeToken, "sid"))
                .isInstanceOf(WeixinIlinkClient.WeixinIlinkException.class)
                .hasMessageContaining("too long");
    }
}
