package com.skillforge.server.channel.platform.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.channel.spi.ChannelAdapter;
import com.skillforge.server.channel.spi.ChannelConfigDecrypted;
import com.skillforge.server.channel.spi.ChannelMessage;
import com.skillforge.server.channel.spi.ChannelReply;
import com.skillforge.server.channel.spi.DeliveryResult;
import com.skillforge.server.channel.spi.WebhookContext;
import com.skillforge.server.channel.spi.WebhookVerificationException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Feishu (Lark) ChannelAdapter — composes verifier + parser + client.
 */
@Component
public class FeishuChannelAdapter implements ChannelAdapter {

    private final FeishuWebhookVerifier verifier;
    private final FeishuEventParser parser;
    private final FeishuClient client;
    private final ObjectMapper objectMapper;

    public FeishuChannelAdapter(FeishuClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.verifier = new FeishuWebhookVerifier();
        this.parser = new FeishuEventParser(objectMapper);
    }

    @Override
    public String platformId() {
        return "feishu";
    }

    @Override
    public String displayName() {
        return "Feishu (Lark)";
    }

    @Override
    public void verifyWebhook(WebhookContext ctx, ChannelConfigDecrypted config)
            throws WebhookVerificationException {
        verifier.verify(ctx, config.webhookSecret());
    }

    @Override
    public Optional<ChannelMessage> parseIncoming(byte[] rawBody, ChannelConfigDecrypted config) {
        return parser.parse(rawBody, config);
    }

    @Override
    public ResponseEntity<?> handleVerificationChallenge(byte[] rawBody) {
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            if (root.hasNonNull("challenge")
                    && "url_verification".equals(root.path("type").asText())) {
                String challenge = root.path("challenge").asText();
                return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("challenge", challenge));
            }
        } catch (Exception ignored) {
            // Not a challenge, fall through
        }
        return ResponseEntity.ok().body(Map.of());
    }

    @Override
    public DeliveryResult deliver(ChannelReply reply, ChannelConfigDecrypted config) {
        return client.sendInteractive(reply, config);
    }
}
