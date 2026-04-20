package com.skillforge.server.channel.platform.feishu;

import com.skillforge.server.channel.spi.WebhookContext;
import com.skillforge.server.channel.spi.WebhookVerificationException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Feishu signature: SHA256(timestamp + "\n" + nonce + "\n" + encryptKey + "\n" + body).
 * Plain SHA-256 (not HMAC); key is encryptKey, NOT verificationToken.
 */
public class FeishuWebhookVerifier {

    private static final long TIMESTAMP_WINDOW_SEC = 300;

    public void verify(WebhookContext ctx, String encryptKey) {
        // No encryptKey configured in Feishu app → signature headers won't be present; skip.
        if (encryptKey == null || encryptKey.isBlank()) {
            return;
        }

        String timestamp = ctx.header("x-lark-request-timestamp");
        String nonce = ctx.header("x-lark-request-nonce");
        String signature = ctx.header("x-lark-signature");

        if (timestamp == null || nonce == null || signature == null) {
            throw new WebhookVerificationException("feishu", "missing required headers");
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            throw new WebhookVerificationException("feishu", "invalid timestamp");
        }
        if (Math.abs(Instant.now().getEpochSecond() - ts) > TIMESTAMP_WINDOW_SEC) {
            throw new WebhookVerificationException("feishu", "timestamp out of window");
        }

        String bodyString = new String(ctx.rawBody(), StandardCharsets.UTF_8);
        String toSign = timestamp + "\n" + nonce + "\n" + encryptKey + "\n" + bodyString;

        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] expected = sha256.digest(toSign.getBytes(StandardCharsets.UTF_8));
            byte[] actual = HexFormat.of().parseHex(signature);
            if (!MessageDigest.isEqual(expected, actual)) {
                throw new WebhookVerificationException("feishu", "SHA-256 mismatch");
            }
        } catch (NoSuchAlgorithmException e) {
            throw new WebhookVerificationException("feishu", "no SHA-256 algorithm");
        } catch (IllegalArgumentException e) {
            throw new WebhookVerificationException("feishu", "invalid signature hex");
        }
    }
}
