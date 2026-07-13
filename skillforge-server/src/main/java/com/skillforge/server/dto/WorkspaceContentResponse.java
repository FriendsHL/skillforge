package com.skillforge.server.dto;

import java.time.Instant;

public record WorkspaceContentResponse(
        String name,
        String path,
        long sizeBytes,
        Instant modifiedAt,
        String content,
        boolean truncated,
        boolean binary) {
}
