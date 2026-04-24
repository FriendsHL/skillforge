package com.skillforge.server.channel.platform.feishu;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 共享的飞书 webhook / card-action 签名算法,避免 verifier 之间漂移 (B2 fix)。
 *
 * <p>飞书公开文档 "验证事件来源" / "互动卡片回传消息校验签名":
 * <pre>
 *   signature = hex( SHA-256( timestamp + "\n" + nonce + "\n" + encryptKey + "\n" + body ) )
 * </pre>
 */
final class FeishuSignatures {

    private FeishuSignatures() {}

    static String sign(String timestamp, String nonce, String encryptKey, String body) {
        String toSign = timestamp + "\n" + nonce + "\n" + encryptKey + "\n" + (body == null ? "" : body);
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest(toSign.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
