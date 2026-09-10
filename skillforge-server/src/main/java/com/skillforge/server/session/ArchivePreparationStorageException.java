package com.skillforge.server.session;

/** Closed signal for a retryable archive-storage failure; never carries transcript data. */
final class ArchivePreparationStorageException extends IllegalStateException {

    ArchivePreparationStorageException() {
        super("Tool result archive storage is temporarily unavailable");
    }
}
