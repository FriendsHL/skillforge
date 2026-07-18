package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "t_personal_app_preference",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_personal_app_preference_user_attachment",
                columnNames = {"user_id", "attachment_id"}),
        indexes = {
                @Index(name = "idx_personal_app_preference_user_favorite",
                        columnList = "user_id, updated_at, attachment_id"),
                @Index(name = "idx_personal_app_preference_user_recent",
                        columnList = "user_id, last_opened_at, attachment_id")
        })
public class PersonalAppPreferenceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "attachment_id", nullable = false, length = 36)
    private String attachmentId;

    @Column(name = "favorite", nullable = false)
    private boolean favorite;

    @Column(name = "last_opened_at")
    private Instant lastOpenedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getAttachmentId() { return attachmentId; }
    public void setAttachmentId(String attachmentId) { this.attachmentId = attachmentId; }
    public boolean isFavorite() { return favorite; }
    public void setFavorite(boolean favorite) { this.favorite = favorite; }
    public Instant getLastOpenedAt() { return lastOpenedAt; }
    public void setLastOpenedAt(Instant lastOpenedAt) { this.lastOpenedAt = lastOpenedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
