package com.skillforge.server.dto;

/**
 * Body for {@code POST /api/chat/sessions}.
 *
 * <p>{@code sourceScenarioId} (EVAL-V2 Q1) is optional and only set by the
 * Analyze-case flow on the dashboard — it links the new chat session back to
 * the eval scenario it was created to analyze, so the scenario detail
 * drawer can surface previous analysis sessions for the same case.
 */
public record CreateSessionRequest(Long userId, Long agentId, String sourceScenarioId) {
    /** Backward-compatible 2-arg constructor for callers that don't link to a scenario. */
    public CreateSessionRequest(Long userId, Long agentId) {
        this(userId, agentId, null);
    }
}
