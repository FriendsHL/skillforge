package com.skillforge.server.runtime;

import com.skillforge.server.entity.SessionEntity;

import java.util.Set;

/** Applies or clears the structured failure fact as one state transition. */
public final class RuntimeFailureState {

    private static final Set<String> RETRYABLE_SOURCES = Set.of(
            "model_provider", "network", "harness");

    private RuntimeFailureState() {
    }

    public static void apply(SessionEntity session, RuntimeFailureFact fact) {
        session.setRuntimeStatus("error");
        session.setRuntimeFailureSource(fact.source());
        session.setRuntimeFailureCode(fact.code());
        session.setRuntimeRetryable(fact.retryable());
        session.setRuntimeSideEffects(fact.sideEffects());
        session.setRuntimeError(fact.sanitizedError());
        session.setRuntimeStep(fact.retryable() ? "retryable" : null);
    }

    public static void clear(SessionEntity session) {
        session.setRuntimeFailureSource(null);
        session.setRuntimeFailureCode(null);
        session.setRuntimeRetryable(false);
        session.setRuntimeSideEffects(null);
        session.setRuntimeError(null);
        if ("retryable".equals(session.getRuntimeStep())) {
            session.setRuntimeStep(null);
        }
    }

    /**
     * Authoritative replay gate shared by API projections and execution. It deliberately
     * uses a closed source allowlist so malformed, stale, or future facts cannot make
     * tool/user-action/unknown failures replayable merely by setting a boolean column.
     */
    public static boolean isRetryAllowed(SessionEntity session) {
        if (session == null
                || !"error".equals(session.getRuntimeStatus())
                || !session.isRuntimeRetryable()
                || !"none".equals(session.getRuntimeSideEffects())) {
            return false;
        }
        String source = session.getRuntimeFailureSource();
        String code = session.getRuntimeFailureCode();
        if (source == null || code == null || source.isBlank() || code.isBlank()) {
            return false;
        }
        return RETRYABLE_SOURCES.contains(source);
    }
}
