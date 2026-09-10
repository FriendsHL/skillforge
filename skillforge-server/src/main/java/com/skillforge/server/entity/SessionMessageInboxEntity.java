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
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/** A live-loop USER message accepted in durable database order before later drain. */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "t_session_message_inbox",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_session_message_inbox_inbox_id", columnNames = "inbox_id"),
        indexes = @Index(
                name = "idx_session_message_inbox_session_id", columnList = "session_id, id"))
public class SessionMessageInboxEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "inbox_id", nullable = false, updatable = false, columnDefinition = "UUID")
    private UUID inboxId;

    @Column(name = "session_id", nullable = false, updatable = false, length = 36)
    private String sessionId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    /** Exact full {@code Message} JSON produced by PersistedMessageCodec. */
    @Column(name = "message_json", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String messageJson;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public SessionMessageInboxEntity() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public UUID getInboxId() {
        return inboxId;
    }

    public void setInboxId(UUID inboxId) {
        this.inboxId = inboxId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getMessageJson() {
        return messageJson;
    }

    public void setMessageJson(String messageJson) {
        this.messageJson = messageJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
