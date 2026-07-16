package com.skillforge.server.mobile;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AppleApnsClient implements ApnsClient {
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Pattern REASON = Pattern.compile("\\\"reason\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private final HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final String teamId, keyId, topic, privateKeyPem;
    private volatile String cachedJwt;
    private volatile Instant cachedJwtAt = Instant.EPOCH;

    public AppleApnsClient(@Value("${skillforge.mobile.push.apns.team-id:}") String teamId,
                           @Value("${skillforge.mobile.push.apns.key-id:}") String keyId,
                           @Value("${skillforge.mobile.push.apns.topic:}") String topic,
                           @Value("${skillforge.mobile.push.apns.private-key:}") String privateKeyPem) {
        this.teamId = teamId; this.keyId = keyId; this.topic = topic; this.privateKeyPem = privateKeyPem;
    }

    @Override
    public ApnsResult send(String deviceToken, String environment, String payload) {
        if (!isConfigured()) return new ApnsResult(false, false, null, "APNs is not configured");
        String host = "production".equals(environment) ? "api.push.apple.com" : "api.sandbox.push.apple.com";
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://" + host + "/3/device/" + deviceToken))
                .timeout(Duration.ofSeconds(20))
                .header("authorization", "bearer " + jwt())
                .header("apns-topic", topic)
                .header("apns-push-type", "alert")
                .header("apns-priority", "10")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String apnsId = response.headers().firstValue("apns-id").orElse(null);
            if (response.statusCode() == 200) return new ApnsResult(true, false, apnsId, null);
            String reason = reason(response.body());
            boolean permanent = response.statusCode() == 400 || response.statusCode() == 410;
            return new ApnsResult(false, permanent, apnsId, reason != null ? reason : "HTTP " + response.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ApnsResult(false, false, null, "interrupted");
        } catch (Exception e) {
            return new ApnsResult(false, false, null, e.getClass().getSimpleName());
        }
    }

    @Override
    public boolean isConfigured() {
        return !blank(teamId) && !blank(keyId) && !blank(topic) && !blank(privateKeyPem);
    }

    private synchronized String jwt() {
        Instant now = Instant.now();
        if (cachedJwt != null && cachedJwtAt.plusSeconds(50 * 60).isAfter(now)) return cachedJwt;
        try {
            String header = b64("{\"alg\":\"ES256\",\"kid\":\"" + keyId + "\"}");
            String claims = b64("{\"iss\":\"" + teamId + "\",\"iat\":" + now.getEpochSecond() + "}");
            String signingInput = header + "." + claims;
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(readPrivateKey());
            signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            cachedJwt = signingInput + "." + B64.encodeToString(derToJose(signer.sign(), 64));
            cachedJwtAt = now;
            return cachedJwt;
        } catch (Exception e) {
            throw new IllegalStateException("failed to create APNs provider token", e);
        }
    }

    private PrivateKey readPrivateKey() throws Exception {
        String normalized = privateKeyPem.replace("\\n", "\n")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(normalized)));
    }

    static byte[] derToJose(byte[] der, int outputLength) {
        if (der.length < 8 || der[0] != 0x30) throw new IllegalArgumentException("invalid ECDSA signature");
        int offset = der[1] >= 0 ? 2 : 2 + (der[1] & 0x7f);
        if (der[offset++] != 0x02) throw new IllegalArgumentException("invalid ECDSA R");
        int rLen = der[offset++] & 0xff; byte[] r = java.util.Arrays.copyOfRange(der, offset, offset + rLen); offset += rLen;
        if (der[offset++] != 0x02) throw new IllegalArgumentException("invalid ECDSA S");
        int sLen = der[offset++] & 0xff; byte[] s = java.util.Arrays.copyOfRange(der, offset, offset + sLen);
        byte[] out = new byte[outputLength]; copyUnsigned(r, out, 0, outputLength / 2); copyUnsigned(s, out, outputLength / 2, outputLength / 2); return out;
    }

    private static void copyUnsigned(byte[] value, byte[] out, int offset, int length) {
        int source = value.length > length ? value.length - length : (value.length > 0 && value[0] == 0 ? 1 : 0);
        int count = Math.min(length, value.length - source);
        System.arraycopy(value, source, out, offset + length - count, count);
    }
    private static String b64(String s) { return B64.encodeToString(s.getBytes(StandardCharsets.UTF_8)); }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static String reason(String body) { if (body == null) return null; Matcher m = REASON.matcher(body); return m.find() ? m.group(1) : null; }
}
