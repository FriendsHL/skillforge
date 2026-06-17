package com.skillforge.server.improve.agent;

/**
 * AUTOEVOLVE-AGENT-LEVEL-BUNDLE Phase 1 — a bundle (改动包) is a tuple of
 * per-surface version pointers. A {@code null} pointer means "use the agent's
 * currently-active version for that surface" (tech-design.md §0 / §2.1).
 *
 * <p>Phase 4 (§10 #1) widens the record to THREE surfaces (prompt, behavior_rule,
 * skill). A {@code null} {@code skillDraftId} means "no skill change on this side"
 * (the agent's active skill set is unchanged). Adding a further surface (tools,
 * hook) is a deliberate future widening — do NOT speculatively add fields before
 * the surface is actually wired.
 *
 * <p>Records have structural equality, which {@code AgentEvolveAbEvalService}
 * relies on for the §7 W1 cached-rate consistency assertion (the incoming
 * baseline bundle must {@code equals()} the prior winner's candidate bundle).
 * The check stays correct with three fields — equality compares all three
 * pointers component-wise.
 */
public record Bundle(String promptVersionId, String behaviorRuleVersionId, String skillDraftId) {
}
