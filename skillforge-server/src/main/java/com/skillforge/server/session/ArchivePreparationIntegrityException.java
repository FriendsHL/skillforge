package com.skillforge.server.session;

/** Closed signal for archive identity/protocol corruption, which must never use raw fallback. */
final class ArchivePreparationIntegrityException extends IllegalStateException {

    ArchivePreparationIntegrityException() {
        super("Tool result archive occurrence is partial or inconsistent");
    }
}
