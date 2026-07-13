package com.skillforge.server.mobile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class MobileDeviceService {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final MobileDeviceRepository deviceRepository;
    private final MobileTokenService tokenService;
    private final ObjectMapper objectMapper;

    @Autowired
    public MobileDeviceService(MobileDeviceRepository deviceRepository,
                               MobileTokenService tokenService,
                               ObjectMapper objectMapper) {
        this.deviceRepository = deviceRepository;
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Optional<MobileDevicePrincipal> authenticate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        Optional<MobileDeviceEntity> found = deviceRepository.findByTokenHash(tokenService.hashToken(rawToken));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        MobileDeviceEntity device = found.get();
        if (!MobileDeviceEntity.STATUS_ACTIVE.equals(device.getStatus())) {
            return Optional.empty();
        }
        device.setLastSeenAt(Instant.now());
        deviceRepository.save(device);
        return Optional.of(new MobileDevicePrincipal(
                device.getId(),
                device.getUserId(),
                device.getDeviceName(),
                parseScopes(device.getScopesJson())));
    }

    @Transactional(readOnly = true)
    public List<MobileDeviceResponse> listDevices(Long userId) {
        return deviceRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public MobileDeviceEntity revokeDevice(Long userId, UUID deviceId) {
        MobileDeviceEntity device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new NoSuchElementException("mobile device not found"));
        if (!userId.equals(device.getUserId())) {
            throw new NoSuchElementException("mobile device not found");
        }
        device.setStatus(MobileDeviceEntity.STATUS_REVOKED);
        device.setRevokedAt(Instant.now());
        deviceRepository.save(device);
        return device;
    }

    public MobileDeviceResponse toResponse(MobileDeviceEntity device) {
        return new MobileDeviceResponse(
                device.getId(),
                device.getDeviceName(),
                device.getPlatform(),
                device.getAppVersion(),
                device.getStatus(),
                parseScopes(device.getScopesJson()),
                device.getLastSeenAt(),
                device.getCreatedAt(),
                device.getRevokedAt());
    }

    private Set<String> parseScopes(String scopesJson) {
        if (scopesJson == null || scopesJson.isBlank()) {
            return Set.of();
        }
        try {
            return new LinkedHashSet<>(objectMapper.readValue(scopesJson, STRING_LIST));
        } catch (JsonProcessingException e) {
            return Set.of();
        }
    }
}
