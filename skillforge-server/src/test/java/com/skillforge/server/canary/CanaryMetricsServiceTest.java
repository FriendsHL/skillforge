package com.skillforge.server.canary;

import com.skillforge.server.canary.CanaryMetricsService.RecomputeResult;
import com.skillforge.server.entity.CanaryRolloutEntity;
import com.skillforge.server.entity.SessionAnnotationEntity;
import com.skillforge.server.repository.CanaryMetricSnapshotRepository;
import com.skillforge.server.repository.CanaryRolloutRepository;
import com.skillforge.server.repository.SessionAnnotationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SKILL-CANARY-ROLLOUT V2 Phase 1.4 — {@link CanaryMetricsService} unit tests.
 *
 * <p>Coverage (12 cases per team-lead brief + 2 helper-method cases):
 *
 * <ol>
 *   <li>No active canaries → returns 0/0/0 with no DB calls.</li>
 *   <li>1 canary correctly buckets outcomes into control/candidate.</li>
 *   <li>failRateRatio computed correctly (candidate 50% / control 25% → 2.000).</li>
 *   <li>Session without canary_group annotation skipped.</li>
 *   <li>partial_success counts as success; cancelled counts as failure (brief §6).</li>
 *   <li>Per-session dedupe: only the latest outcome kept when multiple exist.</li>
 *   <li>Empty window → snapshot still written (0/0/0 row, dashboard renders empty bucket).</li>
 *   <li>Multi-canary: one canary's autoRollback exception does not break the other.</li>
 *   <li>ON CONFLICT returns 0 (idempotent) → snapshotsWritten not incremented.</li>
 *   <li>upsert throws DataAccessException → graceful per-canary skip.</li>
 *   <li>autoRollback triggered count surfaced correctly.</li>
 *   <li>Invalid window (null / zero / negative) rejected.</li>
 *   <li>control 0-failures → failRateRatio = null (defensive div/0).</li>
 *   <li>{@code parseSkillName} helper rejects malformed values.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class CanaryMetricsServiceTest {

    @Mock private CanaryRolloutRepository canaryRepository;
    @Mock private CanaryMetricSnapshotRepository snapshotRepository;
    @Mock private SessionAnnotationRepository annotationRepository;
    @Mock private CanaryRolloutService canaryRolloutService;
    @Mock private com.skillforge.server.repository.SessionRepository sessionRepository;

    private CanaryMetricsService service;

    @BeforeEach
    void setUp() {
        service = new CanaryMetricsService(canaryRepository, snapshotRepository,
                annotationRepository, canaryRolloutService, sessionRepository);
        // V5 Phase 1.3: existing tests don't simulate user_sim sessions — return empty
        // so excludeUserSimOutcomes is a no-op (all annotations pass through unchanged).
        lenient().when(sessionRepository.findAllById(any(Iterable.class)))
                .thenReturn(java.util.Collections.emptyList());
        // Default: upsert returns 1 (inserted). Tests override for ON CONFLICT / throw cases.
        lenient().when(snapshotRepository.upsertSnapshotSkipDuplicate(
                anyLong(), any(Instant.class), anyInt(), anyInt(), anyInt(),
                any(), any(), any(), any(),
                anyInt(), anyInt(), anyInt(),
                any(), any(), any(), any(),
                any())).thenReturn(1);
        lenient().when(canaryRolloutService.autoRollbackCheck(anyLong())).thenReturn(false);
    }

    // ───────────────────────── helpers ─────────────────────────

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

    private SessionAnnotationEntity outcome(String sessionId, String outcome, Instant createdAt) {
        SessionAnnotationEntity a = new SessionAnnotationEntity();
        a.setSessionId(sessionId);
        a.setAnnotationType("outcome");
        a.setAnnotationValue(outcome);
        a.setSource("llm");
        a.setConfidence(new BigDecimal("0.85"));
        a.setCreatedAt(createdAt);
        return a;
    }

    private SessionAnnotationEntity outcome(String sessionId, String outcome) {
        return outcome(sessionId, outcome, Instant.now());
    }

    private String groupValue(String skillName) {
        return "skill:" + skillName;
    }

    // ───────────────────────── tests ───────────────────────────

    @Test
    @DisplayName("recompute returns zeros + skips work when no active canaries")
    void recompute_noOp_whenNoActiveCanaries() {
        when(canaryRepository.findByRolloutStage("canary")).thenReturn(List.of());

        RecomputeResult result = service.recompute(Duration.ofHours(1));

        assertThat(result.activeCanaries()).isEqualTo(0);
        assertThat(result.snapshotsWritten()).isEqualTo(0);
        assertThat(result.autoRollbacksTriggered()).isEqualTo(0);
        verify(annotationRepository, never()).findByTypeCreatedSince(anyString(), any());
        verify(canaryRolloutService, never()).autoRollbackCheck(anyLong());
    }

    @Test
    @DisplayName("recompute buckets outcomes into control / candidate via canary_group + computes failRateRatio")
    void recompute_bucketsAndComputesRatio() {
        CanaryRolloutEntity c = canary(7L, "my-skill", "my-skill-v2");
        when(canaryRepository.findByRolloutStage("canary")).thenReturn(List.of(c));

        // control: 4 samples (3 success, 1 failure → 25%); candidate: 4 samples (2 success, 2 failure → 50%)
        // → ratio 2.0
        when(annotationRepository.findByTypeCreatedSince(eq("outcome"), any())).thenReturn(List.of(
                outcome("s1-ctrl", "success"),
                outcome("s2-ctrl", "success"),
                outcome("s3-ctrl", "success"),
                outcome("s4-ctrl", "failure"),
                outcome("s5-cand", "success"),
                outcome("s6-cand", "success"),
                outcome("s7-cand", "failure"),
                outcome("s8-cand", "failure")
        ));
        when(annotationRepository.findCanaryGroup("s1-ctrl", "skill")).thenReturn(Optional.of(groupValue("my-skill")));
        when(annotationRepository.findCanaryGroup("s2-ctrl", "skill")).thenReturn(Optional.of(groupValue("my-skill")));
        when(annotationRepository.findCanaryGroup("s3-ctrl", "skill")).thenReturn(Optional.of(groupValue("my-skill")));
        when(annotationRepository.findCanaryGroup("s4-ctrl", "skill")).thenReturn(Optional.of(groupValue("my-skill")));
        when(annotationRepository.findCanaryGroup("s5-cand", "skill")).thenReturn(Optional.of(groupValue("my-skill-v2")));
        when(annotationRepository.findCanaryGroup("s6-cand", "skill")).thenReturn(Optional.of(groupValue("my-skill-v2")));
        when(annotationRepository.findCanaryGroup("s7-cand", "skill")).thenReturn(Optional.of(groupValue("my-skill-v2")));
        when(annotationRepository.findCanaryGroup("s8-cand", "skill")).thenReturn(Optional.of(groupValue("my-skill-v2")));

        RecomputeResult result = service.recompute(Duration.ofHours(1));

        assertThat(result.activeCanaries()).isEqualTo(1);
        assertThat(result.snapshotsWritten()).isEqualTo(1);

        ArgumentCaptor<Long> canaryIdCap = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Instant> bucketCap = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Integer> ctrlSamplesCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> ctrlSuccessCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> ctrlFailureCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> candSamplesCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> candSuccessCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> candFailureCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<BigDecimal> ratioCap = ArgumentCaptor.forClass(BigDecimal.class);
        verify(snapshotRepository).upsertSnapshotSkipDuplicate(
                canaryIdCap.capture(), bucketCap.capture(),
                ctrlSamplesCap.capture(), ctrlSuccessCap.capture(), ctrlFailureCap.capture(),
                any(), any(), any(), any(),
                candSamplesCap.capture(), candSuccessCap.capture(), candFailureCap.capture(),
                any(), any(), any(), any(),
                ratioCap.capture());

        assertThat(canaryIdCap.getValue()).isEqualTo(7L);
        assertThat(bucketCap.getValue().getEpochSecond() % 3600).isEqualTo(0L);
        assertThat(ctrlSamplesCap.getValue()).isEqualTo(4);
        assertThat(ctrlSuccessCap.getValue()).isEqualTo(3);
        assertThat(ctrlFailureCap.getValue()).isEqualTo(1);
        assertThat(candSamplesCap.getValue()).isEqualTo(4);
        assertThat(candSuccessCap.getValue()).isEqualTo(2);
        assertThat(candFailureCap.getValue()).isEqualTo(2);
        // (2/4) / (1/4) = 2.000
        assertThat(ratioCap.getValue()).isEqualByComparingTo(new BigDecimal("2.000"));
    }

    @Test
    @DisplayName("recompute skips sessions without canary_group annotation")
    void recompute_skipsUngroupedSessions() {
        CanaryRolloutEntity c = canary(7L, "my-skill", "my-skill-v2");
        when(canaryRepository.findByRolloutStage("canary")).thenReturn(List.of(c));
        when(annotationRepository.findByTypeCreatedSince(eq("outcome"), any())).thenReturn(List.of(
                outcome("s-noncanary", "success"),
                outcome("s-canary", "failure")
        ));
        when(annotationRepository.findCanaryGroup("s-noncanary", "skill")).thenReturn(Optional.empty());
        when(annotationRepository.findCanaryGroup("s-canary", "skill"))
                .thenReturn(Optional.of(groupValue("my-skill")));

        service.recompute(Duration.ofHours(1));

        ArgumentCaptor<Integer> ctrlSamplesCap = ArgumentCaptor.forClass(Integer.class);
        verify(snapshotRepository).upsertSnapshotSkipDuplicate(
                eq(7L), any(),
                ctrlSamplesCap.capture(), anyInt(), anyInt(),
                any(), any(), any(), any(),
                anyInt(), anyInt(), anyInt(),
                any(), any(), any(), any(),
                any());
        assertThat(ctrlSamplesCap.getValue()).isEqualTo(1); // only s-canary counted
    }

    @Test
    @DisplayName("partial_success counts as success_count; cancelled counts as failure_count (brief §6 mapping)")
    void recompute_mapsPartialSuccessAndCancelled() {
        CanaryRolloutEntity c = canary(7L, "my-skill", "my-skill-v2");
        when(canaryRepository.findByRolloutStage("canary")).thenReturn(List.of(c));
        when(annotationRepository.findByTypeCreatedSince(eq("outcome"), any())).thenReturn(List.of(
                outcome("s1", "success"),
                outcome("s2", "partial_success"),
                outcome("s3", "cancelled"),
                outcome("s4", "failure")
        ));
        when(annotationRepository.findCanaryGroup(anyString(), eq("skill")))
                .thenReturn(Optional.of(groupValue("my-skill"))); // all control

        service.recompute(Duration.ofHours(1));

        ArgumentCaptor<Integer> samplesCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> successCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> failureCap = ArgumentCaptor.forClass(Integer.class);
        verify(snapshotRepository).upsertSnapshotSkipDuplicate(
                anyLong(), any(),
                samplesCap.capture(), successCap.capture(), failureCap.capture(),
                any(), any(), any(), any(),
                anyInt(), anyInt(), anyInt(),
                any(), any(), any(), any(),
                any());
        assertThat(samplesCap.getValue()).isEqualTo(4);
        assertThat(successCap.getValue()).isEqualTo(2); // success + partial_success
        assertThat(failureCap.getValue()).isEqualTo(2); // failure + cancelled
    }

    @Test
    @DisplayName("recompute dedupes per-session — only latest outcome kept (defensive)")
    void recompute_dedupesPerSession() {
        CanaryRolloutEntity c = canary(7L, "my-skill", "my-skill-v2");
        when(canaryRepository.findByRolloutStage("canary")).thenReturn(List.of(c));

        // Repository ordering is newest-first (per query). Same session, multiple annotations.
        Instant t1 = Instant.now();
        Instant t0 = t1.minusSeconds(60);
        when(annotationRepository.findByTypeCreatedSince(eq("outcome"), any())).thenReturn(List.of(
                outcome("s1", "failure", t1),   // latest
                outcome("s1", "success", t0)    // older — must be discarded
        ));
        when(annotationRepository.findCanaryGroup("s1", "skill"))
                .thenReturn(Optional.of(groupValue("my-skill")));

        service.recompute(Duration.ofHours(1));

        ArgumentCaptor<Integer> ctrlSamplesCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> ctrlSuccessCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> ctrlFailureCap = ArgumentCaptor.forClass(Integer.class);
        verify(snapshotRepository).upsertSnapshotSkipDuplicate(
                anyLong(), any(),
                ctrlSamplesCap.capture(), ctrlSuccessCap.capture(), ctrlFailureCap.capture(),
                any(), any(), any(), any(),
                anyInt(), anyInt(), anyInt(),
                any(), any(), any(), any(),
                any());
        assertThat(ctrlSamplesCap.getValue()).isEqualTo(1);
        assertThat(ctrlSuccessCap.getValue()).isEqualTo(0);
        assertThat(ctrlFailureCap.getValue()).isEqualTo(1); // latest (failure) wins
    }

    @Test
    @DisplayName("recompute writes empty (0/0/0) snapshot when canary has no window outcomes — dashboard renders empty bucket")
    void recompute_writesEmptySnapshot_whenNoData() {
        CanaryRolloutEntity c = canary(7L, "my-skill", "my-skill-v2");
        when(canaryRepository.findByRolloutStage("canary")).thenReturn(List.of(c));
        when(annotationRepository.findByTypeCreatedSince(eq("outcome"), any())).thenReturn(List.of());

        RecomputeResult result = service.recompute(Duration.ofHours(1));

        assertThat(result.snapshotsWritten()).isEqualTo(1);
        ArgumentCaptor<Integer> samplesCap = ArgumentCaptor.forClass(Integer.class);
        verify(snapshotRepository).upsertSnapshotSkipDuplicate(
                anyLong(), any(),
                samplesCap.capture(), anyInt(), anyInt(),
                any(), any(), any(), any(),
                anyInt(), anyInt(), anyInt(),
                any(), any(), any(), any(),
                any());
        assertThat(samplesCap.getValue()).isEqualTo(0); // empty snapshot
    }

    @Test
    @DisplayName("recompute treats ON CONFLICT (return 0) as idempotent skip — no snapshotsWritten++")
    void recompute_idempotentOnConflictReturn0() {
        CanaryRolloutEntity c = canary(7L, "my-skill", "my-skill-v2");
        when(canaryRepository.findByRolloutStage("canary")).thenReturn(List.of(c));
        when(annotationRepository.findByTypeCreatedSince(eq("outcome"), any())).thenReturn(List.of());
        when(snapshotRepository.upsertSnapshotSkipDuplicate(
                anyLong(), any(Instant.class), anyInt(), anyInt(), anyInt(),
                any(), any(), any(), any(),
                anyInt(), anyInt(), anyInt(),
                any(), any(), any(), any(),
                any())).thenReturn(0); // ON CONFLICT — row already exists

        RecomputeResult result = service.recompute(Duration.ofHours(1));

        assertThat(result.activeCanaries()).isEqualTo(1);
        assertThat(result.snapshotsWritten()).isEqualTo(0);
    }

    @Test
    @DisplayName("recompute upsert DataAccessException on one canary does not break the rest")
    void recompute_perCanaryDaeIsolated() {
        CanaryRolloutEntity c1 = canary(7L, "a", "a-v2");
        CanaryRolloutEntity c2 = canary(8L, "b", "b-v2");
        when(canaryRepository.findByRolloutStage("canary")).thenReturn(List.of(c1, c2));
        when(annotationRepository.findByTypeCreatedSince(eq("outcome"), any())).thenReturn(List.of());
        // First call throws; second returns 1.
        when(snapshotRepository.upsertSnapshotSkipDuplicate(
                eq(7L), any(Instant.class), anyInt(), anyInt(), anyInt(),
                any(), any(), any(), any(),
                anyInt(), anyInt(), anyInt(),
                any(), any(), any(), any(),
                any())).thenThrow(new DataAccessResourceFailureException("simulated PG hiccup"));
        when(snapshotRepository.upsertSnapshotSkipDuplicate(
                eq(8L), any(Instant.class), anyInt(), anyInt(), anyInt(),
                any(), any(), any(), any(),
                anyInt(), anyInt(), anyInt(),
                any(), any(), any(), any(),
                any())).thenReturn(1);

        RecomputeResult result = service.recompute(Duration.ofHours(1));

        // c1 threw (0 snapshots written), c2 succeeded (1).
        assertThat(result.snapshotsWritten()).isEqualTo(1);
        assertThat(result.activeCanaries()).isEqualTo(2);
    }

    @Test
    @DisplayName("recompute calls autoRollbackCheck for every active canary and counts triggers")
    void recompute_callsAutoRollbackAndCountsTriggers() {
        CanaryRolloutEntity c1 = canary(7L, "a", "a-v2");
        CanaryRolloutEntity c2 = canary(8L, "b", "b-v2");
        when(canaryRepository.findByRolloutStage("canary")).thenReturn(List.of(c1, c2));
        when(annotationRepository.findByTypeCreatedSince(eq("outcome"), any())).thenReturn(List.of());
        when(canaryRolloutService.autoRollbackCheck(7L)).thenReturn(true);
        when(canaryRolloutService.autoRollbackCheck(8L)).thenReturn(false);

        RecomputeResult result = service.recompute(Duration.ofHours(1));

        assertThat(result.autoRollbacksTriggered()).isEqualTo(1);
        verify(canaryRolloutService, times(1)).autoRollbackCheck(7L);
        verify(canaryRolloutService, times(1)).autoRollbackCheck(8L);
    }

    @Test
    @DisplayName("Phase 1.4 r1 W2 — autoRollback throwing on canary A still lets canary B's snapshot persist")
    void recompute_continuesOnAutoRollbackException_andStillWritesAllSnapshots() {
        // Two active canaries. canary A's autoRollbackCheck throws — this used to
        // poison the outer @Transactional and roll back canary B's snapshot too.
        // Post-fix: autoRollbackCheck runs in REQUIRES_NEW, so canary A's failure
        // is isolated and canary B's snapshot still persists.
        CanaryRolloutEntity cA = canary(7L, "a", "a-v2");
        CanaryRolloutEntity cB = canary(8L, "b", "b-v2");
        when(canaryRepository.findByRolloutStage("canary")).thenReturn(List.of(cA, cB));
        when(annotationRepository.findByTypeCreatedSince(eq("outcome"), any())).thenReturn(List.of());
        when(canaryRolloutService.autoRollbackCheck(7L))
                .thenThrow(new RuntimeException("auto-rollback exploded for canary A"));
        when(canaryRolloutService.autoRollbackCheck(8L)).thenReturn(false);

        RecomputeResult result = service.recompute(Duration.ofHours(1));

        // Both canaries get a snapshot row written before autoRollback even runs.
        assertThat(result.snapshotsWritten()).isEqualTo(2);
        verify(snapshotRepository, times(2)).upsertSnapshotSkipDuplicate(
                anyLong(), any(), anyInt(), anyInt(), anyInt(),
                any(), any(), any(), any(),
                anyInt(), anyInt(), anyInt(),
                any(), any(), any(), any(),
                any());
        // autoRollbackCheck called for both (A threw, B returned false → 0 triggered)
        verify(canaryRolloutService).autoRollbackCheck(7L);
        verify(canaryRolloutService).autoRollbackCheck(8L);
        assertThat(result.autoRollbacksTriggered()).isEqualTo(0);
    }

    @Test
    @DisplayName("autoRollbackCheck exception on one canary does not abort the rest of the tick")
    void recompute_autoRollbackFailureIsolated() {
        CanaryRolloutEntity c1 = canary(7L, "a", "a-v2");
        CanaryRolloutEntity c2 = canary(8L, "b", "b-v2");
        when(canaryRepository.findByRolloutStage("canary")).thenReturn(List.of(c1, c2));
        when(annotationRepository.findByTypeCreatedSince(eq("outcome"), any())).thenReturn(List.of());
        when(canaryRolloutService.autoRollbackCheck(7L))
                .thenThrow(new RuntimeException("simulated rollback failure"));
        when(canaryRolloutService.autoRollbackCheck(8L)).thenReturn(true);

        RecomputeResult result = service.recompute(Duration.ofHours(1));

        // c1 threw (not counted), c2 returned true.
        assertThat(result.autoRollbacksTriggered()).isEqualTo(1);
    }

    @Test
    @DisplayName("recompute rejects null / zero / negative window")
    void recompute_rejectsBadWindow() {
        assertThatThrownBy(() -> service.recompute(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive Duration");
        assertThatThrownBy(() -> service.recompute(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.recompute(Duration.ofHours(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("failRateRatio = null when control has zero failures (defensive div/0)")
    void recompute_failRateRatioNull_whenControlPerfect() {
        CanaryRolloutEntity c = canary(7L, "my-skill", "my-skill-v2");
        when(canaryRepository.findByRolloutStage("canary")).thenReturn(List.of(c));
        when(annotationRepository.findByTypeCreatedSince(eq("outcome"), any())).thenReturn(List.of(
                outcome("c1", "success"), outcome("c2", "success"),
                outcome("d1", "failure")
        ));
        when(annotationRepository.findCanaryGroup("c1", "skill")).thenReturn(Optional.of(groupValue("my-skill")));
        when(annotationRepository.findCanaryGroup("c2", "skill")).thenReturn(Optional.of(groupValue("my-skill")));
        when(annotationRepository.findCanaryGroup("d1", "skill")).thenReturn(Optional.of(groupValue("my-skill-v2")));

        service.recompute(Duration.ofHours(1));

        ArgumentCaptor<BigDecimal> ratioCap = ArgumentCaptor.forClass(BigDecimal.class);
        verify(snapshotRepository).upsertSnapshotSkipDuplicate(
                anyLong(), any(), anyInt(), anyInt(), anyInt(),
                any(), any(), any(), any(),
                anyInt(), anyInt(), anyInt(),
                any(), any(), any(), any(),
                ratioCap.capture());
        assertThat(ratioCap.getValue()).isNull();
    }

    @Test
    @DisplayName("parseSkillName helper rejects malformed group values")
    void parseSkillName_rejectsMalformed() {
        assertThat(CanaryMetricsService.parseSkillName("skill:my-skill")).isEqualTo("my-skill");
        assertThat(CanaryMetricsService.parseSkillName("skill:")).isNull();
        assertThat(CanaryMetricsService.parseSkillName("prompt:foo")).isNull();
        assertThat(CanaryMetricsService.parseSkillName(null)).isNull();
        assertThat(CanaryMetricsService.parseSkillName("garbage")).isNull();
    }

    @Test
    @DisplayName("recompute degrades gracefully when findByRolloutStage throws DataAccessException")
    void recompute_gracefulOnCanaryLookupFailure() {
        when(canaryRepository.findByRolloutStage("canary"))
                .thenThrow(new DataAccessResourceFailureException("DB unreachable"));

        RecomputeResult result = service.recompute(Duration.ofHours(1));

        assertThat(result.activeCanaries()).isEqualTo(0);
        assertThat(result.snapshotsWritten()).isEqualTo(0);
        assertThat(result.autoRollbacksTriggered()).isEqualTo(0);
        verify(annotationRepository, never()).findByTypeCreatedSince(anyString(), any());
    }
}
