package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Idempotency dedup table. platformMessageId is the PK; INSERT success = first time,
 * ON CONFLICT = duplicate.
 */
@Entity
@Table(name = "t_channel_message_dedup")
public class ChannelMessageDedupEntity {

    @Id
    @Column(name = "platform_message_id", length = 256)
    private String platformMessageId;

    @Column(nullable = false, length = 64)
    private String platform;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public ChannelMessageDedupEntity() {}

    public String getPlatformMessageId() { return platformMessageId; }
    public void setPlatformMessageId(String platformMessageId) { this.platformMessageId = platformMessageId; }

    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
