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
@Table(name = "t_user_identity_mapping",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_identity_platform_user",
                columnNames = {"platform", "platform_user_id"}))
@EntityListeners(AuditingEntityListener.class)
public class UserIdentityMappingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String platform;

    @Column(name = "platform_user_id", nullable = false, length = 256)
    private String platformUserId;

    @Column(name = "skillforge_user_id", nullable = false)
    private Long skillforgeUserId;

    @Column(name = "platform_display_name", length = 256)
    private String platformDisplayName;

    @CreatedDate
    @Column(name = "created_at")
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    public UserIdentityMappingEntity() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }

    public String getPlatformUserId() { return platformUserId; }
    public void setPlatformUserId(String platformUserId) { this.platformUserId = platformUserId; }

    public Long getSkillforgeUserId() { return skillforgeUserId; }
    public void setSkillforgeUserId(Long skillforgeUserId) { this.skillforgeUserId = skillforgeUserId; }

    public String getPlatformDisplayName() { return platformDisplayName; }
    public void setPlatformDisplayName(String platformDisplayName) { this.platformDisplayName = platformDisplayName; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
