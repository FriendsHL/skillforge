package com.skillforge.server.session.persistence;

/** Closed signal for an exact durable message batch that is partial or inconsistent. */
public final class DurableMessageBatchIntegrityException extends IllegalStateException {

    DurableMessageBatchIntegrityException() {
        super("Durable message batch is partial or inconsistent");
    }
}
