package com.skillforge.server.channel.spi;

/**
 * Platform delivery result. Carries the platform-reported retry wait hint.
 */
public record DeliveryResult(
        boolean success,
        /** Platform-reported retry delay in ms; overrides default backoff when > 0. */
        long retryAfterMs,
        /** True = permanent failure (e.g. invalid token); do not retry. */
        boolean permanent,
        String errorMessage
) {
    public static DeliveryResult ok() {
        return new DeliveryResult(true, 0, false, null);
    }

    public static DeliveryResult retry(long retryAfterMs, String reason) {
        return new DeliveryResult(false, retryAfterMs, false, reason);
    }

    public static DeliveryResult failed(String reason) {
        return new DeliveryResult(false, 0, true, reason);
    }
}
