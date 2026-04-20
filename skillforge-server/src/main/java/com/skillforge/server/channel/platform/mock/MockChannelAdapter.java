package com.skillforge.server.channel.platform.mock;

import com.skillforge.server.channel.spi.ChannelAdapter;
import com.skillforge.server.channel.spi.ChannelConfigDecrypted;
import com.skillforge.server.channel.spi.ChannelMessage;
import com.skillforge.server.channel.spi.ChannelReply;
import com.skillforge.server.channel.spi.DeliveryResult;
import com.skillforge.server.channel.spi.WebhookContext;
import com.skillforge.server.channel.spi.WebhookVerificationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Mock adapter for tests/dev. Does no verification, stores inbound/outbound
 * in-memory lists so tests can assert on them.
 */
@Component
@Profile({"test", "dev"})
public class MockChannelAdapter implements ChannelAdapter {

    private final ObjectMapper objectMapper;
    private final List<ChannelMessage> received = new CopyOnWriteArrayList<>();
    private final List<ChannelReply> delivered = new CopyOnWriteArrayList<>();

    public MockChannelAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String platformId() {
        return "mock";
    }

    @Override
    public String displayName() {
        return "Mock";
    }

    @Override
    public void verifyWebhook(WebhookContext ctx, ChannelConfigDecrypted config)
            throws WebhookVerificationException {
        // no-op
    }

    @Override
    public Optional<ChannelMessage> parseIncoming(byte[] rawBody, ChannelConfigDecrypted config) {
        try {
            JsonNode n = objectMapper.readTree(rawBody);
            if (!n.hasNonNull("messageId")) {
                return Optional.empty();
            }
            ChannelMessage msg = new ChannelMessage(
                    "mock",
                    n.path("chatId").asText("mock-chat"),
                    n.path("userId").asText("mock-user"),
                    n.path("messageId").asText(),
                    ChannelMessage.MessageType.TEXT,
                    n.path("text").asText(""),
                    null,
                    Instant.now(),
                    Collections.emptyMap()
            );
            received.add(msg);
            return Optional.of(msg);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public ResponseEntity<?> handleVerificationChallenge(byte[] rawBody) {
        return ResponseEntity.ok().build();
    }

    @Override
    public DeliveryResult deliver(ChannelReply reply, ChannelConfigDecrypted config) {
        delivered.add(reply);
        return DeliveryResult.ok();
    }

    public List<ChannelMessage> getReceived() {
        return Collections.unmodifiableList(received);
    }

    public List<ChannelReply> getDelivered() {
        return Collections.unmodifiableList(delivered);
    }

    public void clear() {
        received.clear();
        delivered.clear();
    }
}
