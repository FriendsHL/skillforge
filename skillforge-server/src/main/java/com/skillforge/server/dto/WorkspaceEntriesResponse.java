package com.skillforge.server.dto;

import java.util.List;

public record WorkspaceEntriesResponse(
        String rootLabel,
        String path,
        String parentPath,
        List<WorkspaceEntryResponse> entries,
        boolean truncated) {
}
