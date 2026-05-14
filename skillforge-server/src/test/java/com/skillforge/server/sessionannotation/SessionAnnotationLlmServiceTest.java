package com.skillforge.server.sessionannotation;

import com.skillforge.server.repository.SessionAnnotationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PROD-LABEL-CLUSTER V1 (Phase 1.3): Mockito-style unit tests for the LLM-stage
 * annotation service. Mocks {@link SessionAnnotationRepository} so the tests
 * run without Docker / a real Postgres (matches the rest of the
 * sessionannotation test package).
 *
 * <p>Covers per Phase 1.3 brief:
 * <ul>
 *   <li>writes outcome + suspect_surface rows in the minimum 2-row case</li>
 *   <li>adds the top_failing_tool row when supplied</li>
 *   <li>omits top_failing_tool when null / blank</li>
 *   <li>rejects invalid outcome / suspect_surface enum values</li>
 *   <li>rejects out-of-range confidence</li>
 *   <li>guards null sessionId / reasoning</li>
 *   <li>idempotent on identical re-run (DataIntegrityViolation caught, no fail)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SessionAnnotationLlmService")
class SessionAnnotationLlmServiceTest {

    @Mock private SessionAnnotationRepository sessionAnnotationRepository;

    private SessionAnnotationLlmService service;

    @BeforeEach
    void setUp() {
        service = new SessionAnnotationLlmService(sessionAnnotationRepository);
    }

    @Test
    @DisplayName("writes outcome + suspect_surface rows in the minimum case")
    void annotateSession_writesOutcomeAndSuspectSurfaceRows_minimumCase() {
        AtomicLong idGen = new AtomicLong(100L);
        when(sessionAnnotationRepository.upsertSkipDuplicate(
                anyString(), anyString(), anyString(), anyString(), any(BigDecimal.class), any()))
                .thenAnswer(inv -> idGen.getAndIncrement());

        List<Long> ids = service.annotateSession(
                "sess-A",
                "failure",
                "skill",
                new BigDecimal("0.85"),
                "FileWrite returned non-zero status",
                null);

        assertThat(ids).hasSize(2).containsExactly(100L, 101L);

        ArgumentCaptor<String> typeCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCap = ArgumentCaptor.forClass(String.class);
        verify(sessionAnnotationRepository, times(2)).upsertSkipDuplicate(
                eq("sess-A"),
                typeCap.capture(),
                valueCap.capture(),
                eq("llm"),
                eq(new BigDecimal("0.85")),
                eq("FileWrite returned non-zero status"));

        // First call = outcome row, second = suspect_surface row (order preserved).
        assertThat(typeCap.getAllValues()).containsExactly("outcome", "suspect_surface");
        assertThat(valueCap.getAllValues()).containsExactly("failure", "skill");
    }

    @Test
    @DisplayName("writes the top_failing_tool row when supplied")
    void annotateSession_writesTopFailingToolRow_whenProvided() {
        AtomicLong idGen = new AtomicLong(200L);
        when(sessionAnnotationRepository.upsertSkipDuplicate(
                anyString(), anyString(), anyString(), anyString(), any(BigDecimal.class), any()))
                .thenAnswer(inv -> idGen.getAndIncrement());

        List<Long> ids = service.annotateSession(
                "sess-B",
                "partial_success",
                "prompt",
                new BigDecimal("0.60"),
                "agent recovered after one retry",
                "BashTool");

        assertThat(ids).hasSize(3).containsExactly(200L, 201L, 202L);
        // 3rd call must be the top_failing_tool row.
        verify(sessionAnnotationRepository).upsertSkipDuplicate(
                eq("sess-B"),
                eq("top_failing_tool"),
                eq("BashTool"),
                eq("llm"),
                eq(new BigDecimal("0.60")),
                eq("agent recovered after one retry"));
        verify(sessionAnnotationRepository, times(3)).upsertSkipDuplicate(
                anyString(), anyString(), anyString(), anyString(), any(BigDecimal.class), any());
    }

    @Test
    @DisplayName("omits the top_failing_tool row when null or blank")
    void annotateSession_omitsTopFailingToolRow_whenNullOrBlank() {
        when(sessionAnnotationRepository.upsertSkipDuplicate(
                anyString(), anyString(), anyString(), anyString(), any(BigDecimal.class), any()))
                .thenReturn(1L);

        // null
        List<Long> idsNull = service.annotateSession(
                "sess-N", "success", "unclear", BigDecimal.ONE, "all good", null);
        assertThat(idsNull).hasSize(2);

        // blank
        List<Long> idsBlank = service.annotateSession(
                "sess-N", "success", "unclear", BigDecimal.ONE, "all good", "   ");
        assertThat(idsBlank).hasSize(2);

        // 2 calls × 2 rows = 4 upserts total; the third row was never attempted
        verify(sessionAnnotationRepository, times(4)).upsertSkipDuplicate(
                anyString(), anyString(), anyString(), anyString(), any(BigDecimal.class), any());
    }

    @Test
    @DisplayName("rejects invalid outcome with IllegalArgumentException")
    void annotateSession_rejectsInvalidOutcome() {
        assertThatThrownBy(() -> service.annotateSession(
                "sess-X", "kinda_ok", "skill", BigDecimal.ONE, "...", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outcome");
        verify(sessionAnnotationRepository, never()).upsertSkipDuplicate(
                anyString(), anyString(), anyString(), anyString(), any(BigDecimal.class), any());
    }

    @Test
    @DisplayName("rejects invalid suspect_surface with IllegalArgumentException")
    void annotateSession_rejectsInvalidSuspectSurface() {
        assertThatThrownBy(() -> service.annotateSession(
                "sess-X", "failure", "skill_v2", BigDecimal.ONE, "...", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("suspect_surface");
        verify(sessionAnnotationRepository, never()).upsertSkipDuplicate(
                anyString(), anyString(), anyString(), anyString(), any(BigDecimal.class), any());
    }

    @Test
    @DisplayName("rejects confidence outside [0, 1]")
    void annotateSession_rejectsConfidenceOutOfRange() {
        assertThatThrownBy(() -> service.annotateSession(
                "sess-X", "failure", "skill", new BigDecimal("-0.01"), "...", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confidence");
        assertThatThrownBy(() -> service.annotateSession(
                "sess-X", "failure", "skill", new BigDecimal("1.01"), "...", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confidence");
        assertThatThrownBy(() -> service.annotateSession(
                "sess-X", "failure", "skill", null, "...", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confidence");
        verify(sessionAnnotationRepository, never()).upsertSkipDuplicate(
                anyString(), anyString(), anyString(), anyString(), any(BigDecimal.class), any());
    }

    @Test
    @DisplayName("rejects null or blank sessionId / reasoning")
    void annotateSession_rejectsBlankRequiredFields() {
        assertThatThrownBy(() -> service.annotateSession(
                null, "failure", "skill", BigDecimal.ONE, "ok", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sessionId");
        assertThatThrownBy(() -> service.annotateSession(
                "  ", "failure", "skill", BigDecimal.ONE, "ok", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sessionId");
        assertThatThrownBy(() -> service.annotateSession(
                "sess-X", "failure", "skill", BigDecimal.ONE, "", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reasoning");
        verify(sessionAnnotationRepository, never()).upsertSkipDuplicate(
                anyString(), anyString(), anyString(), anyString(), any(BigDecimal.class), any());
    }

    @Test
    @DisplayName("idempotent on identical re-run — ON CONFLICT returns null, ids omitted")
    void annotateSession_isIdempotent_onIdenticalRerun() {
        // V1 W2 fix: repository now returns null on UNIQUE conflict (native
        // ON CONFLICT DO NOTHING RETURNING id) instead of throwing DIVE.
        when(sessionAnnotationRepository.upsertSkipDuplicate(
                anyString(), anyString(), anyString(), anyString(), any(BigDecimal.class), any()))
                .thenReturn(null);

        List<Long> ids = service.annotateSession(
                "sess-DUP", "failure", "skill",
                new BigDecimal("0.80"), "repeat judgment", "BashTool");

        // All 3 rows conflicted on UNIQUE — no ids returned, no throw to caller.
        assertThat(ids).isEmpty();
        verify(sessionAnnotationRepository, times(3)).upsertSkipDuplicate(
                anyString(), anyString(), anyString(), anyString(), any(BigDecimal.class), any());
    }
}
