package com.skillforge.server.improve;

import com.skillforge.server.repository.EvalScenarioDraftRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * FLYWHEEL-LOOP-CLOSURE Phase 1.4d (2026-05-17) W5 fix — focused unit tests
 * for {@link EphemeralScenarioCleanupService}.
 *
 * <p>The {@code @Transactional(REQUIRES_NEW)} interaction with Spring AOP is
 * exercised by the full {@code @SpringBootTest} IT suite (any IT that touches
 * the attribution path will exercise the cleanup at AB-run-complete). These
 * unit tests cover the in-method behaviour: null/empty no-op, deleteAllById
 * dispatch, and the try/catch that prevents a cleanup failure from masking
 * the original A/B failure cause.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EphemeralScenarioCleanupService (Phase 1.4d W5 fix)")
class EphemeralScenarioCleanupServiceTest {

    @Mock private EvalScenarioDraftRepository scenarioRepository;

    private EphemeralScenarioCleanupService service;

    @BeforeEach
    void setUp() {
        service = new EphemeralScenarioCleanupService(scenarioRepository);
    }

    @Test
    @DisplayName("null / empty list → no-op (deleteAllById never called)")
    void cleanupEphemerals_nullOrEmpty_noOp() {
        service.cleanupEphemerals(null);
        service.cleanupEphemerals(List.of());

        verifyNoInteractions(scenarioRepository);
    }

    @Test
    @DisplayName("non-empty list → deleteAllById delegated with the same ids")
    void cleanupEphemerals_nonEmpty_delegatesDelete() {
        List<String> ids = List.of("ephemeral-1", "ephemeral-2", "ephemeral-3");

        service.cleanupEphemerals(ids);

        verify(scenarioRepository).deleteAllById(eq(ids));
    }

    @Test
    @DisplayName("repository throws → swallowed (logged at WARN, doesn't propagate to caller's finally)")
    void cleanupEphemerals_repoThrows_swallowsForLogOnly() {
        doThrow(new RuntimeException("simulated DB error"))
                .when(scenarioRepository).deleteAllById(any());

        // Critical invariant: cleanup failure must not propagate, otherwise it
        // would mask the original A/B failure cause when invoked from an async
        // finally block (would leak as uncaught Future exception).
        assertThatCode(() -> service.cleanupEphemerals(List.of("x")))
                .doesNotThrowAnyException();
        verify(scenarioRepository).deleteAllById(any());
        // Only one delete call (not retried).
        verify(scenarioRepository, never().description("no retry inside helper"))
                .findById(any());
    }
}
