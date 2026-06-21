package com.skillforge.server.channel.platform.weixin;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Reversible codec for the weixin {@code platformMessageId}.
 *
 * <p>The SkillForge reply pipeline only carries {@code inboundMessageId} (= platformMessageId)
 * and {@code conversationId} to {@code deliver()}; {@code rawFields} are dropped. To echo the
 * inbound {@code context_token} on the reply (INV-2) and address {@code to_user_id}, we pack
 * {@code from_user_id} + {@code context_token} + a stable per-message component into the
 * platformMessageId and unpack them at delivery time.
 *
 * <p>Format: {@code wx1|<b64url(fromUserId)>|<b64url(contextToken)>|<stableId>}. Each variable
 * segment is Base64-URL encoded so the literal {@code |} delimiter never appears inside a
 * segment. Stays well under the 256-char dedup PK limit for realistic ids/tokens.
 */
final class WeixinIlinkIds {

    private static final String PREFIX = "wx1";
    private static final String SEP = "|";
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();

    /**
     * Safe upper bound for the encoded id. The DB columns are VARCHAR(1024) (V156); we fail loud
     * below that so an oversized context_token surfaces as an error rather than a truncated /
     * overflowing INSERT that silently drops the message.
     */
    static final int MAX_ENCODED_LEN = 1000;

    private WeixinIlinkIds() {}

    static String encode(String fromUserId, String contextToken, String stableId) {
        String encoded = PREFIX + SEP
                + b64(fromUserId) + SEP
                + b64(contextToken) + SEP
                + (stableId == null ? "" : stableId);
        if (encoded.length() > MAX_ENCODED_LEN) {
            // Fail loud: the caller's catch logs the exception class + id so the drop is diagnosable.
            throw new WeixinIlinkClient.WeixinIlinkException(
                    "weixin platformMessageId too long (" + encoded.length()
                            + " > " + MAX_ENCODED_LEN + "); context_token oversized");
        }
        return encoded;
    }

    /** @return decoded parts, or null if the id is not a weixin-encoded id. */
    static Decoded decode(String platformMessageId) {
        if (platformMessageId == null) {
            return null;
        }
        String[] parts = platformMessageId.split("\\" + SEP, 4);
        if (parts.length < 3 || !PREFIX.equals(parts[0])) {
            return null;
        }
        String fromUserId = unb64(parts[1]);
        String contextToken = unb64(parts[2]);
        return new Decoded(fromUserId, contextToken);
    }

    private static String b64(String s) {
        return ENC.encodeToString((s == null ? "" : s).getBytes(StandardCharsets.UTF_8));
    }

    private static String unb64(String s) {
        try {
            return new String(DEC.decode(s), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    record Decoded(String fromUserId, String contextToken) {}
}
