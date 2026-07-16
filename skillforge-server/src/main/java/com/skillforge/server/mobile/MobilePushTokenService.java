package com.skillforge.server.mobile;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class MobilePushTokenService {
    private static final Pattern TOKEN = Pattern.compile("[0-9a-fA-F]{64,200}");
    private final MobilePushTokenRepository tokenRepository;
    private final MobileDeviceRepository deviceRepository;
    private final MobilePushTokenCipher cipher;

    public MobilePushTokenService(MobilePushTokenRepository tokenRepository,
                                  MobileDeviceRepository deviceRepository,
                                  MobilePushTokenCipher cipher) {
        this.tokenRepository = tokenRepository;
        this.deviceRepository = deviceRepository;
        this.cipher = cipher;
    }

    @Transactional
    public MobilePushTokenResponse register(MobileDevicePrincipal principal, String rawToken, String environment) {
        if (principal == null) throw new IllegalArgumentException("mobile principal is required");
        String normalized = rawToken == null ? "" : rawToken.replaceAll("[\\s<>]", "").toLowerCase(Locale.ROOT);
        if (!TOKEN.matcher(normalized).matches()) throw new IllegalArgumentException("invalid APNs device token");
        if (!"development".equals(environment) && !"production".equals(environment)) {
            throw new IllegalArgumentException("environment must be development or production");
        }
        if (!cipher.isConfigured()) throw new IllegalStateException("mobile push encryption is not configured");
        MobileDeviceEntity device = deviceRepository.findById(principal.deviceId())
                .filter(d -> d.getUserId().equals(principal.userId()))
                .filter(d -> MobileDeviceEntity.STATUS_ACTIVE.equals(d.getStatus()))
                .orElseThrow(() -> new IllegalArgumentException("mobile device is not active"));
        Instant now = Instant.now();
        MobilePushTokenEntity row = tokenRepository.findByDeviceIdAndEnvironment(device.getId(), environment)
                .orElseGet(() -> {
                    MobilePushTokenEntity created = new MobilePushTokenEntity();
                    created.setId(UUID.randomUUID());
                    created.setDeviceId(device.getId());
                    created.setEnvironment(environment);
                    return created;
                });
        row.setTokenHash(cipher.hash(normalized));
        row.setTokenCiphertext(cipher.encrypt(normalized));
        row.setStatus(MobilePushTokenEntity.STATUS_ACTIVE);
        row.setInvalidatedAt(null);
        row.setLastRegisteredAt(now);
        tokenRepository.save(row);
        return new MobilePushTokenResponse(row.getId(), environment, row.getStatus(), now);
    }

    @Transactional
    public void unregister(MobileDevicePrincipal principal, String environment) {
        tokenRepository.findByDeviceIdAndEnvironment(principal.deviceId(), environment).ifPresent(row -> {
            row.setStatus(MobilePushTokenEntity.STATUS_INACTIVE);
            row.setInvalidatedAt(Instant.now());
            tokenRepository.save(row);
        });
    }
}
