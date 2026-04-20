package com.skillforge.server.dto;

import java.util.Map;

public record SessionMessageDto(
        long seqNo,
        String role,
        Object content,
        String msgType,
        Map<String, Object> metadata
) {
}
