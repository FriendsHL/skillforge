package com.skillforge.server.mobile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.entity.AgentEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

@DisplayName("MobilePairingService")
class MobilePairingServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-09T06:00:00Z"), ZoneOffset.UTC);

    private MobilePairingRequestRepository pairingRepository;
    private MobileDeviceRepository deviceRepository;
    private MobileAgentAccessService mobileAgentAccessService;
    private MobileTokenService tokenService;
    private MobilePairingService service;

    @BeforeEach
    void setUp() {
        pairingRepository = mock(MobilePairingRequestRepository.class);
        deviceRepository = mock(MobileDeviceRepository.class);
        mobileAgentAccessService = mock(MobileAgentAccessService.class);
        tokenService = new MobileTokenService();
        service = new MobilePairingService(
                pairingRepository,
                deviceRepository,
                mobileAgentAccessService,
                tokenService,
                new ObjectMapper(),
                CLOCK);
    }

    @Test
    @DisplayName("createPairing stores only secret/setup hashes and returns QR payload")
    void createPairing_storesHashesAndReturnsQrPayload() {
        when(pairingRepository.save(any(MobilePairingRequestEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        MobilePairingCreateResponse response = service.createPairing(
                1L,
                "SkillForge Dev",
                List.of("http://192.168.1.10:8080"));

        assertThat(response.pairingId()).isNotNull();
        assertThat(response.setupCode()).matches("\\d{6}");
        assertThat(response.expiresAt()).isAfter(CLOCK.instant());
        assertThat(response.qrPayload().type()).isEqualTo("skillforge.mobile_pairing");
        assertThat(response.qrPayload().pairingSecret()).isNotBlank();
        assertThat(response.qrPayload().endpoints()).containsExactly("http://192.168.1.10:8080");

        verify(pairingRepository).save(any(MobilePairingRequestEntity.class));
    }

    @Test
    @DisplayName("claimPairing creates active device and marks pairing claimed")
    void claimPairing_createsDeviceAndMarksPairingClaimed() {
        UUID pairingId = UUID.randomUUID();
        MobilePairingRequestEntity pairing = pendingPairing(pairingId, "secret");
        when(pairingRepository.findByIdForUpdate(pairingId)).thenReturn(Optional.of(pairing));
        when(deviceRepository.save(any(MobileDeviceEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(pairingRepository.save(any(MobilePairingRequestEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(mobileAgentAccessService.findSelectableDefaultAgent(1L))
                .thenReturn(Optional.of(agent(3L, "Main Assistant")));

        MobilePairingClaimResponse response = service.claimPairing(
                pairingId,
                new MobilePairingClaimRequest("secret", "Youren iPhone", "ios", "1.0.0"));

        assertThat(response.deviceId()).isNotNull();
        assertThat(response.deviceToken()).isNotBlank();
        assertThat(response.deviceToken()).isNotEqualTo(response.deviceId().toString());
        assertThat(response.defaultAgent().id()).isEqualTo(3L);
        assertThat(pairing.getStatus()).isEqualTo(MobilePairingRequestEntity.STATUS_CLAIMED);
        assertThat(pairing.getClaimedDeviceId()).isEqualTo(response.deviceId());
        assertThat(pairing.getClaimedAt()).isEqualTo(CLOCK.instant());

        verify(deviceRepository).save(any(MobileDeviceEntity.class));
        verify(pairingRepository).save(pairing);

        ArgumentCaptor<MobileDeviceEntity> deviceCaptor = ArgumentCaptor.forClass(MobileDeviceEntity.class);
        verify(deviceRepository).save(deviceCaptor.capture());
        assertThat(deviceCaptor.getValue().getScopesJson())
                .contains("schedule:read", "schedule:write", "agent:read");
    }

    @Test
    @DisplayName("claimPairing rejects wrong or expired secrets without creating device")
    void claimPairing_rejectsInvalidSecretWithoutCreatingDevice() {
        UUID pairingId = UUID.randomUUID();
        when(pairingRepository.findByIdForUpdate(pairingId)).thenReturn(Optional.of(pendingPairing(pairingId, "secret")));

        assertThatThrownBy(() -> service.claimPairing(
                pairingId,
                new MobilePairingClaimRequest("wrong", "Youren iPhone", "ios", "1.0.0")))
                .isInstanceOf(MobilePairingException.class)
                .hasMessageContaining("invalid");

        verify(deviceRepository, never()).save(any());
    }

    @Test
    @DisplayName("getPairingStatus flips expired pending rows to expired")
    void getPairingStatus_marksExpiredPendingRows() {
        UUID pairingId = UUID.randomUUID();
        MobilePairingRequestEntity pairing = pendingPairing(pairingId, "secret");
        pairing.setExpiresAt(CLOCK.instant().minusSeconds(1));
        when(pairingRepository.findById(pairingId)).thenReturn(Optional.of(pairing));

        MobilePairingStatusResponse response = service.getPairingStatus(1L, pairingId);

        assertThat(response.status()).isEqualTo(MobilePairingRequestEntity.STATUS_EXPIRED);
        assertThat(pairing.getStatus()).isEqualTo(MobilePairingRequestEntity.STATUS_EXPIRED);
        verify(pairingRepository).save(pairing);
    }

    private MobilePairingRequestEntity pendingPairing(UUID pairingId, String rawSecret) {
        MobilePairingRequestEntity pairing = new MobilePairingRequestEntity();
        pairing.setId(pairingId);
        pairing.setUserId(1L);
        pairing.setSecretHash(tokenService.hashToken(rawSecret));
        pairing.setSetupCodeHash(tokenService.hashToken("123456"));
        pairing.setServerName("SkillForge Dev");
        pairing.setEndpointsJson("[\"http://192.168.1.10:8080\"]");
        pairing.setStatus(MobilePairingRequestEntity.STATUS_PENDING);
        pairing.setExpiresAt(CLOCK.instant().plusSeconds(300));
        pairing.setCreatedAt(CLOCK.instant());
        return pairing;
    }

    private AgentEntity agent(Long id, String name) {
        AgentEntity agent = new AgentEntity();
        agent.setId(id);
        agent.setName(name);
        return agent;
    }
}
