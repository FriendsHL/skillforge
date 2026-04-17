package com.skillforge.core.engine.hook;

/**
 * Thrown by the lifecycle hook adapter when a synchronous hook returns ABORT.
 *
 * <p>Caught by {@code AgentLoopEngine} (for {@code UserPromptSubmit}) and by
 * {@code ChatService} (for {@code SessionStart}) to short-circuit the main flow
 * and surface the abort as a structured error.
 */
public class HookAbortException extends RuntimeException {

    private final HookEvent event;
    private final String reason;

    public HookAbortException(HookEvent event, String reason) {
        super("Hook aborted at " + (event != null ? event.wireName() : "unknown") + ": " + reason);
        this.event = event;
        this.reason = reason;
    }

    public HookEvent getEvent() {
        return event;
    }

    public String getReason() {
        return reason;
    }
}
