package com.skillforge.server.session;

import com.skillforge.core.engine.durability.PersistedBlockOccurrence;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Version-1 domain-separated, length-framed hash of exact Tool result scalar bytes. */
@Component
public class ArchivePayloadIdentityHasher {

    public static final short VERSION = 1;
    private static final byte[] DOMAIN =
            "skillforge:tool-result-archive:v1".getBytes(StandardCharsets.UTF_8);

    public String hash(PersistedBlockOccurrence occurrence) {
        return hash(
                occurrence.toolUseId(), occurrence.content(),
                occurrence.error(), occurrence.errorType());
    }

    /** Hashes the exact persisted logical scalars without requiring a persistence carrier. */
    public String hash(String toolUseId, String content, boolean error, String errorType) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(DOMAIN);
            digest.update((byte) 0);
            updateField(digest, "toolUseId", toolUseId);
            updateField(digest, "content", content);
            updateField(digest, "isError", error ? "true" : "false");
            updateField(digest, "errorType", errorType);
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private static void updateField(MessageDigest digest, String tag, String value) {
        byte[] tagBytes = strictUtf8(tag);
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(tagBytes.length).array());
        digest.update(tagBytes);
        if (value == null) {
            digest.update((byte) 0);
            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(0L).array());
            return;
        }
        byte[] valueBytes = strictUtf8(value);
        digest.update((byte) 1);
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(valueBytes.length).array());
        digest.update(valueBytes);
    }

    private static byte[] strictUtf8(String value) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException invalidUnicode) {
            throw new IllegalArgumentException("Tool result payload is not valid Unicode");
        }
    }
}
