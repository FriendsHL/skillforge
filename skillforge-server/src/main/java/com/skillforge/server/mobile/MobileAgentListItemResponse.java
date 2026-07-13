package com.skillforge.server.mobile;

public record MobileAgentListItemResponse(
        Long id,
        String name,
        String description,
        String role,
        String modelId,
        String status,
        String source,
        String visibility,
        boolean isDefault,
        String executionMode,
        int skillCount,
        int toolCount,
        String toolAccess,
        String configurationAccess) {
}
