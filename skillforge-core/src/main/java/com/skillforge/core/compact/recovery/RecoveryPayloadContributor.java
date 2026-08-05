package com.skillforge.core.compact.recovery;

/** Supplies best-effort, session-scoped state that must be visible immediately after full compact. */
@FunctionalInterface
public interface RecoveryPayloadContributor {
    /** Return compact reminder text, or {@code null} when this contributor has no state. */
    String contribute(String sessionId);
}
