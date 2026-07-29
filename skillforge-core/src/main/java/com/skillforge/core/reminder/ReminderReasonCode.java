package com.skillforge.core.reminder;

/**
 * Machine-readable reason for emitting a reminder. Values are intentionally
 * broader than the current sources so later placements do not need free-form
 * diagnostic strings.
 */
public enum ReminderReasonCode {
    CONTEXT_THRESHOLD_REACHED,
    PENDING_TODOS,
    STALE_MEMORY_AVAILABLE,
    RECENT_FILE_CONTEXT,
    TOOL_DISCOVERY_REQUIRED,
    PERMISSION_STATE_CHANGED,
    COMPACT_STATE_RESTORED,
    UNSPECIFIED
}
