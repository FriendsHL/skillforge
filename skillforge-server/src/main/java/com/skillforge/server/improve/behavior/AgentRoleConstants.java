package com.skillforge.server.improve.behavior;

import java.util.Set;

/**
 * FLYWHEEL-AB-AGENT-AWARE-DATASET V1 — closed set of 5 agent roles used to
 * split eval scenarios into agent-relevant subsets for behavior rule A/B
 * evaluation. V2 may add an {@code agent_role VARCHAR(32)} real column on
 * {@link com.skillforge.server.entity.AgentEntity}; until then
 * {@link AgentRoleResolver} maps {@code t_agent.name} via {@code .contains()}
 * pattern matching.
 *
 * <p><b>DUAL-SOURCE INVARIANT</b>: the V117 SQL backfill in
 * {@code V117__eval_scenario_add_applicable_agent_roles.sql} uses the SAME
 * substring patterns (via {@code ILIKE '%design%' / '%code%' / '%research%'
 * / '%main% OR %assistant%'}). KEEP THE TWO IN SYNC — drift would silently
 * mislabel newly-seeded scenarios (logged in V117 RAISE NOTICE) vs newly-
 * dogfood-created scenarios (resolved by Java pattern at runtime). V2
 * constraint (prd.md §D4.1): adding a 6th role requires shipping the
 * {@code AgentEntity.agent_role} real column + admin UI + V_n backfill
 * BEFORE extending this enum or {@link AgentRoleResolver}.
 *
 * <p>Pattern ordering matters in {@link AgentRoleResolver}: matches
 * cascade in the order DESIGN → CODE → RESEARCH → MAIN_ASSISTANT, so an
 * agent named e.g. "MainCodeDesign Agent" resolves to DESIGN. This mirrors
 * the V117 SQL which guards MAIN_ASSISTANT with negative ILIKE clauses
 * against design/code/research.
 */
public final class AgentRoleConstants {

    /**
     * Default fallback role for agents whose name matches no known pattern
     * AND for benchmark scenarios with no specific agent owner. When a rule's
     * owner resolves to GENERAL, {@code BehaviorRuleAbEvalService} runs in
     * regression-only mode (no target subset; matches V1 fallback semantics).
     */
    public static final String GENERAL = "general";

    /** Code Agent role. V117 SQL pattern: {@code ILIKE '%code%'}. */
    public static final String CODE = "code";

    /** Design Agent role. V117 SQL pattern: {@code ILIKE '%design%'}. */
    public static final String DESIGN = "design";

    /** Research Agent role. V117 SQL pattern: {@code ILIKE '%research%'}. */
    public static final String RESEARCH = "research";

    /**
     * Main Assistant role. V117 SQL pattern:
     * {@code (ILIKE '%main%' OR ILIKE '%assistant%')} AND NOT design/code/research.
     */
    public static final String MAIN_ASSISTANT = "main_assistant";

    /** Closed set of all 5 role values; useful for validation / iteration. */
    public static final Set<String> ALL = Set.of(GENERAL, CODE, DESIGN, RESEARCH, MAIN_ASSISTANT);

    private AgentRoleConstants() {
    }
}
