package com.skillforge.server.channel.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * CHANNEL-MIDTURN-PROGRESS: per-channel mid-turn progress delivery settings,
 * parsed from a {@code progressDelivery} block inside the channel config JSON
 * ({@code ChannelConfigDecrypted.configJson()}).
 *
 * <pre>
 * progressDelivery: { enabled, minIntervalMs, maxPerRun, minChars, prefix }
 * </pre>
 *
 * <p>Defaults are platform-aware and centralized here:
 * <ul>
 *   <li>feishu — {@code enabled:true} (IM is robust to a few progress pings)</li>
 *   <li>weixin — {@code enabled:false} by default (OQ-3: iLink personal-account
 *       rate-limit / ban risk; opt-in only)</li>
 *   <li>any other / unknown platform — {@code enabled:false} (conservative)</li>
 * </ul>
 *
 * <p>A missing {@code progressDelivery} block, a missing individual field, or
 * unparseable JSON all fall back to the platform default. Numeric fields are
 * clamped to sane lower bounds so a misconfiguration can't disable throttling.
 */
public record ProgressDeliveryConfig(
        boolean enabled,
        long minIntervalMs,
        int maxPerRun,
        int minChars,
        String prefix
) {

    private static final long DEFAULT_MIN_INTERVAL_MS = 2000L;
    private static final int DEFAULT_MAX_PER_RUN = 12;
    private static final int DEFAULT_MIN_CHARS = 8;
    private static final String DEFAULT_PREFIX = "🔄 ";

    static final String PLATFORM_FEISHU = "feishu";

    /** Platform default when no {@code progressDelivery} block is present. */
    public static ProgressDeliveryConfig platformDefault(String platform) {
        boolean enabledByDefault = PLATFORM_FEISHU.equalsIgnoreCase(platform);
        return new ProgressDeliveryConfig(
                enabledByDefault,
                DEFAULT_MIN_INTERVAL_MS,
                DEFAULT_MAX_PER_RUN,
                DEFAULT_MIN_CHARS,
                DEFAULT_PREFIX);
    }

    /**
     * Parse the {@code progressDelivery} block from a channel config JSON string,
     * falling back to the platform default for any missing piece. Never throws —
     * invalid JSON yields the platform default.
     */
    public static ProgressDeliveryConfig parse(String configJson, String platform, ObjectMapper mapper) {
        ProgressDeliveryConfig fallback = platformDefault(platform);
        if (configJson == null || configJson.isBlank()) {
            return fallback;
        }
        try {
            JsonNode root = mapper.readTree(configJson);
            JsonNode pd = root.path("progressDelivery");
            if (pd.isMissingNode() || !pd.isObject()) {
                return fallback;
            }
            boolean enabled = pd.path("enabled").asBoolean(fallback.enabled());
            long minIntervalMs = pd.path("minIntervalMs").asLong(fallback.minIntervalMs());
            int maxPerRun = pd.path("maxPerRun").asInt(fallback.maxPerRun());
            int minChars = pd.path("minChars").asInt(fallback.minChars());
            String prefix = pd.path("prefix").isTextual() ? pd.path("prefix").asText() : fallback.prefix();
            // Clamp to non-negative so a bad config can't break throttling math.
            minIntervalMs = Math.max(0L, minIntervalMs);
            maxPerRun = Math.max(0, maxPerRun);
            minChars = Math.max(0, minChars);
            if (prefix == null) {
                prefix = "";
            }
            return new ProgressDeliveryConfig(enabled, minIntervalMs, maxPerRun, minChars, prefix);
        } catch (Exception e) {
            return fallback;
        }
    }
}
