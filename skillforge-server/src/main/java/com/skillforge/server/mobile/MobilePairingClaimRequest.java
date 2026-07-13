package com.skillforge.server.mobile;

public record MobilePairingClaimRequest(
        String pairingSecret,
        String deviceName,
        String platform,
        String appVersion) {
}
