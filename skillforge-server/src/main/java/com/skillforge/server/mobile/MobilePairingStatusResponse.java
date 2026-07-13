package com.skillforge.server.mobile;

import java.time.Instant;
import java.util.UUID;

public record MobilePairingStatusResponse(
        UUID pairingId,
        String status,
        UUID claimedDeviceId,
        Instant expiresAt,
        Instant claimedAt) {
}
