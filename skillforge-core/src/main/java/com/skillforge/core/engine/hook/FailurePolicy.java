package com.skillforge.core.engine.hook;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * What to do when a hook handler fails (returns unsuccessful, throws, or times out).
 *
 * <p>{@link #CONTINUE} — log a warning, continue main flow (default).
 * <br>{@link #ABORT} — interrupt the main loop. Only meaningful for synchronous
 * before-flow events (currently {@code SessionStart} / {@code UserPromptSubmit}).
 * <br>{@link #SKIP_CHAIN} — skip subsequent entries for the same event but continue main flow.
 * <em>Planned for P1 (multi-entry); currently treated as {@code CONTINUE} since P0 only
 * executes the first entry.</em>
 */
public enum FailurePolicy {
    CONTINUE,
    ABORT,
    SKIP_CHAIN;

    /** Lenient JSON parsing: unknown values fall back to {@link #CONTINUE}. */
    @JsonCreator
    public static FailurePolicy fromJson(String value) {
        if (value == null) return CONTINUE;
        try {
            return FailurePolicy.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return CONTINUE;
        }
    }
}
