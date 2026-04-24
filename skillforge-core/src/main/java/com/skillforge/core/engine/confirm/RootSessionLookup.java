package com.skillforge.core.engine.confirm;

/**
 * Resolves a sessionId to its root (top-most parent) sessionId.
 *
 * <p>Abstraction point: {@link com.skillforge.core.engine.AgentLoopEngine} and
 * {@link com.skillforge.core.engine.SafetySkillHook} live in the core module and
 * cannot depend on server-layer {@code SessionService}. The server-layer
 * {@code RootSessionResolver} implements this interface; core tests may mock it.
 *
 * <p>Implementations must guarantee:
 * <ul>
 *   <li>null input → null output</li>
 *   <li>bounded traversal depth (default 10) to prevent accidental loops</li>
 *   <li>any lookup failure → return the input sessionId itself (conservative,
 *       localizes authorization without contaminating other trees)</li>
 * </ul>
 */
@FunctionalInterface
public interface RootSessionLookup {
    String resolveRoot(String sessionId);
}
