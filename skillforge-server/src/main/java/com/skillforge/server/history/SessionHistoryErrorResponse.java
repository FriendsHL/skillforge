package com.skillforge.server.history;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Objects;

/** Small closed error payload used when a History response cannot safely enter provider context. */
@JsonPropertyOrder({"schemaVersion", "error"})
public record SessionHistoryErrorResponse(int schemaVersion, Error error) {

    public SessionHistoryErrorResponse {
        if (schemaVersion <= 0) throw new IllegalArgumentException("schemaVersion must be positive");
        Objects.requireNonNull(error, "error");
    }

    public static SessionHistoryErrorResponse responseTooLarge() {
        return new SessionHistoryErrorResponse(1, new Error(
                "HISTORY_RESPONSE_TOO_LARGE",
                "History response exceeded the provider wire limit; narrow the selector or page size"));
    }

    public static SessionHistoryErrorResponse of(String code, String message) {
        return new SessionHistoryErrorResponse(1, new Error(code, message));
    }

    @JsonPropertyOrder({"code", "message"})
    public record Error(String code, String message) {
        public Error {
            if (code == null || code.isBlank()) throw new IllegalArgumentException("error code is required");
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("error message is required");
            }
        }
    }
}
