package com.skillforge.server.history;

import com.skillforge.server.config.SessionHistoryProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Atomic visibility and execution policy for the system-resident History Tool pair.
 *
 * <p>Agent IDs, grants, custom configuration, and deferred-tool state are intentionally
 * absent: when enabled and authoritative, this pair is visible to every Agent. Tool registry
 * wiring consumes this policy in the History delivery batch.
 */
@Component
public class SessionHistoryAvailabilityPolicy {

    private static final List<String> HISTORY_PAIR = List.of(
            SessionHistoryToolSchemas.SEARCH_NAME,
            SessionHistoryToolSchemas.READ_NAME);
    private static final Set<String> HISTORY_NAMES = Set.copyOf(HISTORY_PAIR);

    private final SessionHistoryProperties properties;

    public SessionHistoryAvailabilityPolicy(SessionHistoryProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    public boolean isMasterEnabled() {
        return properties.isEnabled();
    }

    public Decision decide(StoreReadiness readiness, boolean legacyToolConfigured) {
        Objects.requireNonNull(readiness, "readiness");
        if (!properties.isEnabled()) {
            return new Decision(List.of(), false, legacyToolConfigured);
        }
        if (!readiness.authoritative()) {
            // Master-on never falls back to a legacy-only transcript or partially durable path.
            return new Decision(List.of(), false, false);
        }
        return new Decision(HISTORY_PAIR, true, false);
    }

    /** Enforces the same policy at dispatch time; schema hiding is not an authorization gate. */
    public void requireDirectDispatch(String toolName, StoreReadiness readiness) {
        if (!HISTORY_NAMES.contains(toolName)) {
            throw new IllegalArgumentException("Not a system History Tool: " + toolName);
        }
        Objects.requireNonNull(readiness, "readiness");
        if (!properties.isEnabled()) {
            throw new SessionHistoryUnavailableException(
                    "HISTORY_DISABLED", "Current-Session History is disabled");
        }
        if (!readiness.authoritative()) {
            throw new SessionHistoryUnavailableException(
                    "HISTORY_STORE_NOT_AUTHORITATIVE",
                    "Current-Session History requires authoritative row storage, writer, and inbox");
        }
    }

    public record StoreReadiness(
            boolean rowReadAvailable,
            boolean rowWriteAvailable,
            boolean durableAttemptWriterAvailable,
            boolean inboxAvailable,
            boolean legacyOnlySession) {

        public boolean authoritative() {
            return rowReadAvailable
                    && rowWriteAvailable
                    && durableAttemptWriterAvailable
                    && inboxAvailable
                    && !legacyOnlySession;
        }
    }

    public record Decision(
            List<String> visibleSystemHistoryTools,
            boolean directDispatchAllowed,
            boolean legacyToolVisible) {

        public Decision {
            visibleSystemHistoryTools = List.copyOf(
                    Objects.requireNonNull(visibleSystemHistoryTools, "visibleSystemHistoryTools"));
            if (!visibleSystemHistoryTools.isEmpty() && !visibleSystemHistoryTools.equals(HISTORY_PAIR)) {
                throw new IllegalArgumentException("History Search and Read must be exposed atomically");
            }
        }
    }
}
