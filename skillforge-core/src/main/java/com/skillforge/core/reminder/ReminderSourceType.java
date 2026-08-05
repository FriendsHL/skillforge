package com.skillforge.core.reminder;

/**
 * Stable category used for content-free reminder diagnostics.
 */
public enum ReminderSourceType {
    CONTEXT_USAGE,
    TASK_STATE,
    /** Historical TodoWrite reminder value retained for persisted message compatibility. */
    TODO_LIST,
    MEMORY_AGE,
    FILE_ACTIVITY,
    TOOL_DISCOVERY,
    PERMISSION,
    COMPACT_RECOVERY,
    OTHER
}
