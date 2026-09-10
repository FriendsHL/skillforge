package com.skillforge.server.session;

import java.util.Objects;

/** Payload-free error contract for the manual unknown-outcome boundary. */
public final class UnknownOutcomeResolutionException extends IllegalStateException {

    private final Code code;

    UnknownOutcomeResolutionException(Code code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }

    public enum Code {
        RESOLUTION_NOT_AVAILABLE,
        PERSISTENCE_FAILED
    }
}
