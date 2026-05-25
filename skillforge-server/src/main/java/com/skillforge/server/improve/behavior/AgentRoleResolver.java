package com.skillforge.server.improve.behavior;

import com.skillforge.server.entity.AgentEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * FLYWHEEL-AB-AGENT-AWARE-DATASET V1 — maps {@link AgentEntity} to one of the
 * 5 closed-set role values defined in {@link AgentRoleConstants}. V1 uses
 * {@code agent.name} substring heuristics (after lower-casing); V2 may add
 * an {@code agent_role VARCHAR(32)} real column on AgentEntity and obviate
 * this resolver.
 *
 * <p><b>DUAL-SOURCE INVARIANT — KEEP IN SYNC WITH V117 SQL</b>
 * ({@code V117__eval_scenario_add_applicable_agent_roles.sql}):
 * <ol>
 *   <li>{@link AgentRoleConstants#DESIGN} ⟷ {@code ILIKE '%design%'}</li>
 *   <li>{@link AgentRoleConstants#CODE} ⟷ {@code ILIKE '%code%'}</li>
 *   <li>{@link AgentRoleConstants#RESEARCH} ⟷ {@code ILIKE '%research%'}</li>
 *   <li>{@link AgentRoleConstants#MAIN_ASSISTANT} ⟷
 *       {@code (ILIKE '%main%' OR ILIKE '%assistant%')} AND NOT design/code/research</li>
 *   <li>{@link AgentRoleConstants#GENERAL} fallback (no Java match;
 *       log.warn so operator extends heuristics + V_n backfill)</li>
 * </ol>
 *
 * <p>Pattern ordering MUST match the V117 SQL precedence (design wins over
 * main_assistant for hypothetical "MainDesign" agent — V117 negative ILIKE
 * guards enforce the same). {@code AgentRoleResolverTest} hardcodes the
 * V117 substring literals as inputs so renaming a literal on either side
 * fails the test (machine cross-check stronger than the comment above).
 *
 * <p>INV-2 (prd.md): NEVER returns null. Unknown names fall back to
 * {@link AgentRoleConstants#GENERAL} with a WARN log.
 */
@Component
public class AgentRoleResolver {

    private static final Logger log = LoggerFactory.getLogger(AgentRoleResolver.class);

    /**
     * Resolve the closed-set role for the given agent. Null agent or null/blank
     * name → {@link AgentRoleConstants#GENERAL} (no warn — these are legitimate
     * caller paths e.g. agent-less benchmark scenarios).
     *
     * @param agent the agent entity; may be null
     * @return one of the 5 {@link AgentRoleConstants} values; never null
     */
    public String resolveRole(AgentEntity agent) {
        if (agent == null || agent.getName() == null || agent.getName().isBlank()) {
            return AgentRoleConstants.GENERAL;
        }
        String name = agent.getName().toLowerCase(Locale.ROOT);
        // Pattern order matches V117 SQL precedence (design → code → research →
        // main_assistant). Negative guards in the V117 main_assistant UPDATE
        // are achieved here by the if/return chain ordering.
        if (name.contains("design")) {
            return AgentRoleConstants.DESIGN;
        }
        if (name.contains("code")) {
            return AgentRoleConstants.CODE;
        }
        if (name.contains("research")) {
            return AgentRoleConstants.RESEARCH;
        }
        if (name.contains("main") || name.contains("assistant")) {
            return AgentRoleConstants.MAIN_ASSISTANT;
        }
        log.warn("[AgentRoleResolver] unknown agent name='{}' agent_id={} → fallback GENERAL. "
                        + "Consider extending heuristic + V_n backfill if this agent will own "
                        + "dogfood scenarios for behavior_rule A/B evaluation.",
                agent.getName(), agent.getId());
        return AgentRoleConstants.GENERAL;
    }
}
