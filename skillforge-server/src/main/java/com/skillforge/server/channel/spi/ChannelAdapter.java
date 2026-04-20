package com.skillforge.server.channel.spi;

import org.springframework.http.ResponseEntity;

import java.util.Optional;

/**
 * Message platform adapter SPI.
 * Each platform registers a @Component implementation.
 * {@link com.skillforge.server.channel.registry.ChannelAdapterRegistry}
 * keys them by {@link #platformId()} and
 * {@link com.skillforge.server.channel.web.ChannelWebhookController}
 * dispatches via path variable.
 */
public interface ChannelAdapter {

    /** Unique platform id, matches URL path variable ("feishu", "telegram"). Lowercase, no '/'. */
    String platformId();

    /** Friendly name for UI. */
    String displayName();

    /**
     * Verify webhook signature/token. Must use rawBody, not deserialized object.
     * Throws {@link WebhookVerificationException} on failure.
     */
    void verifyWebhook(WebhookContext ctx, ChannelConfigDecrypted config)
            throws WebhookVerificationException;

    /**
     * Parse raw webhook body into normalized {@link ChannelMessage}.
     * Returns {@link Optional#empty()} for URL-verification challenges or bot self-messages
     * (controller then calls {@link #handleVerificationChallenge}).
     */
    Optional<ChannelMessage> parseIncoming(byte[] rawBody, ChannelConfigDecrypted config);

    /** When parseIncoming is empty and body is a challenge, produce the response body. */
    ResponseEntity<?> handleVerificationChallenge(byte[] rawBody);

    /**
     * Deliver reply to platform user.
     * {@link DeliveryResult#retryAfterMs()} lets caller wait the exact amount the platform asked.
     */
    DeliveryResult deliver(ChannelReply reply, ChannelConfigDecrypted config);

    /** Delivery HTTP timeout (ms). Default 10s. */
    default long deliveryTimeoutMs() { return 10_000L; }

    /** Max retries (excluding first attempt). Default 3. */
    default int maxRetries() { return 3; }
}
