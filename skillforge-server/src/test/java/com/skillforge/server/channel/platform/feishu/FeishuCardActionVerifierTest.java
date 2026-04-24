package com.skillforge.server.channel.platform.feishu;

import com.skillforge.server.channel.spi.WebhookContext;
import com.skillforge.server.channel.spi.WebhookVerificationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeishuCardActionVerifierTest {

    private final FeishuCardActionVerifier verifier = new FeishuCardActionVerifier();

    private static String sha256Hex(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    @Test
    @DisplayName("blank encryptKey → throws (strict mode)")
    void blankKeyThrows() {
        WebhookContext ctx = new WebhookContext(Map.of(), new byte[0]);
        assertThatThrownBy(() -> verifier.verifyStrict(ctx, null))
                .isInstanceOf(WebhookVerificationException.class);
        assertThatThrownBy(() -> verifier.verifyStrict(ctx, "   "))
                .isInstanceOf(WebhookVerificationException.class);
    }

    @Test
    @DisplayName("missing headers → throws")
    void missingHeaders() {
        WebhookContext ctx = new WebhookContext(Map.of(), new byte[0]);
        assertThatThrownBy(() -> verifier.verifyStrict(ctx, "key"))
                .isInstanceOf(WebhookVerificationException.class)
                .hasMessageContaining("missing required headers");
    }

    @Test
    @DisplayName("stale timestamp → throws (outside 300s window)")
    void staleTimestamp() {
        long stale = Instant.now().getEpochSecond() - 1000;
        Map<String, String> hdr = new HashMap<>();
        hdr.put("x-lark-request-timestamp", String.valueOf(stale));
        hdr.put("x-lark-request-nonce", "n");
        hdr.put("x-lark-signature", "whatever");
        WebhookContext ctx = new WebhookContext(hdr, "{}".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> verifier.verifyStrict(ctx, "key"))
                .isInstanceOf(WebhookVerificationException.class)
                .hasMessageContaining("timestamp out of window");
    }

    @Test
    @DisplayName("bad signature → throws")
    void badSignature() {
        long ts = Instant.now().getEpochSecond();
        Map<String, String> hdr = new HashMap<>();
        hdr.put("x-lark-request-timestamp", String.valueOf(ts));
        hdr.put("x-lark-request-nonce", "n");
        hdr.put("x-lark-signature", "0000000000000000000000000000000000000000000000000000000000000000");
        WebhookContext ctx = new WebhookContext(hdr, "{}".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> verifier.verifyStrict(ctx, "key"))
                .isInstanceOf(WebhookVerificationException.class)
                .hasMessageContaining("SHA-256 mismatch");
    }

    @Test
    @DisplayName("good signature → no exception")
    void happy() throws Exception {
        long ts = Instant.now().getEpochSecond();
        String nonce = "n1";
        String key = "my-encrypt-key";
        String body = "{\"a\":1}";
        String sig = sha256Hex(ts + "\n" + nonce + "\n" + key + "\n" + body);
        Map<String, String> hdr = new HashMap<>();
        hdr.put("x-lark-request-timestamp", String.valueOf(ts));
        hdr.put("x-lark-request-nonce", nonce);
        hdr.put("x-lark-signature", sig);
        WebhookContext ctx = new WebhookContext(hdr, body.getBytes(StandardCharsets.UTF_8));
        verifier.verifyStrict(ctx, key);
        assertThat(true).isTrue(); // no-throw
    }
}
