package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

@Entity
@Table(name = "t_channel_conversation",
        indexes = {
            @Index(name = "idx_ch_conv_platform_conv",
                    columnList = "platform, conversation_id"),
            @Index(name = "idx_ch_conv_session_id",
                    columnList = "session_id")
        })
@EntityListeners(AuditingEntityListener.class)
public class ChannelConversationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String platform;

    @Column(name = "conversation_id", nullable = false, length = 256)
    private String conversationId;

    @Column(name = "session_id", nullable = false, length = 36)
    private String sessionId;

    @Column(name = "channel_config_id", nullable = false)
    private Long channelConfigId;

    @CreatedDate
    @Column(name = "created_at")
    private Instant createdAt;

    /** null = active; non-null = closed. */
    @Column(name = "closed_at")
    private Instant closedAt;

    public ChannelConversationEntity() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public Long getChannelConfigId() { return channelConfigId; }
    public void setChannelConfigId(Long channelConfigId) { this.channelConfigId = channelConfigId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }
}
