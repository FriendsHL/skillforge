package com.skillforge.core.context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Stable hashes for content-free prompt observation.
 */
public final class PromptObservationHashes {

    private PromptObservationHashes() {
    }

    public static String sha256(String value) {
        if (value == null) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossibleOnJava17) {
            throw new IllegalStateException("SHA-256 is unavailable", impossibleOnJava17);
        }
    }
}
