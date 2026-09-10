package com.skillforge.server.history;

/** Closed, machine-readable validation failure raised before any History repository access. */
public final class HistoryInputValidationException extends IllegalArgumentException {

    private final String code;
    private final String field;

    public HistoryInputValidationException(String code, String field, String message) {
        super(message);
        this.code = code;
        this.field = field;
    }

    public String getCode() {
        return code;
    }

    public String getField() {
        return field;
    }
}
