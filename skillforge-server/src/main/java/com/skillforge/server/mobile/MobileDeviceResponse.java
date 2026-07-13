package com.skillforge.server.mobile;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record MobileDeviceResponse(
        UUID id,
        String deviceName,
        String platform,
        String appVersion,
        String status,
        Set<String> scopes,
        Instant lastSeenAt,
        Instant createdAt,
        Instant revokedAt) {
}
