package com.skillforge.server.improve.surface;

/**
 * MULTI-SURFACE-FLYWHEEL V4 Phase 1.1 — placeholder marker interface for the
 * sandbox fan-out hook carried by {@link SandboxContext}.
 *
 * <p>Phase 1.1 (this commit): <b>no methods declared</b> — the interface
 * exists only to lock the {@code SandboxContext} record signature at the
 * spec §2.1 shape ({@code (agentId, sessionId, factory)}). Phase 1.1 callers
 * pass {@code null} for the {@code factory} field; surface implementations
 * that need sandbox plumbing throw {@code UnsupportedOperationException}
 * anyway (see {@code BehaviorRuleSurface.injectForSandbox}).
 *
 * <p>Phase 1.2 (when {@code AbstractAbEvalRunner.run} grows a real body):
 * this interface picks up methods like
 * {@code SandboxSkillRegistry buildSandboxRegistry(...)} and concrete
 * implementations like {@code SandboxSkillRegistryFactory} implement it.
 * Locking the {@code SandboxContext} signature now avoids a breaking
 * record-shape change in Phase 1.2.
 *
 * @param <V> surface-specific version entity type (mirrors
 *            {@link OptimizableSurface}'s type parameter)
 */
public interface SandboxSurfaceFactory<V> {
    // Phase 1.2 will add real methods. Intentionally empty in Phase 1.1.
}
