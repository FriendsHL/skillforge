package com.skillforge.server.history;

/** Machine-readable History protocol failure that contains no resource details. */
public final class HistoryProtocolException extends IllegalArgumentException {

    private final String code;

    public HistoryProtocolException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
