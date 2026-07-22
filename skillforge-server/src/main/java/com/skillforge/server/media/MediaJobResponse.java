package com.skillforge.server.media;

import com.skillforge.server.entity.MediaGenerationJobEntity;

import java.time.Instant;

public record MediaJobResponse(String id, String sessionId, Long agentId, String mediaType,
                               String provider, String model, String status, String resultAttachmentId,
                               String errorCode, String errorMessage, Instant createdAt, Instant updatedAt) {
    public static MediaJobResponse from(MediaGenerationJobEntity row) {
        return new MediaJobResponse(row.getId(), row.getSessionId(), row.getAgentId(), row.getMediaType(),
                row.getProvider(), row.getModel(), row.getStatus(), row.getResultAttachmentId(), row.getErrorCode(),
                row.getErrorMessage(), row.getCreatedAt(), row.getUpdatedAt());
    }
}
