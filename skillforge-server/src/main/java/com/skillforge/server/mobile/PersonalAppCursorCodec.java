package com.skillforge.server.mobile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Base64;

import org.springframework.stereotype.Component;

/** Device-token-scoped, restart-stable signed keyset cursor for the Personal App Library. */
@Component
public final class PersonalAppCursorCodec {

    private static final int VERSION = 1;
    private static final int MAX_CURSOR_LENGTH = 4_096;
    private static final byte[] KEY_DOMAIN =
            "skillforge.personal-app-cursor.v1\0".getBytes(StandardCharsets.UTF_8);
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final ObjectMapper objectMapper;

    public PersonalAppCursorCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encode(String bearerToken, Cursor cursor) {
        if (cursor == null || cursor.sortTimestamp() == null
                || blank(cursor.artifactId()) || blank(cursor.filterBinding())) {
            throw new IllegalArgumentException("Invalid cursor");
        }
        try {
            byte[] payload = objectMapper.writeValueAsBytes(new Payload(
                    VERSION,
                    cursor.sortTimestamp().toString(),
                    cursor.artifactId(),
                    cursor.filterBinding()));
            byte[] signature = sign(bearerToken, payload);
            try {
                return ENCODER.encodeToString(payload) + "." + ENCODER.encodeToString(signature);
            } finally {
                Arrays.fill(signature, (byte) 0);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to encode personal app cursor", e);
        }
    }

    public Cursor decode(String bearerToken, String encoded) {
        if (blank(bearerToken) || blank(encoded) || encoded.length() > MAX_CURSOR_LENGTH) {
            throw invalid();
        }
        try {
            int separator = encoded.indexOf('.');
            if (separator <= 0 || separator != encoded.lastIndexOf('.')
                    || separator == encoded.length() - 1) {
                throw invalid();
            }
            byte[] payload = DECODER.decode(encoded.substring(0, separator));
            byte[] provided = DECODER.decode(encoded.substring(separator + 1));
            if (payload.length == 0 || payload.length > 2_048 || provided.length != 32) {
                throw invalid();
            }
            byte[] expected = sign(bearerToken, payload);
            try {
                if (!MessageDigest.isEqual(expected, provided)) {
                    throw invalid();
                }
            } finally {
                Arrays.fill(expected, (byte) 0);
                Arrays.fill(provided, (byte) 0);
            }

            JsonNode node = objectMapper.readTree(payload);
            if (node == null || !node.isObject() || node.size() != 4
                    || node.path("v").asInt(-1) != VERSION
                    || !node.path("ts").isTextual()
                    || !node.path("id").isTextual()
                    || !node.path("bind").isTextual()) {
                throw invalid();
            }
            String artifactId = node.path("id").textValue();
            String binding = node.path("bind").textValue();
            if (blank(artifactId) || artifactId.length() > 200
                    || blank(binding) || binding.length() > 128) {
                throw invalid();
            }
            return new Cursor(
                    Instant.parse(node.path("ts").textValue()),
                    artifactId,
                    binding);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw invalid();
        } catch (Exception e) {
            throw invalid();
        }
    }

    private static byte[] sign(String bearerToken, byte[] payload) throws GeneralSecurityException {
        if (blank(bearerToken)) throw invalid();
        byte[] tokenBytes = bearerToken.getBytes(StandardCharsets.UTF_8);
        byte[] key = null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(KEY_DOMAIN);
            key = digest.digest(tokenBytes);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(payload);
        } finally {
            Arrays.fill(tokenBytes, (byte) 0);
            if (key != null) Arrays.fill(key, (byte) 0);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid cursor");
    }

    private record Payload(int v, String ts, String id, String bind) { }

    public record Cursor(Instant sortTimestamp, String artifactId, String filterBinding) { }
}
