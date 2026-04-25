package com.skillforge.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Per-agent thinking-mode override, serialised as lower-case wire strings.
 *
 * <ul>
 *   <li>{@link #AUTO} — do not emit any thinking-control field upstream; keep provider default.</li>
 *   <li>{@link #ENABLED} — explicitly enable thinking for families that support the toggle.</li>
 *   <li>{@link #DISABLED} — explicitly disable thinking for families that support the toggle.</li>
 * </ul>
 *
 * Families that do not advertise {@code supportsThinkingToggle} ignore this field.
 *
 * <p>Unknown / malformed inputs from the wire or the DB fall back to {@link #AUTO} instead
 * of throwing — the DB CHECK constraint is the source of truth for validation.</p>
 */
public enum ThinkingMode {
    AUTO,
    ENABLED,
    DISABLED;

    @JsonCreator
    public static ThinkingMode fromString(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return valueOf(v.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // Permissive default: unknown enum value degrades to AUTO (never throws).
            // DB CHECK guards against new bad writes; this covers legacy rows and foreign inputs.
            return AUTO;
        }
    }

    @JsonValue
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
