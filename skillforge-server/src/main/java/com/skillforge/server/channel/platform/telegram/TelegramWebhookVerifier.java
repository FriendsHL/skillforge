package com.skillforge.server.channel.platform.telegram;

import com.skillforge.server.channel.spi.WebhookContext;
import com.skillforge.server.channel.spi.WebhookVerificationException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Telegram sets header X-Telegram-Bot-Api-Secret-Token on webhook delivery.
 * We compare it to the configured secret_token using constant-time equality.
 */
public class TelegramWebhookVerifier {

    public void verify(WebhookContext ctx, String expectedSecret) {
        if (expectedSecret == null || expectedSecret.isBlank()) {
            throw new WebhookVerificationException("telegram", "server missing secret_token config");
        }
        String actual = ctx.header("x-telegram-bot-api-secret-token");
        if (actual == null) {
            throw new WebhookVerificationException("telegram", "missing secret_token header");
        }
        byte[] a = actual.getBytes(StandardCharsets.UTF_8);
        byte[] b = expectedSecret.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(a, b)) {
            throw new WebhookVerificationException("telegram", "secret_token mismatch");
        }
    }
}
