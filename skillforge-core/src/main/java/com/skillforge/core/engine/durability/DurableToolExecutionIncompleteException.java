package com.skillforge.core.engine.durability;

/** A Tool task may still be running, so no terminal result vector may be committed. */
public final class DurableToolExecutionIncompleteException extends IllegalStateException {

    private final boolean userCancelled;

    public DurableToolExecutionIncompleteException(boolean userCancelled) {
        super("Durable Tool execution did not reach a known terminal state");
        this.userCancelled = userCancelled;
    }

    public boolean isUserCancelled() {
        return userCancelled;
    }
}
