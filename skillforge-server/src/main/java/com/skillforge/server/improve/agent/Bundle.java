package com.skillforge.server.improve.agent;

/**
 * AUTOEVOLVE-AGENT-LEVEL-BUNDLE Phase 1 — a bundle (改动包) is a tuple of
 * per-surface version pointers. A {@code null} pointer means "use the agent's
 * currently-active version for that surface" (tech-design.md §0 / §2.1).
 *
 * <p>V1 carries exactly two surfaces (prompt, behavior_rule). Adding a surface
 * (skill, tools, hook) is a deliberate future widening — do NOT speculatively
 * add fields now (§7 W4: keep the record 2-field + a loud guard in
 * {@code BundleApplicator} for the not-yet-wired behavior_rule branch).
 *
 * <p>Records have structural equality, which {@code AgentEvolveAbEvalService}
 * relies on for the §7 W1 cached-rate consistency assertion (the incoming
 * baseline bundle must {@code equals()} the prior winner's candidate bundle).
 */
public record Bundle(String promptVersionId, String behaviorRuleVersionId) {
}
