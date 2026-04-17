package com.skillforge.core.engine.hook;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Lifecycle hook event identifiers.
 *
 * <p>JSON wire form uses PascalCase names (e.g. {@code "SessionStart"}) to match the public API
 * schema in {@code docs/design-n3-lifecycle-hooks.md}. Java enum constants keep the standard
 * {@code UPPER_SNAKE_CASE}.
 */
public enum HookEvent {
    SESSION_START("SessionStart"),
    USER_PROMPT_SUBMIT("UserPromptSubmit"),
    POST_TOOL_USE("PostToolUse"),
    STOP("Stop"),
    SESSION_END("SessionEnd");

    private final String wireName;

    HookEvent(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    /**
     * Parse a wire-format name ({@code "SessionStart"}, etc.) into the enum, case-insensitive.
     * Returns null when the input does not match any known event — callers should treat null as
     * "unknown event, skip" and not throw.
     */
    @JsonCreator
    public static HookEvent fromWire(String value) {
        if (value == null) return null;
        for (HookEvent ev : values()) {
            if (ev.wireName.equalsIgnoreCase(value) || ev.name().equalsIgnoreCase(value)) {
                return ev;
            }
        }
        return null;
    }
}
