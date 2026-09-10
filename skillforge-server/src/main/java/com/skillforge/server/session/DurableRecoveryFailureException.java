package com.skillforge.server.session;

/** Payload-free recovery integrity/storage failure for caller-side classification. */
public class DurableRecoveryFailureException extends IllegalStateException {
    public DurableRecoveryFailureException() {
        super("Durable recovery failed");
    }
}
