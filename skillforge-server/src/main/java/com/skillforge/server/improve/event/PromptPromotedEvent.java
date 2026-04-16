package com.skillforge.server.improve.event;

public record PromptPromotedEvent(String agentId, String versionId, double deltaPassRate,
                                   int versionNumber, Long userId) {
}
