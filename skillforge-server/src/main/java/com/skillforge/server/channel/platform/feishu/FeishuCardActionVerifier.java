package com.skillforge.server.channel.platform.feishu;

import com.skillforge.server.channel.spi.WebhookContext;
import com.skillforge.server.channel.spi.WebhookVerificationException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * Strict (B2 fix) signature verifier for Feishu interactive-card action callbacks.
 *
 * <p>Unlike {@link FeishuWebhookVerifier} which silently no-ops when
 * {@code encryptKey} is blank (tolerated for event webhooks in dev), this verifier
 * <b>refuses</b> missing {@code encryptKey}. The engine's prompter layer also refuses
 * to treat feishu as a usable channel when {@code encryptKey} is blank, so this is a
 * double-guard against accidental multi-user chat bypass.
 *
 * <p>Algorithm (Feishu docs:"消息卡片回传签名校验"):
 * <pre>
 *   X-Lark-Signature          = hex( SHA-256(timestamp + "\n" + nonce + "\n" + encryptKey + "\n" + body) )
 *   X-Lark-Request-Timestamp  (unix seconds, 300s window)
 *   X-Lark-Request-Nonce
 * </pre>
 * Same algorithm as the event webhook per current open docs; kept as a separate class so
 * that a future protocol divergence can be absorbed without touching
 * {@link FeishuWebhookVerifier}.
 */
@Component
public class FeishuCardActionVerifier {

    private static final long TIMESTAMP_WINDOW_SEC = 300;

    public void verifyStrict(WebhookContext ctx, String encryptKey) {
        if (encryptKey == null || encryptKey.isBlank()) {
            throw new WebhookVerificationException("feishu-card-action",
                    "encryptKey not configured; card-action callback strict-mode requires encryptKey");
        }
        String timestamp = ctx.header("x-lark-request-timestamp");
        String nonce = ctx.header("x-lark-request-nonce");
        String signature = ctx.header("x-lark-signature");
        if (timestamp == null || nonce == null || signature == null) {
            throw new WebhookVerificationException("feishu-card-action", "missing required headers");
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            throw new WebhookVerificationException("feishu-card-action", "invalid timestamp");
        }
        if (Math.abs(Instant.now().getEpochSecond() - ts) > TIMESTAMP_WINDOW_SEC) {
            throw new WebhookVerificationException("feishu-card-action", "timestamp out of window");
        }
        String bodyString = new String(ctx.rawBody() != null ? ctx.rawBody() : new byte[0],
                StandardCharsets.UTF_8);
        String expected = FeishuSignatures.sign(timestamp, nonce, encryptKey, bodyString);
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = signature.toLowerCase().getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedBytes, actualBytes)) {
            throw new WebhookVerificationException("feishu-card-action", "SHA-256 mismatch");
        }
    }
}
