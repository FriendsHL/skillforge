package com.skillforge.server.improve.event;

/**
 * Published when a behavior_rule candidate version is promoted to active.
 * Mirrors {@code PromptPromotedEvent} (kept as a distinct record rather than
 * reusing PromptPromotedEvent because the surface dimension is meaningful for
 * listeners — a dashboard panel for behavior_rule wants only this event, not
 * prompt promotions).
 *
 * @param agentId        target agent (string per V82 column type)
 * @param versionId      promoted candidate's version id
 * @param versionNumber  promoted version number (monotonic per agent)
 * @param userId         operator who triggered the promotion (for audit + WS
 *                       toast targeting); nullable for system-initiated paths
 */
public record BehaviorRulePromotedEvent(String agentId, String versionId,
                                         int versionNumber, Long userId) {
}
