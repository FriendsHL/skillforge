package com.skillforge.server.session;

/** Payload-free transient cancellation failure; retry only with the same request ID. */
public class DurableCancellationRetryableException extends IllegalStateException {
    public DurableCancellationRetryableException() {
        super("Durable cancellation is temporarily unavailable");
    }
}
