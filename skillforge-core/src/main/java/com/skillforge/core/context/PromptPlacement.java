package com.skillforge.core.context;

/**
 * Placement of a prompt fragment or runtime attachment. System-prompt
 * renderers currently consume the first two values; Reminder V2 records the
 * event-relative placements for compatibility rendering and diagnostics.
 */
public enum PromptPlacement {
    STABLE_SYSTEM,
    DYNAMIC_SYSTEM,
    INITIAL_CONTEXT,
    BEFORE_NEXT_MODEL_CALL,
    AFTER_TOOL_RESULT,
    AFTER_COMPACT_SUMMARY
}
