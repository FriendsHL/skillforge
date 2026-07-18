package com.skillforge.server.runtime;

import java.util.Objects;
import java.util.Set;

/** Sanitized, stable failure information safe for persistence and client wires. */
public record RuntimeFailureFact(
        String source,
        String code,
        boolean retryable,
        String sideEffects,
        String sanitizedError) {

    private static final Set<String> SOURCES = Set.of(
            "model_provider", "network", "tool", "harness", "user_action", "unknown");
    private static final Set<String> SIDE_EFFECTS = Set.of("none", "possible", "observed");

    public RuntimeFailureFact {
        source = requireText(source, "source");
        code = requireText(code, "code");
        sideEffects = requireText(sideEffects, "sideEffects");
        sanitizedError = requireText(sanitizedError, "sanitizedError");
        if (!SOURCES.contains(source)) {
            throw new IllegalArgumentException("unsupported source");
        }
        if (!SIDE_EFFECTS.contains(sideEffects)) {
            throw new IllegalArgumentException("unsupported sideEffects");
        }
        if (code.length() > 64) {
            throw new IllegalArgumentException("code must be at most 64 characters");
        }
        if (sanitizedError.length() > 512) {
            throw new IllegalArgumentException("sanitizedError must be at most 512 characters");
        }
        if (retryable && !"none".equals(sideEffects)) {
            throw new IllegalArgumentException("retryable failures must have no side effects");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
