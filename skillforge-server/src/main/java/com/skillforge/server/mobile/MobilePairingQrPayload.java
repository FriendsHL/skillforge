package com.skillforge.server.mobile;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MobilePairingQrPayload(
        String type,
        int version,
        String serverName,
        UUID pairingId,
        String pairingSecret,
        List<String> endpoints,
        Instant expiresAt) {
}
