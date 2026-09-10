package com.skillforge.server.session;

/** Payload-free stale, unauthorized, or otherwise invalid cancellation target. */
public class DurableCancellationRejectedException extends IllegalStateException {
    public DurableCancellationRejectedException() {
        super("Durable cancellation target is no longer current");
    }
}
