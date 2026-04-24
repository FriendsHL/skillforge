package com.skillforge.core.engine.confirm;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * User's response to an install-confirmation prompt. {@code TIMEOUT} is produced by the
 * engine when the latch expires; it never comes from user input.
 *
 * <p>{@code @JsonCreator} / {@code @JsonValue} keep the wire format case-insensitive and
 * tolerant of legacy values (future-proof for discriminated-union evolution per
 * SkillForge footgun #4).
 */
public enum Decision {
    APPROVED,
    DENIED,
    TIMEOUT;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static Decision fromJson(String raw) {
        if (raw == null) return null;
        String normalized = raw.trim().toUpperCase();
        return switch (normalized) {
            case "APPROVED", "APPROVE", "YES", "OK", "TRUE" -> APPROVED;
            case "DENIED", "DENY", "NO", "CANCEL", "REJECT", "FALSE" -> DENIED;
            case "TIMEOUT", "EXPIRED" -> TIMEOUT;
            default -> throw new IllegalArgumentException("Unknown Decision: " + raw);
        };
    }
}
