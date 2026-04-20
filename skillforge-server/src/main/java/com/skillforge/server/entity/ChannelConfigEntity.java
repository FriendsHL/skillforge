package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

@Entity
@Table(name = "t_channel_config",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_channel_config_platform",
                columnNames = {"platform"}))
@EntityListeners(AuditingEntityListener.class)
public class ChannelConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String platform;

    @Column(name = "display_name", length = 128)
    private String displayName;

    @Column(nullable = false)
    private boolean active = true;

    /** TODO: AES-GCM 加密存储。目前为明文 JSON，P2 先行。 */
    @Column(name = "webhook_secret", nullable = false, columnDefinition = "TEXT")
    private String webhookSecret;

    /** TODO: AES-GCM 加密存储。目前为明文 JSON。 */
    @Column(name = "credentials_json", nullable = false, columnDefinition = "TEXT")
    private String credentialsJson;

    @Column(name = "config_json", columnDefinition = "TEXT")
    private String configJson;

    @Column(name = "default_agent_id", nullable = false)
    private Long defaultAgentId;

    @CreatedDate
    @Column(name = "created_at")
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    public ChannelConfigEntity() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }

    public String getCredentialsJson() { return credentialsJson; }
    public void setCredentialsJson(String credentialsJson) { this.credentialsJson = credentialsJson; }

    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }

    public Long getDefaultAgentId() { return defaultAgentId; }
    public void setDefaultAgentId(Long defaultAgentId) { this.defaultAgentId = defaultAgentId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
