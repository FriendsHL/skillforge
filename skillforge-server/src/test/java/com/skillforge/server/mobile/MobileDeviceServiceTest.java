package com.skillforge.server.mobile;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("MobileDeviceService")
class MobileDeviceServiceTest {

    private MobileDeviceRepository deviceRepository;
    private MobileTokenService tokenService;
    private MobileDeviceService service;

    @BeforeEach
    void setUp() {
        deviceRepository = mock(MobileDeviceRepository.class);
        tokenService = new MobileTokenService();
        service = new MobileDeviceService(deviceRepository, tokenService, new ObjectMapper());
    }

    @Test
    @DisplayName("authenticate returns principal for active token and updates lastSeenAt")
    void authenticate_returnsPrincipalForActiveToken() {
        MobileDeviceEntity device = device("active");
        when(deviceRepository.findByTokenHash(tokenService.hashToken("raw-token")))
                .thenReturn(Optional.of(device));

        Optional<MobileDevicePrincipal> principal = service.authenticate("raw-token");

        assertThat(principal).isPresent();
        assertThat(principal.get().deviceId()).isEqualTo(device.getId());
        assertThat(principal.get().userId()).isEqualTo(1L);
        assertThat(principal.get().scopes()).contains("chat:read");
        assertThat(device.getLastSeenAt()).isNotNull();
        verify(deviceRepository).save(device);
    }

    @Test
    @DisplayName("authenticate rejects revoked tokens")
    void authenticate_rejectsRevokedTokens() {
        MobileDeviceEntity device = device("revoked");
        when(deviceRepository.findByTokenHash(tokenService.hashToken("raw-token")))
                .thenReturn(Optional.of(device));

        Optional<MobileDevicePrincipal> principal = service.authenticate("raw-token");

        assertThat(principal).isEmpty();
    }

    @Test
    @DisplayName("revokeDevice marks an owned active device revoked")
    void revokeDevice_marksOwnedDeviceRevoked() {
        UUID deviceId = UUID.randomUUID();
        MobileDeviceEntity device = device("active");
        device.setId(deviceId);
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));

        MobileDeviceEntity revoked = service.revokeDevice(1L, deviceId);

        assertThat(revoked.getStatus()).isEqualTo(MobileDeviceEntity.STATUS_REVOKED);
        assertThat(revoked.getRevokedAt()).isNotNull();
        verify(deviceRepository).save(device);
    }

    private MobileDeviceEntity device(String status) {
        MobileDeviceEntity device = new MobileDeviceEntity();
        device.setId(UUID.randomUUID());
        device.setUserId(1L);
        device.setDeviceName("Youren iPhone");
        device.setPlatform("ios");
        device.setAppVersion("1.0.0");
        device.setTokenHash(tokenService.hashToken("raw-token"));
        device.setStatus(status);
        device.setScopesJson("[\"chat:read\",\"chat:write\"]");
        device.setCreatedAt(Instant.parse("2026-07-09T06:00:00Z"));
        return device;
    }
}
