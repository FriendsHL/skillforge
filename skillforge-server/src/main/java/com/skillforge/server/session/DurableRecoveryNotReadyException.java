package com.skillforge.server.session;

/** The previous fenced owner still has a live database lease. */
public class DurableRecoveryNotReadyException extends IllegalStateException {
    public DurableRecoveryNotReadyException() {
        super("Durable recovery lease has not expired");
    }
}
