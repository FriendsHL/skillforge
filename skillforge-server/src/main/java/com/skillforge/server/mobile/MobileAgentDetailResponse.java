package com.skillforge.server.mobile;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MobileAgentDetailResponse(
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
        String configurationAccess,
        Integer maxLoops,
        String thinkingMode,
        String reasoningEffort,
        List<String> skillNames,
        List<String> toolNames,
        Integer enabledSystemSkillCount,
        MobileAgentPromptSummaryResponse promptMetadata) {
}
