package com.skillforge.core.engine.durability;

/** Durable lifecycle states shared by the core execution protocol and its persistence adapter. */
public enum DurableToolAttemptState {
    INTENT_COMMITTED,
    EXECUTING,
    WAITING_USER,
    RESULTS_COMMITTED,
    UNCERTAIN_PENDING_RESOLUTION,
    RESOLVED_UNKNOWN
}
