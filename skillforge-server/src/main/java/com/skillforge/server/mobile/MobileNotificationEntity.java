package com.skillforge.server.mobile;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "t_mobile_notification")
public class MobileNotificationEntity {
    @Id private UUID id;
    @Column(name = "task_id", nullable = false, length = 128) private String taskId;
    @Column(name = "session_id", nullable = false, length = 36) private String sessionId;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(nullable = false, length = 32) private String kind;
    @Column(nullable = false, length = 128) private String title;
    @Column(nullable = false, length = 256) private String body;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    public MobileNotificationEntity() {}
    public UUID getId() { return id; } public void setId(UUID id) { this.id = id; }
    public String getTaskId() { return taskId; } public void setTaskId(String value) { taskId = value; }
    public String getSessionId() { return sessionId; } public void setSessionId(String value) { sessionId = value; }
    public Long getUserId() { return userId; } public void setUserId(Long value) { userId = value; }
    public String getKind() { return kind; } public void setKind(String value) { kind = value; }
    public String getTitle() { return title; } public void setTitle(String value) { title = value; }
    public String getBody() { return body; } public void setBody(String value) { body = value; }
    public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant value) { createdAt = value; }
}
