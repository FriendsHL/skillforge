package com.skillforge.server.mobile;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

@Component
public class MobilePushTokenCipher {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final byte[] key;

    public MobilePushTokenCipher(@Value("${skillforge.mobile.push.encryption-key:}") String encodedKey) {
        if (encodedKey == null || encodedKey.isBlank()) {
            key = null;
        } else {
            byte[] decoded = Base64.getDecoder().decode(encodedKey);
            if (decoded.length != 32) throw new IllegalStateException("mobile push encryption key must be 32 bytes");
            key = decoded;
        }
    }

    public boolean isConfigured() { return key != null; }

    public String encrypt(String rawToken) {
        requireConfigured();
        try {
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(rawToken.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, combined, 0, nonce.length);
            System.arraycopy(encrypted, 0, combined, nonce.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("failed to protect mobile push token", e);
        }
    }

    public String decrypt(String ciphertext) {
        requireConfigured();
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertext);
            if (combined.length < 29) throw new IllegalArgumentException("invalid protected push token");
            byte[] nonce = java.util.Arrays.copyOfRange(combined, 0, 12);
            byte[] encrypted = java.util.Arrays.copyOfRange(combined, 12, combined.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("failed to read protected mobile push token", e);
        }
    }

    public String hash(String rawToken) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("failed to hash mobile push token", e);
        }
    }

    private void requireConfigured() {
        if (key == null) throw new IllegalStateException("mobile push encryption is not configured");
    }
}
