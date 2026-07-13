package com.skillforge.server.mobile;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/api/mobile")
public class MobilePairingController {

    private static final long DASHBOARD_USER_ID = 1L;

    private final MobilePairingService pairingService;
    private final MobileDeviceService deviceService;

    public MobilePairingController(MobilePairingService pairingService, MobileDeviceService deviceService) {
        this.pairingService = pairingService;
        this.deviceService = deviceService;
    }

    @PostMapping("/pairings")
    public ResponseEntity<MobilePairingCreateResponse> createPairing(
            @RequestBody(required = false) MobilePairingCreateRequest request,
            HttpServletRequest servletRequest) {
        String serverName = request != null && request.serverName() != null
                ? request.serverName()
                : "SkillForge";
        List<String> endpoints = request != null && request.endpoints() != null && !request.endpoints().isEmpty()
                ? request.endpoints()
                : List.of(origin(servletRequest));
        return ResponseEntity.ok(pairingService.createPairing(DASHBOARD_USER_ID, serverName, endpoints));
    }

    @GetMapping("/pairings/{pairingId}")
    public ResponseEntity<MobilePairingStatusResponse> getPairingStatus(@PathVariable UUID pairingId) {
        return ResponseEntity.ok(pairingService.getPairingStatus(DASHBOARD_USER_ID, pairingId));
    }

    @PostMapping("/pairings/{pairingId}/claim")
    public ResponseEntity<MobilePairingClaimResponse> claimPairing(
            @PathVariable UUID pairingId,
            @RequestBody MobilePairingClaimRequest request) {
        return ResponseEntity.ok(pairingService.claimPairing(pairingId, request));
    }

    @GetMapping("/devices")
    public ResponseEntity<List<MobileDeviceResponse>> listDevices() {
        return ResponseEntity.ok(deviceService.listDevices(DASHBOARD_USER_ID));
    }

    @PostMapping("/devices/{deviceId}/revoke")
    public ResponseEntity<MobileDeviceResponse> revokeDevice(@PathVariable UUID deviceId) {
        MobileDeviceEntity revoked = deviceService.revokeDevice(DASHBOARD_USER_ID, deviceId);
        return ResponseEntity.ok(deviceService.toResponse(revoked));
    }

    @ExceptionHandler(MobilePairingException.class)
    public ResponseEntity<Map<String, String>> pairingError(MobilePairingException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler({IllegalArgumentException.class, NoSuchElementException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }

    private String origin(HttpServletRequest request) {
        String scheme = request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();
        boolean defaultPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        return defaultPort ? scheme + "://" + host : scheme + "://" + host + ":" + port;
    }
}
