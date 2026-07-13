package com.skillforge.server.mobile;

import java.util.UUID;

public record MobilePairingClaimResponse(
        UUID deviceId,
        String deviceToken,
        String serverName,
        MobileUserResponse user,
        MobileAgentResponse defaultAgent,
        MobileFeatureFlags features) {
}
