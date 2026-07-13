package com.skillforge.server.mobile;

import com.skillforge.server.entity.SessionEntity;

import java.time.LocalDateTime;

public record MobileSessionResponse(
        String id,
        Long userId,
        Long agentId,
        String title,
        String status,
        String runtimeStatus,
        int messageCount,
        LocalDateTime updatedAt) {

    public static MobileSessionResponse from(SessionEntity session) {
        return new MobileSessionResponse(
                session.getId(),
                session.getUserId(),
                session.getAgentId(),
                session.getTitle(),
                session.getStatus(),
                session.getRuntimeStatus(),
                session.getMessageCount(),
                session.getUpdatedAt());
    }
}
