package com.skillforge.server.mobile;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "t_mobile_push_token")
public class MobilePushTokenEntity {
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_INACTIVE = "inactive";

    @Id private UUID id;
    @Column(name = "device_id", nullable = false) private UUID deviceId;
    @Column(name = "token_hash", nullable = false, length = 64) private String tokenHash;
    @Column(name = "token_ciphertext", nullable = false, columnDefinition = "TEXT") private String tokenCiphertext;
    @Column(nullable = false, length = 16) private String environment;
    @Column(nullable = false, length = 16) private String status = STATUS_ACTIVE;
    @Column(name = "last_registered_at", nullable = false) private Instant lastRegisteredAt;
    @Column(name = "invalidated_at") private Instant invalidatedAt;

    public MobilePushTokenEntity() {}
    public UUID getId() { return id; } public void setId(UUID id) { this.id = id; }
    public UUID getDeviceId() { return deviceId; } public void setDeviceId(UUID deviceId) { this.deviceId = deviceId; }
    public String getTokenHash() { return tokenHash; } public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public String getTokenCiphertext() { return tokenCiphertext; } public void setTokenCiphertext(String tokenCiphertext) { this.tokenCiphertext = tokenCiphertext; }
    public String getEnvironment() { return environment; } public void setEnvironment(String environment) { this.environment = environment; }
    public String getStatus() { return status; } public void setStatus(String status) { this.status = status; }
    public Instant getLastRegisteredAt() { return lastRegisteredAt; } public void setLastRegisteredAt(Instant value) { this.lastRegisteredAt = value; }
    public Instant getInvalidatedAt() { return invalidatedAt; } public void setInvalidatedAt(Instant value) { this.invalidatedAt = value; }
}
