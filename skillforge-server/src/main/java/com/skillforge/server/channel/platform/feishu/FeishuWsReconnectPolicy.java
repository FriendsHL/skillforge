package com.skillforge.server.channel.platform.feishu;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Value object for WS reconnect backoff.
 * baseMs=1s, maxMs=64s, jitterRatio=20% by default.
 */
public record FeishuWsReconnectPolicy(
        long baseMs,
        long maxMs,
        double jitterRatio
) {
    public static FeishuWsReconnectPolicy defaultPolicy() {
        return new FeishuWsReconnectPolicy(1_000L, 64_000L, 0.2d);
    }

    public long nextDelayMs(int attempt) {
        int safeAttempt = Math.max(0, attempt);
        long backoff = baseMs;
        for (int i = 0; i < safeAttempt; i++) {
            if (backoff >= maxMs) {
                backoff = maxMs;
                break;
            }
            long doubled = backoff * 2L;
            backoff = Math.min(maxMs, doubled);
        }

        if (jitterRatio <= 0) {
            return backoff;
        }
        double jitterFactor = 1d + ThreadLocalRandom.current().nextDouble(-jitterRatio, jitterRatio);
        long jittered = Math.round(backoff * jitterFactor);
        return Math.max(1L, jittered);
    }
}
