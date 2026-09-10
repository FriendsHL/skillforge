package com.skillforge.server.history;

/** Fail-closed denial before a History Tool can reach persistence. */
public final class SessionHistoryUnavailableException extends IllegalStateException {

    private final String code;

    public SessionHistoryUnavailableException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
