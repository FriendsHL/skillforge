package com.skillforge.server.mobile;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "t_mobile_notification_delivery")
public class MobileNotificationDeliveryEntity {
    @Id private UUID id;
    @Column(name = "notification_id", nullable = false) private UUID notificationId;
    @Column(name = "device_id", nullable = false) private UUID deviceId;
    @Column(name = "push_token_id", nullable = false) private UUID pushTokenId;
    @Column(nullable = false, length = 16) private String status = "pending";
    @Column(nullable = false) private int attempts;
    @Column(name = "next_attempt_at", nullable = false) private Instant nextAttemptAt;
    @Column(name = "last_error", length = 256) private String lastError;
    @Column(name = "apns_id", length = 64) private String apnsId;
    @Column(name = "delivered_at") private Instant deliveredAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    public MobileNotificationDeliveryEntity() {}
    public UUID getId() { return id; } public void setId(UUID v) { id = v; }
    public UUID getNotificationId() { return notificationId; } public void setNotificationId(UUID v) { notificationId = v; }
    public UUID getDeviceId() { return deviceId; } public void setDeviceId(UUID v) { deviceId = v; }
    public UUID getPushTokenId() { return pushTokenId; } public void setPushTokenId(UUID v) { pushTokenId = v; }
    public String getStatus() { return status; } public void setStatus(String v) { status = v; }
    public int getAttempts() { return attempts; } public void setAttempts(int v) { attempts = v; }
    public Instant getNextAttemptAt() { return nextAttemptAt; } public void setNextAttemptAt(Instant v) { nextAttemptAt = v; }
    public String getLastError() { return lastError; } public void setLastError(String v) { lastError = v; }
    public String getApnsId() { return apnsId; } public void setApnsId(String v) { apnsId = v; }
    public Instant getDeliveredAt() { return deliveredAt; } public void setDeliveredAt(Instant v) { deliveredAt = v; }
    public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; } public void setUpdatedAt(Instant v) { updatedAt = v; }
}
