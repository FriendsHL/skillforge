package com.skillforge.server.mobile;

import com.skillforge.server.AbstractPostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Mobile repositories")
class MobileRepositoryIT extends AbstractPostgresIT {

    @Autowired
    private MobileDeviceRepository deviceRepository;

    @Autowired
    private MobilePairingRequestRepository pairingRepository;

    @BeforeEach
    void cleanUp() {
        pairingRepository.deleteAll();
        deviceRepository.deleteAll();
    }

    @Test
    @DisplayName("device repository persists token hash and status fields")
    void deviceRepository_persistsTokenHashAndStatusFields() {
        MobileDeviceEntity device = device(1L);

        deviceRepository.save(device);

        Optional<MobileDeviceEntity> found = deviceRepository.findByTokenHash("hash-1");
        assertThat(found).isPresent();
        assertThat(found.get().getDeviceName()).isEqualTo("Youren iPhone");
        assertThat(found.get().getStatus()).isEqualTo(MobileDeviceEntity.STATUS_ACTIVE);
        assertThat(deviceRepository.findByUserIdOrderByCreatedAtDesc(1L)).hasSize(1);
    }

    @Test
    @DisplayName("pairing repository persists pending request and claim link")
    void pairingRepository_persistsPendingRequestAndClaimLink() {
        MobileDeviceEntity device = device(1L);
        deviceRepository.save(device);
        MobilePairingRequestEntity pairing = new MobilePairingRequestEntity();
        pairing.setId(UUID.randomUUID());
        pairing.setUserId(1L);
        pairing.setSecretHash("secret-hash");
        pairing.setSetupCodeHash("setup-hash");
        pairing.setServerName("SkillForge Dev");
        pairing.setEndpointsJson("[\"http://192.168.1.10:8080\"]");
        pairing.setStatus(MobilePairingRequestEntity.STATUS_CLAIMED);
        pairing.setExpiresAt(Instant.parse("2026-07-09T06:05:00Z"));
        pairing.setClaimedDeviceId(device.getId());
        pairing.setCreatedAt(Instant.parse("2026-07-09T06:00:00Z"));
        pairing.setClaimedAt(Instant.parse("2026-07-09T06:01:00Z"));

        pairingRepository.save(pairing);

        List<MobilePairingRequestEntity> rows = pairingRepository.findByUserIdOrderByCreatedAtDesc(1L);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStatus()).isEqualTo(MobilePairingRequestEntity.STATUS_CLAIMED);
        assertThat(rows.get(0).getClaimedDeviceId()).isEqualTo(device.getId());
    }

    private MobileDeviceEntity device(Long userId) {
        MobileDeviceEntity device = new MobileDeviceEntity();
        device.setId(UUID.randomUUID());
        device.setUserId(userId);
        device.setDeviceName("Youren iPhone");
        device.setPlatform(MobileDeviceEntity.PLATFORM_IOS);
        device.setAppVersion("1.0.0");
        device.setTokenHash("hash-" + userId);
        device.setStatus(MobileDeviceEntity.STATUS_ACTIVE);
        device.setScopesJson("[\"chat:read\"]");
        device.setCreatedAt(Instant.parse("2026-07-09T06:00:00Z"));
        return device;
    }
}
