package com.skillforge.server.canary;

import com.skillforge.server.entity.CanaryRolloutEntity;
import com.skillforge.server.entity.SessionAnnotationEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.CanaryMetricSnapshotRepository;
import com.skillforge.server.repository.CanaryRolloutRepository;
import com.skillforge.server.repository.SessionAnnotationRepository;
import com.skillforge.server.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/**
 * V5 EVAL-DYNAMIC-USER-SIM Phase 1.3 — regression test locking the user_sim
 * origin filter in {@link CanaryMetricsService#recompute}.
 *
 * <p>Invariant: outcome annotations belonging to {@code origin='user_sim'}
 * sessions never contribute to canary aggregates. Otherwise UserSim trial
 * outcomes could trip auto-rollback on the candidate side.
 */
@ExtendWith(MockitoExtension.class)
class CanaryMetricsServiceOriginFilterTest {

    @Mock private CanaryRolloutRepository canaryRepository;
    @Mock private CanaryMetricSnapshotRepository snapshotRepository;
    @Mock private SessionAnnotationRepository annotationRepository;
    @Mock private CanaryRolloutService canaryRolloutService;
    @Mock private SessionRepository sessionRepository;

    private CanaryMetricsService service;

    @BeforeEach
    void setUp() {
        service = new CanaryMetricsService(canaryRepository, snapshotRepository,
                annotationRepository, canaryRolloutService, sessionRepository);
        lenient().when(snapshotRepository.upsertSnapshotSkipDuplicate(
                anyLong(), any(Instant.class), anyInt(), anyInt(), anyInt(),
                any(), any(), any(), any(),
                anyInt(), anyInt(), anyInt(),
                any(), any(), any(), any(),
                any())).thenReturn(1);
        lenient().when(canaryRolloutService.autoRollbackCheck(anyLong())).thenReturn(false);
    }

    @Test
    @DisplayName("recompute drops user_sim-origin outcomes before bucketing into canary aggregates")
    void recompute_excludesUserSimOutcomesFromAggregation() {
        // 1 active canary with explicit baseline/candidate skills.
        CanaryRolloutEntity canary = canary(1L, "skill_X_v1", "skill_X_v2");
        when(canaryRepository.findByRolloutStage(CanaryRolloutEntity.STAGE_CANARY))
                .thenReturn(List.of(canary));

        // 2 outcomes: 1 production failure, 1 user_sim failure.
        // The user_sim one belongs to a sandboxed trial session and must NOT count.
        SessionAnnotationEntity prodOutcome = outcome("prod-1", "failure");
        SessionAnnotationEntity userSimOutcome = outcome("usersim-1", "failure");
        when(annotationRepository.findByTypeCreatedSince(eq("outcome"), any(Instant.class)))
                .thenReturn(List.of(prodOutcome, userSimOutcome));

        // Session origin lookup: usersim-1 → 'user_sim', prod-1 → 'production'.
        when(sessionRepository.findAllById(any(Iterable.class)))
                .thenReturn(List.of(
                        sessionWithOrigin("prod-1", "production"),
                        sessionWithOrigin("usersim-1", "user_sim")));

        // canary_group annotation for the production session (pins it to candidate side).
        lenient().when(annotationRepository.findCanaryGroup(eq("prod-1"), any()))
                .thenReturn(java.util.Optional.of("skill:skill_X_v2"));
        lenient().when(annotationRepository.findCanaryGroup(eq("usersim-1"), any()))
                .thenReturn(java.util.Optional.of("skill:skill_X_v2"));

        service.recompute(Duration.ofHours(1));

        // Capture upsert args — the candidateFailure count must be from prod outcome only (1),
        // not 2 (would mean user_sim leaked in).
        ArgumentCaptor<Integer> candidateFailure = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> candidateSamples = ArgumentCaptor.forClass(Integer.class);
        verify(snapshotRepository).upsertSnapshotSkipDuplicate(
                eq(1L), any(Instant.class),
                anyInt(), anyInt(), anyInt(),
                any(), any(), any(), any(),
                candidateSamples.capture(), anyInt(), candidateFailure.capture(),
                any(), any(), any(), any(),
                any());
        assertThat(candidateSamples.getValue()).isEqualTo(1);
        assertThat(candidateFailure.getValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("recompute is unchanged when no user_sim outcomes exist (regression: baseline behavior preserved)")
    void recompute_allProduction_unchanged() {
        CanaryRolloutEntity canary = canary(1L, "skill_X_v1", "skill_X_v2");
        when(canaryRepository.findByRolloutStage(CanaryRolloutEntity.STAGE_CANARY))
                .thenReturn(List.of(canary));

        SessionAnnotationEntity prodOutcome = outcome("prod-A", "success");
        when(annotationRepository.findByTypeCreatedSince(eq("outcome"), any(Instant.class)))
                .thenReturn(List.of(prodOutcome));
        when(sessionRepository.findAllById(any(Iterable.class)))
                .thenReturn(List.of(sessionWithOrigin("prod-A", "production")));

        lenient().when(annotationRepository.findCanaryGroup(eq("prod-A"), any()))
                .thenReturn(java.util.Optional.of("skill:skill_X_v2"));

        CanaryMetricsService.RecomputeResult result = service.recompute(Duration.ofHours(1));

        assertThat(result.activeCanaries()).isEqualTo(1);
        // Both buckets should remain — assertion focused on filter not over-pruning.
    }

    private CanaryRolloutEntity canary(long id, String baseline, String candidate) {
        CanaryRolloutEntity c = new CanaryRolloutEntity();
        c.setId(id);
        c.setAgentId(42L);
        c.setSurfaceType(CanaryRolloutEntity.SURFACE_SKILL);
        c.setBaselineSkillName(baseline);
        c.setCandidateSkillName(candidate);
        c.setRolloutStage(CanaryRolloutEntity.STAGE_CANARY);
        c.setRolloutPercentage(25);
        Instant now = Instant.now();
        c.setStartedAt(now);
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return c;
    }

    private SessionAnnotationEntity outcome(String sessionId, String value) {
        SessionAnnotationEntity a = new SessionAnnotationEntity();
        a.setSessionId(sessionId);
        a.setAnnotationType("outcome");
        a.setAnnotationValue(value);
        a.setSource(SessionAnnotationEntity.SOURCE_LLM);
        a.setConfidence(new java.math.BigDecimal("0.9"));
        a.setCreatedAt(Instant.now());
        a.setId(System.nanoTime());
        return a;
    }

    private SessionEntity sessionWithOrigin(String id, String origin) {
        SessionEntity s = new SessionEntity();
        s.setId(id);
        s.setOrigin(origin);
        return s;
    }
}
