package com.skillforge.server.channel.spi;

/**
 * Adapter-facing view of decrypted config. Adapter never sees the Entity.
 */
public record ChannelConfigDecrypted(
        Long id,
        String platform,
        /** webhook signature secret (feishu encryptKey / telegram secret_token). */
        String webhookSecret,
        /** Platform API credentials (JSON, decrypted). */
        String credentialsJson,
        /** Non-sensitive extras (JSON, plaintext). */
        String configJson,
        Long defaultAgentId
) {}
