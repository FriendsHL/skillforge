package com.skillforge.core.context;

public enum ContextLifecycle {
    REQUEST_ONLY,
    SINGLE_TURN,
    UNTIL_CONSUMED,
    UNTIL_STATE_CHANGE,
    SESSION
}
