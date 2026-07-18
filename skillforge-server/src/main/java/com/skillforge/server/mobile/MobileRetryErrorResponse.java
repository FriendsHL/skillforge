package com.skillforge.server.mobile;

/** Stable, sanitized retry error envelope consumed by the iOS client. */
public record MobileRetryErrorResponse(String code, String message, boolean retryable) {
}
