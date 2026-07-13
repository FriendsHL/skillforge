package com.skillforge.server.mobile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "t_mobile_pairing_request")
public class MobilePairingRequestEntity {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_CLAIMED = "claimed";
    public static final String STATUS_EXPIRED = "expired";
    public static final String STATUS_CANCELLED = "cancelled";

    @Id
    @Column(nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "secret_hash", nullable = false, length = 64)
    private String secretHash;

    @Column(name = "setup_code_hash", nullable = false, length = 64)
    private String setupCodeHash;

    @Column(name = "server_name", nullable = false, length = 128)
    private String serverName;

    @Column(name = "endpoints_json", nullable = false, columnDefinition = "TEXT")
    private String endpointsJson;

    @Column(nullable = false, length = 16)
    private String status = STATUS_PENDING;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "claimed_device_id")
    private UUID claimedDeviceId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    public MobilePairingRequestEntity() {
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getSecretHash() { return secretHash; }
    public void setSecretHash(String secretHash) { this.secretHash = secretHash; }
    public String getSetupCodeHash() { return setupCodeHash; }
    public void setSetupCodeHash(String setupCodeHash) { this.setupCodeHash = setupCodeHash; }
    public String getServerName() { return serverName; }
    public void setServerName(String serverName) { this.serverName = serverName; }
    public String getEndpointsJson() { return endpointsJson; }
    public void setEndpointsJson(String endpointsJson) { this.endpointsJson = endpointsJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public UUID getClaimedDeviceId() { return claimedDeviceId; }
    public void setClaimedDeviceId(UUID claimedDeviceId) { this.claimedDeviceId = claimedDeviceId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getClaimedAt() { return claimedAt; }
    public void setClaimedAt(Instant claimedAt) { this.claimedAt = claimedAt; }
}
