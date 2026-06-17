package com.skillforge.server.improve.event;

/**
 * Fired after a prompt version becomes the agent's active version.
 *
 * <p>{@code deltaPassRate} is the A/B-measured pass-rate delta (pp) that
 * justified an automatic promotion, or {@code null} when the promotion carries
 * no A/B delta — e.g. AUTOEVOLVE-CLOSE-LOOP human adoption
 * ({@code PromptPromotionService.promoteByHuman}), where the operator is the
 * gate and no fresh A/B run produced a delta. Consumers must null-check before
 * formatting (the FE WS {@code deltaPassRate} field is optional).
 */
public record PromptPromotedEvent(String agentId, String versionId, Double deltaPassRate,
                                   int versionNumber, Long userId) {
}
