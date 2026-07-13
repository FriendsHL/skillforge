package com.skillforge.server.mobile;

import java.time.Instant;
import java.util.UUID;

public record MobilePairingCreateResponse(
        UUID pairingId,
        String status,
        String setupCode,
        Instant expiresAt,
        MobilePairingQrPayload qrPayload) {
}
