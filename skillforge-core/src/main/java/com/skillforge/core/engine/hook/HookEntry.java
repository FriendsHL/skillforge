package com.skillforge.core.engine.hook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A single lifecycle hook entry — one handler to run plus execution settings.
 *
 * <p>Field invariants:
 * <ul>
 *   <li>{@code handler} may be null when JSON is malformed; dispatcher treats null as skip.</li>
 *   <li>{@code timeoutSeconds} valid range is [1, 300]. Outside range is clamped at dispatch time.</li>
 *   <li>{@code failurePolicy} defaults to {@link FailurePolicy#CONTINUE}.</li>
 *   <li>{@code async} defaults to false.</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class HookEntry {

    private HookHandler handler;
    private int timeoutSeconds = 30;
    private FailurePolicy failurePolicy = FailurePolicy.CONTINUE;
    private boolean async = false;
    /** Optional human-readable name. Frontend display only; not used by dispatcher. */
    private String displayName;

    public HookEntry() {}

    public HookHandler getHandler() {
        return handler;
    }

    public void setHandler(HookHandler handler) {
        this.handler = handler;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public FailurePolicy getFailurePolicy() {
        return failurePolicy;
    }

    public void setFailurePolicy(FailurePolicy failurePolicy) {
        this.failurePolicy = failurePolicy != null ? failurePolicy : FailurePolicy.CONTINUE;
    }

    public boolean isAsync() {
        return async;
    }

    public void setAsync(boolean async) {
        this.async = async;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }
}
