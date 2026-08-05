package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Entity
@Table(name = "t_session_task")
@EntityListeners(AuditingEntityListener.class)
public class SessionTaskEntity {
    @Id @Column(length = 36) private String id;
    @Column(name = "session_id", nullable = false, length = 36) private String sessionId;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(nullable = false, length = 256) private String subject;
    @Column(nullable = false, columnDefinition = "TEXT") private String description;
    @Column(name = "active_form", nullable = false, length = 512) private String activeForm;
    @Column(nullable = false, length = 16) private String status = "pending";
    @Column(length = 128) private String owner;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> metadata = new LinkedHashMap<>();
    @Version private long version;
    @CreatedDate @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @LastModifiedDate @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    public SessionTaskEntity() {}
    public String getId() { return id; } public void setId(String v) { id = v; }
    public String getSessionId() { return sessionId; } public void setSessionId(String v) { sessionId = v; }
    public Long getUserId() { return userId; } public void setUserId(Long v) { userId = v; }
    public String getSubject() { return subject; } public void setSubject(String v) { subject = v; }
    public String getDescription() { return description; } public void setDescription(String v) { description = v; }
    public String getActiveForm() { return activeForm; } public void setActiveForm(String v) { activeForm = v; }
    public String getStatus() { return status; } public void setStatus(String v) { status = v; }
    public String getOwner() { return owner; } public void setOwner(String v) { owner = v; }
    public Map<String, Object> getMetadata() { return metadata; } public void setMetadata(Map<String, Object> v) { metadata = v; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; } public void setUpdatedAt(Instant v) { updatedAt = v; }
}
