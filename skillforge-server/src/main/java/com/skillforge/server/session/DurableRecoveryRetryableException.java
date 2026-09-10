package com.skillforge.server.session;

import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;

/** A payload-free transient durability failure that may be retried with the same IDs. */
public class DurableRecoveryRetryableException extends IllegalStateException {
    public DurableRecoveryRetryableException() {
        super("Durable recovery is temporarily unavailable");
    }

    static boolean isInfrastructureTransient(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof DurableRecoveryRetryableException
                    || current instanceof ArchivePreparationStorageException
                    || current instanceof TransientDataAccessException
                    || current instanceof RecoverableDataAccessException
                    || current instanceof CannotCreateTransactionException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
