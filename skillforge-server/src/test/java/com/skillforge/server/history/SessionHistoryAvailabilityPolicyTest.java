package com.skillforge.server.history;

import com.skillforge.server.config.SessionHistoryProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionHistoryAvailabilityPolicyTest {

    private static final SessionHistoryAvailabilityPolicy.StoreReadiness READY =
            new SessionHistoryAvailabilityPolicy.StoreReadiness(true, true, true, true, false);

    @Test
    void masterOff_hidesPairDeniesDirectDispatchAndPreservesLegacyConfiguration() {
        SessionHistoryAvailabilityPolicy policy = policy(false);

        assertThat(policy.decide(READY, true).visibleSystemHistoryTools()).isEmpty();
        assertThat(policy.decide(READY, true).legacyToolVisible()).isTrue();
        assertThat(policy.decide(READY, false).legacyToolVisible()).isFalse();
        assertThatThrownBy(() -> policy.requireDirectDispatch(
                SessionHistoryToolSchemas.SEARCH_NAME, READY))
                .isInstanceOf(SessionHistoryUnavailableException.class)
                .extracting(error -> ((SessionHistoryUnavailableException) error).getCode())
                .isEqualTo("HISTORY_DISABLED");
    }

    @Test
    void masterOn_exposesAtomicPairToEveryAgentAndHidesLegacyTool() {
        SessionHistoryAvailabilityPolicy policy = policy(true);

        // Agent identity/grants are deliberately absent from this system-resident policy.
        for (String ignoredAgentProfile : List.of("default", "custom", "future-agent")) {
            SessionHistoryAvailabilityPolicy.Decision decision = policy.decide(READY, true);
            assertThat(decision.visibleSystemHistoryTools()).containsExactly(
                    SessionHistoryToolSchemas.SEARCH_NAME,
                    SessionHistoryToolSchemas.READ_NAME);
            assertThat(decision.legacyToolVisible()).isFalse();
            assertThat(decision.directDispatchAllowed()).isTrue();
        }
        policy.requireDirectDispatch(SessionHistoryToolSchemas.SEARCH_NAME, READY);
        policy.requireDirectDispatch(SessionHistoryToolSchemas.READ_NAME, READY);
    }

    @Test
    void masterOn_failsClosedUnlessRowStoreWriterAndInboxAreAuthoritative() {
        SessionHistoryAvailabilityPolicy policy = policy(true);
        List<SessionHistoryAvailabilityPolicy.StoreReadiness> incomplete = List.of(
                new SessionHistoryAvailabilityPolicy.StoreReadiness(false, true, true, true, false),
                new SessionHistoryAvailabilityPolicy.StoreReadiness(true, false, true, true, false),
                new SessionHistoryAvailabilityPolicy.StoreReadiness(true, true, false, true, false),
                new SessionHistoryAvailabilityPolicy.StoreReadiness(true, true, true, false, false),
                new SessionHistoryAvailabilityPolicy.StoreReadiness(true, true, true, true, true));

        for (SessionHistoryAvailabilityPolicy.StoreReadiness readiness : incomplete) {
            SessionHistoryAvailabilityPolicy.Decision decision = policy.decide(readiness, true);
            assertThat(decision.visibleSystemHistoryTools()).isEmpty();
            assertThat(decision.legacyToolVisible()).isFalse();
            assertThat(decision.directDispatchAllowed()).isFalse();
            assertThatThrownBy(() -> policy.requireDirectDispatch(
                    SessionHistoryToolSchemas.READ_NAME, readiness))
                    .isInstanceOf(SessionHistoryUnavailableException.class)
                    .extracting(error -> ((SessionHistoryUnavailableException) error).getCode())
                    .isEqualTo("HISTORY_STORE_NOT_AUTHORITATIVE");
        }
    }

    private static SessionHistoryAvailabilityPolicy policy(boolean enabled) {
        SessionHistoryProperties properties = new SessionHistoryProperties();
        properties.setEnabled(enabled);
        return new SessionHistoryAvailabilityPolicy(properties);
    }
}
