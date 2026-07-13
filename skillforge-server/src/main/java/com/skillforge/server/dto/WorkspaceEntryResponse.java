package com.skillforge.server.dto;

import java.time.Instant;

public record WorkspaceEntryResponse(
        String name,
        String path,
        String type,
        Long sizeBytes,
        Instant modifiedAt,
        boolean previewable) {
}
