package com.skillforge.server.exception;

import java.util.concurrent.RejectedExecutionException;

/**
 * Signals that retry is still safe, but cannot be accepted until the previous loop finishes
 * publishing its terminal state.
 *
 * <p>This is a typed, temporary rejection so HTTP adapters can return their existing retryable
 * busy response without inspecting exception messages.</p>
 */
public final class RetryBusyException extends RejectedExecutionException {

    public RetryBusyException() {
        super("failed loop is still finishing");
    }
}
