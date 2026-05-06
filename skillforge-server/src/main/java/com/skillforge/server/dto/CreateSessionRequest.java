package com.skillforge.server.dto;

/**
 * Body for {@code POST /api/chat/sessions}.
 *
 * <p>{@code sourceScenarioId} is a legacy compatibility field from EVAL-V2 Q1.
 * M3c introduces {@code t_eval_analysis_session}; new analysis flows should use
 * dedicated eval analyze endpoints (scenario/task/item) instead of writing
 * {@code t_session.source_scenario_id}. Keep this field so older clients or
 * in-flight sessions can still deserialize.
 */
public record CreateSessionRequest(Long userId, Long agentId, String sourceScenarioId) {
    /** Backward-compatible 2-arg constructor for callers that don't link to a scenario. */
    public CreateSessionRequest(Long userId, Long agentId) {
        this(userId, agentId, null);
    }
}
