package com.skillforge.server.mobile;

import com.skillforge.server.entity.ChatAttachmentEntity;

public record MobileAttachmentResponse(
        String id,
        String sessionId,
        String kind,
        String mimeType,
        String filename,
        long sizeBytes,
        Integer pageCount,
        String status) {

    public static MobileAttachmentResponse from(ChatAttachmentEntity attachment) {
        return new MobileAttachmentResponse(
                attachment.getId(),
                attachment.getSessionId(),
                attachment.getKind(),
                attachment.getMimeType(),
                attachment.getFilename(),
                attachment.getSizeBytes(),
                attachment.getPageCount(),
                attachment.getStatus());
    }
}
