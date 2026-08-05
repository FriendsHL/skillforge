package com.skillforge.server.service;

public class SessionTaskException extends RuntimeException {
    private final String code;
    private final boolean retryable;
    private final String failedField;
    private final String suggestedAction;

    public SessionTaskException(String code, String message, boolean retryable,
                                String failedField, String suggestedAction) {
        super(message);
        this.code = code;
        this.retryable = retryable;
        this.failedField = failedField;
        this.suggestedAction = suggestedAction;
    }
    public String getCode() { return code; }
    public boolean isRetryable() { return retryable; }
    public String getFailedField() { return failedField; }
    public String getSuggestedAction() { return suggestedAction; }
}
