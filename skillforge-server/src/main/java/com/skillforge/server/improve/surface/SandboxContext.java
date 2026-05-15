package com.skillforge.server.improve.surface;

/**
 * Lightweight context passed to {@link OptimizableSurface#injectForSandbox} so
 * the surface implementation knows which sandbox session it's targeting.
 *
 * <p>Phase 1.1 reviewer fix (W-SPEC-2): record carries the full 3-field shape
 * from tech-design §2.1 ({@code agentId, sessionId, factory}) — locking the
 * record signature now avoids a breaking change when Phase 1.2 wires the
 * {@link SandboxSurfaceFactory} fan-out hook. Phase 1.1 callers pass
 * {@code null} for {@code factory}; today's surface adapters throw
 * {@code UnsupportedOperationException} on {@code injectForSandbox} so the
 * null is never dereferenced.
 *
 * @param agentId   target agent for the sandbox A/B run
 * @param sessionId isolated sandbox session id; SkillSurface uses this to
 *                  scope its SandboxSkillRegistry, BehaviorRuleSurface uses
 *                  it to scope its active-version override
 * @param factory   Phase 1.2 sandbox fan-out hook (see
 *                  {@link SandboxSurfaceFactory}); Phase 1.1 callers may pass
 *                  {@code null} — surfaces that need it throw
 *                  {@code UnsupportedOperationException} until Phase 1.2
 *                  arrives
 */
public record SandboxContext(Long agentId, String sessionId, SandboxSurfaceFactory<?> factory) {
}
