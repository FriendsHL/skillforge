package com.skillforge.server.dto;

import java.time.Instant;
import java.util.Map;

public record SessionMessageDto(
        long seqNo,
        String role,
        Object content,
        String msgType,
        String messageType,
        String controlId,
        Instant answeredAt,
        Map<String, Object> metadata,
        String traceId
) {
    public SessionMessageDto(long seqNo, String role, Object content, String msgType,
                             String messageType, String controlId, Instant answeredAt,
                             Map<String, Object> metadata) {
        this(seqNo, role, content, msgType, messageType, controlId, answeredAt, metadata, null);
    }

    public SessionMessageDto(long seqNo, String role, Object content, String msgType,
                             Map<String, Object> metadata) {
        this(seqNo, role, content, msgType, "normal", null, null, metadata, null);
    }
}
