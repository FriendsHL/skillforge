package com.skillforge.server.mobile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.entity.AgentEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class MobilePairingService {

    private static final String QR_TYPE = "skillforge.mobile_pairing";
    private static final int QR_VERSION = 1;
    private static final long PAIRING_TTL_SECONDS = 300;
    private static final List<String> DEFAULT_SCOPES = List.of(
            "chat:read",
            "chat:write",
            "confirmation:answer",
            "attachment:upload",
            "push:register",
            "schedule:read",
            "schedule:write",
            "agent:read");
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final MobilePairingRequestRepository pairingRepository;
    private final MobileDeviceRepository deviceRepository;
    private final MobileAgentAccessService mobileAgentAccessService;
    private final MobileTokenService tokenService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    public MobilePairingService(MobilePairingRequestRepository pairingRepository,
                                MobileDeviceRepository deviceRepository,
                                MobileAgentAccessService mobileAgentAccessService,
                                MobileTokenService tokenService,
                                ObjectMapper objectMapper) {
        this(pairingRepository, deviceRepository, mobileAgentAccessService, tokenService, objectMapper, Clock.systemUTC());
    }

    MobilePairingService(MobilePairingRequestRepository pairingRepository,
                         MobileDeviceRepository deviceRepository,
                         MobileAgentAccessService mobileAgentAccessService,
                         MobileTokenService tokenService,
                         ObjectMapper objectMapper,
                         Clock clock) {
        this.pairingRepository = pairingRepository;
        this.deviceRepository = deviceRepository;
        this.mobileAgentAccessService = mobileAgentAccessService;
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public MobilePairingCreateResponse createPairing(Long userId, String serverName, List<String> endpoints) {
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }
        List<String> cleanEndpoints = cleanEndpoints(endpoints);
        if (cleanEndpoints.isEmpty()) {
            throw new IllegalArgumentException("at least one endpoint is required");
        }
        String cleanServerName = serverName == null || serverName.isBlank() ? "SkillForge" : serverName.trim();
        UUID pairingId = UUID.randomUUID();
        String rawSecret = tokenService.newToken();
        String setupCode = String.format("%06d", secureRandom.nextInt(1_000_000));
        Instant now = clock.instant();
        Instant expiresAt = now.plusSeconds(PAIRING_TTL_SECONDS);

        MobilePairingRequestEntity pairing = new MobilePairingRequestEntity();
        pairing.setId(pairingId);
        pairing.setUserId(userId);
        pairing.setSecretHash(tokenService.hashToken(rawSecret));
        pairing.setSetupCodeHash(tokenService.hashToken(setupCode));
        pairing.setServerName(cleanServerName);
        pairing.setEndpointsJson(writeJson(cleanEndpoints));
        pairing.setStatus(MobilePairingRequestEntity.STATUS_PENDING);
        pairing.setExpiresAt(expiresAt);
        pairing.setCreatedAt(now);
        pairingRepository.save(pairing);

        MobilePairingQrPayload qrPayload = new MobilePairingQrPayload(
                QR_TYPE,
                QR_VERSION,
                cleanServerName,
                pairingId,
                rawSecret,
                cleanEndpoints,
                expiresAt);
        return new MobilePairingCreateResponse(
                pairingId,
                pairing.getStatus(),
                setupCode,
                expiresAt,
                qrPayload);
    }

    @Transactional
    public MobilePairingClaimResponse claimPairing(UUID pairingId, MobilePairingClaimRequest request) {
        MobilePairingRequestEntity pairing = pairingRepository.findByIdForUpdate(pairingId)
                .orElseThrow(() -> new MobilePairingException("pairing not found"));
        ensureClaimable(pairing, request);

        String rawDeviceToken = tokenService.newToken();
        Instant now = clock.instant();
        MobileDeviceEntity device = new MobileDeviceEntity();
        device.setId(UUID.randomUUID());
        device.setUserId(pairing.getUserId());
        device.setDeviceName(cleanDeviceName(request.deviceName()));
        device.setPlatform(MobileDeviceEntity.PLATFORM_IOS);
        device.setAppVersion(request.appVersion());
        device.setTokenHash(tokenService.hashToken(rawDeviceToken));
        device.setStatus(MobileDeviceEntity.STATUS_ACTIVE);
        device.setScopesJson(writeJson(DEFAULT_SCOPES));
        device.setCreatedAt(now);
        device = deviceRepository.save(device);

        pairing.setStatus(MobilePairingRequestEntity.STATUS_CLAIMED);
        pairing.setClaimedAt(now);
        pairing.setClaimedDeviceId(device.getId());
        pairingRepository.save(pairing);

        MobileAgentResponse defaultAgent = mobileAgentAccessService.findSelectableDefaultAgent(pairing.getUserId())
                .map(this::toAgentResponse)
                .orElse(new MobileAgentResponse(null, "Main Assistant"));
        return new MobilePairingClaimResponse(
                device.getId(),
                rawDeviceToken,
                pairing.getServerName(),
                new MobileUserResponse(pairing.getUserId()),
                defaultAgent,
                new MobileFeatureFlags(true, true, false, false));
    }

    @Transactional
    public MobilePairingStatusResponse getPairingStatus(Long userId, UUID pairingId) {
        MobilePairingRequestEntity pairing = pairingRepository.findById(pairingId)
                .orElseThrow(() -> new MobilePairingException("pairing not found"));
        if (!userId.equals(pairing.getUserId())) {
            throw new MobilePairingException("pairing not found");
        }
        if (MobilePairingRequestEntity.STATUS_PENDING.equals(pairing.getStatus())
                && !pairing.getExpiresAt().isAfter(clock.instant())) {
            pairing.setStatus(MobilePairingRequestEntity.STATUS_EXPIRED);
            pairingRepository.save(pairing);
        }
        return toStatusResponse(pairing);
    }

    private void ensureClaimable(MobilePairingRequestEntity pairing, MobilePairingClaimRequest request) {
        if (!MobilePairingRequestEntity.STATUS_PENDING.equals(pairing.getStatus())) {
            throw new MobilePairingException("pairing is not pending");
        }
        if (!pairing.getExpiresAt().isAfter(clock.instant())) {
            pairing.setStatus(MobilePairingRequestEntity.STATUS_EXPIRED);
            pairingRepository.save(pairing);
            throw new MobilePairingException("pairing is expired");
        }
        if (request == null || !tokenService.matchesToken(request.pairingSecret(), pairing.getSecretHash())) {
            throw new MobilePairingException("invalid pairing secret");
        }
        if (!MobileDeviceEntity.PLATFORM_IOS.equals(request.platform())) {
            throw new MobilePairingException("unsupported platform");
        }
    }

    private MobilePairingStatusResponse toStatusResponse(MobilePairingRequestEntity pairing) {
        return new MobilePairingStatusResponse(
                pairing.getId(),
                pairing.getStatus(),
                pairing.getClaimedDeviceId(),
                pairing.getExpiresAt(),
                pairing.getClaimedAt());
    }

    private MobileAgentResponse toAgentResponse(AgentEntity agent) {
        return new MobileAgentResponse(agent.getId(), agent.getName());
    }

    private List<String> cleanEndpoints(List<String> endpoints) {
        if (endpoints == null) {
            return List.of();
        }
        return endpoints.stream()
                .filter(endpoint -> endpoint != null && !endpoint.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private String cleanDeviceName(String deviceName) {
        return deviceName == null || deviceName.isBlank() ? "iPhone" : deviceName.trim();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize mobile JSON", e);
        }
    }

    List<String> readEndpoints(MobilePairingRequestEntity pairing) {
        try {
            return objectMapper.readValue(pairing.getEndpointsJson(), STRING_LIST);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }
}
