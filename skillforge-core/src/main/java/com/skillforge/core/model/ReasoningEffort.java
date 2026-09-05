package com.skillforge.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Reasoning-effort hint for reasoning-capable provider families (deepseek-v4 / OpenAI o*).
 *
 * <p>Values are normally transmitted verbatim. The Bailian Token Plan DeepSeek adapter
 * maps unsupported levels to the documented {@code high} default when switching models.
 * Families that do not advertise {@code supportsReasoningEffort} ignore this field silently.</p>
 */
public enum ReasoningEffort {
    LOW,
    MEDIUM,
    HIGH,
    MAX;

    @JsonCreator
    public static ReasoningEffort fromString(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return valueOf(v.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // Permissive: unknown value becomes null (= provider default) rather than throw.
            return null;
        }
    }

    @JsonValue
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
