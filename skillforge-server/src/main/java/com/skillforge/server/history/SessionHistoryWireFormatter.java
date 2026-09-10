package com.skillforge.server.history;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.context.LowTrustContextBoundary;
import com.skillforge.core.context.PromptSourceType;
import com.skillforge.server.config.SessionHistoryProperties;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Serializes a complete closed History DTO before applying the real low-trust provider boundary. */
@Component
public final class SessionHistoryWireFormatter {

    private final ObjectMapper objectMapper;
    private final int maxProviderWireChars;

    public SessionHistoryWireFormatter(
            ObjectMapper objectMapper,
            SessionHistoryProperties properties) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        Objects.requireNonNull(properties, "properties");
        int configuredLimit = properties.getMaxProviderWireChars();
        if (configuredLimit <= 0) {
            throw new IllegalArgumentException("History provider wire limit must be positive");
        }
        this.maxProviderWireChars = Math.min(
                configuredLimit, SessionHistoryProperties.MAX_PROVIDER_WIRE_CHARS);
    }

    public String format(SessionHistorySearchResponse response) {
        return formatClosed(Objects.requireNonNull(response, "response"));
    }

    public String format(SessionHistoryReadResponse response) {
        return formatClosed(Objects.requireNonNull(response, "response"));
    }

    public String format(SessionHistoryErrorResponse response) {
        return requireWithinLimit(wrap(Objects.requireNonNull(response, "response")));
    }

    private String formatClosed(Object response) {
        String wire = wrap(response);
        if (wire.length() <= maxProviderWireChars) return wire;
        return format(SessionHistoryErrorResponse.responseTooLarge());
    }

    private String wrap(Object response) {
        try {
            String json = objectMapper.writeValueAsString(response);
            return LowTrustContextBoundary.wrap(PromptSourceType.HISTORY, json);
        } catch (JsonProcessingException e) {
            throw new HistoryProtocolException(
                    "HISTORY_SERIALIZATION_FAILED", "Unable to serialize History response");
        }
    }

    private String requireWithinLimit(String wire) {
        if (wire.length() > maxProviderWireChars) {
            throw new HistoryProtocolException(
                    "HISTORY_RESPONSE_TOO_LARGE", "History response exceeds the provider wire limit");
        }
        return wire;
    }
}
