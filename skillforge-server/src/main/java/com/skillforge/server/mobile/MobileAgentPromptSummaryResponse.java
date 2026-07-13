package com.skillforge.server.mobile;

public record MobileAgentPromptSummaryResponse(
        MobileAgentPromptMetadataResponse agent,
        MobileAgentPromptMetadataResponse soul,
        MobileAgentPromptMetadataResponse tools) {
}
