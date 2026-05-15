package com.skillforge.server.improve.surface;

/**
 * V4 multi-surface optimization abstraction.
 *
 * <p>Strategy pattern per surface type (skill / prompt / behavior_rule). Each
 * implementation owns one surface's full lifecycle:
 * <ol>
 *   <li>load the agent's currently active baseline version</li>
 *   <li>create a candidate (may involve an LLM call — e.g. prompt /
 *       behavior_rule improver)</li>
 *   <li>inject baseline or candidate into a sandbox session for A/B
 *       evaluation</li>
 *   <li>promote candidate → active (atomic) once the A/B + canary gate
 *       passes</li>
 *   <li>rollback to the previously active version on auto-rollback signal</li>
 * </ol>
 *
 * <p>Phase 1.1 (this commit) wires the interface + three Spring
 * {@code @Component} adapters:
 * <ul>
 *   <li>{@code SkillSurface} — wraps SkillAbEvalService + SkillDraftService
 *       (V2-era flow; behavior unchanged)</li>
 *   <li>{@code PromptSurface} — wraps PromptImproverService +
 *       PromptPromotionService (V3-era flow; behavior unchanged)</li>
 *   <li>{@code BehaviorRuleSurface} — new third surface (V4)</li>
 * </ul>
 *
 * <p>Phase 1.2 will introduce {@code AbstractAbEvalRunner<V>} which delegates
 * to this surface via Template Method, replacing the duplicated A/B eval logic
 * in the two existing services.
 *
 * <p>Phase 1.3 will widen {@code CanaryAllocator} to take a {@code surface_type}
 * dispatch parameter; today each implementation owns its own canary
 * coordination per surface.
 *
 * @param <V> surface-specific version entity type (e.g. {@code SkillEntity},
 *            {@code PromptVersionEntity}, {@code BehaviorRuleVersionEntity})
 */
public interface OptimizableSurface<V> {

    /**
     * Surface type discriminator for routing + DB column values.
     *
     * <p>Matches {@code t_optimization_event.surface_type} +
     * {@code t_canary_rollout.surface_type} values. Must be one of
     * {@code "skill"} / {@code "prompt"} / {@code "behavior_rule"} —
     * {@link SurfaceRegistry} keys on this string.
     */
    String surfaceType();

    /**
     * Load the agent's currently active baseline version, or {@code null} if
     * the agent has no DB-backed active version (callers fall back to a
     * surface-specific bootstrap baseline — e.g. behavior_rule fallback to the
     * startup-loaded built-in rules in {@code BehaviorRuleRegistry}).
     */
    V loadActive(Long agentId);

    /**
     * Load a specific version by id (for sandbox candidate injection, audit,
     * or rollback target lookup).
     *
     * @param versionId surface-specific id type:
     *                  skill = numeric string ({@code Long.toString}),
     *                  prompt = UUID string,
     *                  behavior_rule = UUID string
     */
    V loadVersion(String versionId);

    /**
     * Build a new candidate version from the baseline + an attribution / curator
     * rationale. May trigger LLM calls (e.g. prompt / behavior_rule surfaces
     * use {@code defaultProvider} per 5-ratify #5).
     *
     * <p>Returned version must be persisted ({@code status='candidate'}); the
     * id is stable so callers may link from {@code OptimizationEventEntity}.
     *
     * @param baseline           the agent's current active version
     * @param improvementContext free-form rationale; for V3+ attribution this is
     *                           the curator's {@code attributedDescription}
     */
    V createCandidate(V baseline, String improvementContext);

    /**
     * Replace agent's view of the surface for an A/B sandbox session:
     * <ul>
     *   <li>SkillSurface: register {@code version} in the sandbox SkillRegistry
     *       (replacing the active baseline binding)</li>
     *   <li>PromptSurface: temporarily set {@code AgentDefinition.systemPrompt}
     *       to {@code version.content}</li>
     *   <li>BehaviorRuleSurface: make {@code BehaviorRuleRegistry} return
     *       {@code version.rulesJson} for {@code ctx.agentId()}</li>
     * </ul>
     *
     * <p>Phase 1.1: signature locked, full sandbox plumbing arrives in Phase
     * 1.2 alongside {@code AbstractAbEvalRunner}. Adapter implementations may
     * throw {@link UnsupportedOperationException} until then.
     */
    void injectForSandbox(SandboxContext ctx, V version);

    /**
     * Promote a candidate to active. Implementations must:
     * <ol>
     *   <li>mark the prior active version as retired / deprecated (preserving
     *       the partial UNIQUE invariant: ≤1 active row per (agent,
     *       surface))</li>
     *   <li>mark {@code candidate.status='active'} + {@code promotedAt=now}</li>
     *   <li>update the agent's active-pointer column (skill_ids /
     *       active_prompt_version_id / [behavior_rule lookup via DB])</li>
     *   <li>publish a surface-specific promoted event (caller-observable side
     *       effect)</li>
     * </ol>
     */
    void promote(V candidate);

    /**
     * Roll back the given (currently-active) version, restoring the prior
     * production pointer. Called by CanaryRolloutService on auto-rollback /
     * operator manual rollback.
     */
    void rollback(V candidate);
}
