package com.skillforge.server.canary;

import com.skillforge.server.entity.CanaryMetricSnapshotEntity;
import com.skillforge.server.entity.CanaryRolloutEntity;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.improve.SkillAbEvalService;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.CanaryMetricSnapshotRepository;
import com.skillforge.server.repository.CanaryRolloutRepository;
import com.skillforge.server.repository.SkillRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SKILL-CANARY-ROLLOUT V2 Phase 1.3 — {@link CanaryRolloutService} unit tests.
 *
 * <p>Covers all 5 lifecycle methods + the auto-rollback decision logic +
 * SkillEntity sync + SkillAbEvalService.promoteCandidate integration on
 * publish. Persistence layer is verified separately by Phase 1.1's
 * {@code CanaryPersistenceIT}.
 */
@ExtendWith(MockitoExtension.class)
class CanaryRolloutServiceTest {

    @Mock private CanaryRolloutRepository canaryRepository;
    @Mock private CanaryMetricSnapshotRepository snapshotRepository;
    @Mock private SkillRepository skillRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private SkillAbEvalService skillAbEvalService;

    private CanaryRolloutService service;

    @BeforeEach
    void setUp() {
        service = new CanaryRolloutService(canaryRepository, snapshotRepository,
                skillRepository, agentRepository, skillAbEvalService);
        // Default: agent 42 exists, both skills exist with parent linkage on
        // candidate. Individual tests override as needed.
        lenient().when(agentRepository.existsById(42L)).thenReturn(true);
        lenient().when(skillRepository.findByName("my-skill"))
                .thenAnswer(inv -> Optional.of(skill(100L, "my-skill", null)));
        lenient().when(skillRepository.findByName("my-skill-v2"))
                .thenAnswer(inv -> Optional.of(skill(101L, "my-skill-v2", 100L)));
        lenient().when(skillRepository.save(any(SkillEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ───────────────────────── helpers ─────────────────────────

    private CanaryRolloutEntity activeCanary(long id, int pct) {
        CanaryRolloutEntity c = new CanaryRolloutEntity();
        c.setId(id);
        c.setAgentId(42L);
        c.setSurfaceType(CanaryRolloutEntity.SURFACE_SKILL);
        c.setBaselineSkillName("my-skill");
        c.setCandidateSkillName("my-skill-v2");
        c.setRolloutStage(CanaryRolloutEntity.STAGE_CANARY);
        c.setRolloutPercentage(pct);
        Instant now = Instant.now();
        c.setStartedAt(now);
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return c;
    }

    private SkillEntity skill(long id, String name, Long parentSkillId) {
        SkillEntity s = new SkillEntity();
        s.setId(id);
        s.setName(name);
        s.setParentSkillId(parentSkillId);
        s.setRolloutStage("production");
        s.setRolloutPercentage(100);
        s.setEnabled(true);
        return s;
    }

    private CanaryMetricSnapshotEntity snapshot(int controlSamples, int controlFailures,
                                                int candidateSamples, int candidateFailures) {
        CanaryMetricSnapshotEntity s = new CanaryMetricSnapshotEntity();
        s.setCanaryId(7L);
        s.setBucketAt(Instant.now());
        s.setControlSampleSize(controlSamples);
        s.setControlFailureCount(controlFailures);
        s.setCandidateSampleSize(candidateSamples);
        s.setCandidateFailureCount(candidateFailures);
        return s;
    }

    // ───────────────────────── startCanary ─────────────────────

    @Test
    @DisplayName("startCanary persists canary row + syncs candidate SkillEntity to stage=canary")
    void startCanary_persistsAndSyncsCandidate() {
        when(canaryRepository.findActiveCanaryByAgentAndSurface(42L, "skill"))
                .thenReturn(Optional.empty());
        when(canaryRepository.save(any(CanaryRolloutEntity.class)))
                .thenAnswer(inv -> {
                    CanaryRolloutEntity e = inv.getArgument(0);
                    e.setId(99L);
                    return e;
                });

        CanaryRolloutEntity created = service.startCanary(42L, "skill", "my-skill", "my-skill-v2", 10);

        assertThat(created.getId()).isEqualTo(99L);
        assertThat(created.getRolloutStage()).isEqualTo("canary");
        assertThat(created.getRolloutPercentage()).isEqualTo(10);

        // Candidate SkillEntity synced.
        ArgumentCaptor<SkillEntity> skillCap = ArgumentCaptor.forClass(SkillEntity.class);
        verify(skillRepository, times(1)).save(skillCap.capture());
        SkillEntity savedSkill = skillCap.getValue();
        assertThat(savedSkill.getName()).isEqualTo("my-skill-v2");
        assertThat(savedSkill.getRolloutStage()).isEqualTo("canary");
        assertThat(savedSkill.getRolloutPercentage()).isEqualTo(10);
    }

    @Test
    @DisplayName("startCanary defaults blank surfaceType to 'skill'")
    void startCanary_defaultsSurfaceToSkill_whenBlank() {
        when(canaryRepository.findActiveCanaryByAgentAndSurface(42L, "skill"))
                .thenReturn(Optional.empty());
        when(canaryRepository.save(any(CanaryRolloutEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        CanaryRolloutEntity created = service.startCanary(42L, null, "my-skill", "my-skill-v2", 10);

        assertThat(created.getSurfaceType()).isEqualTo("skill");
    }

    @Test
    @DisplayName("startCanary rejects non-skill surface (V2 scope)")
    void startCanary_rejectsNonSkillSurface() {
        assertThatThrownBy(() -> service.startCanary(42L, "prompt", "a", "b", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("surfaceType must be 'skill'");
    }

    @Test
    @DisplayName("startCanary rejects when active canary already exists on (agent, surface)")
    void startCanary_throwsConflict_whenActiveCanaryExists() {
        when(canaryRepository.findActiveCanaryByAgentAndSurface(42L, "skill"))
                .thenReturn(Optional.of(activeCanary(7L, 25)));

        assertThatThrownBy(() -> service.startCanary(42L, "skill", "my-skill", "my-skill-v2", 10))
                .isInstanceOf(CanaryStateException.class)
                .hasMessageContaining("already has an active canary")
                .hasMessageContaining("id=7");
        verify(canaryRepository, never()).save(any(CanaryRolloutEntity.class));
    }

    @Test
    @DisplayName("startCanary rejects when agentId does not exist")
    void startCanary_throwsBadRequest_whenAgentMissing() {
        when(agentRepository.existsById(999L)).thenReturn(false);

        assertThatThrownBy(() -> service.startCanary(999L, "skill", "my-skill", "my-skill-v2", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Agent not found");
    }

    @Test
    @DisplayName("startCanary rejects when baseline skill missing")
    void startCanary_throwsBadRequest_whenBaselineSkillMissing() {
        when(skillRepository.findByName("ghost-baseline")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.startCanary(42L, "skill", "ghost-baseline", "my-skill-v2", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Baseline skill not found");
    }

    @Test
    @DisplayName("startCanary rejects when candidate skill missing")
    void startCanary_throwsBadRequest_whenCandidateSkillMissing() {
        when(skillRepository.findByName("ghost-candidate")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.startCanary(42L, "skill", "my-skill", "ghost-candidate", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Candidate skill not found");
    }

    @Test
    @DisplayName("startCanary rejects baseline==candidate")
    void startCanary_throwsBadRequest_whenBaselineEqualsCandidate() {
        assertThatThrownBy(() -> service.startCanary(42L, "skill", "x", "x", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must differ");
    }

    @Test
    @DisplayName("startCanary rejects percentage out of [0, 100]")
    void startCanary_throwsBadRequest_whenPctOutOfRange() {
        assertThatThrownBy(() -> service.startCanary(42L, "skill", "a", "b", -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("percentage must be in [0, 100]");
        assertThatThrownBy(() -> service.startCanary(42L, "skill", "a", "b", 101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ───────────────────────── stepUp ──────────────────────────

    @Test
    @DisplayName("stepUp raises percentage on rollout row AND candidate SkillEntity")
    void stepUp_raisesAndSyncsSkill() {
        when(canaryRepository.findById(7L)).thenReturn(Optional.of(activeCanary(7L, 10)));
        when(canaryRepository.save(any(CanaryRolloutEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        CanaryRolloutEntity updated = service.stepUp(7L, 25);

        assertThat(updated.getRolloutPercentage()).isEqualTo(25);

        // SkillEntity also updated.
        ArgumentCaptor<SkillEntity> cap = ArgumentCaptor.forClass(SkillEntity.class);
        verify(skillRepository, atLeastOnce()).save(cap.capture());
        SkillEntity skillSaved = cap.getValue();
        assertThat(skillSaved.getName()).isEqualTo("my-skill-v2");
        assertThat(skillSaved.getRolloutPercentage()).isEqualTo(25);
    }

    @Test
    @DisplayName("stepUp rejects step-down or stay-flat (must strictly increase)")
    void stepUp_throwsBadRequest_whenNotStrictIncrease() {
        when(canaryRepository.findById(7L)).thenReturn(Optional.of(activeCanary(7L, 50)));

        assertThatThrownBy(() -> service.stepUp(7L, 50))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("strictly increase");
        assertThatThrownBy(() -> service.stepUp(7L, 25))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("stepUp rejects when canary not in stage=canary")
    void stepUp_throwsConflict_whenStageIsNotCanary() {
        CanaryRolloutEntity c = activeCanary(7L, 100);
        c.setRolloutStage(CanaryRolloutEntity.STAGE_PRODUCTION);
        when(canaryRepository.findById(7L)).thenReturn(Optional.of(c));

        assertThatThrownBy(() -> service.stepUp(7L, 100))
                .isInstanceOf(CanaryStateException.class)
                .hasMessageContaining("stage is production");
    }

    @Test
    @DisplayName("stepUp throws 404 when canary not found")
    void stepUp_throwsNotFound_whenCanaryMissing() {
        when(canaryRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.stepUp(99L, 50))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("99");
    }

    // ───────────────────────── publish ─────────────────────────

    @Test
    @DisplayName("publish transitions canary→production, syncs both skills, calls promoteCandidate")
    void publish_transitionsAndPromotesCandidate() {
        when(canaryRepository.findById(7L)).thenReturn(Optional.of(activeCanary(7L, 50)));
        when(canaryRepository.save(any(CanaryRolloutEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        CanaryRolloutEntity updated = service.publish(7L);

        assertThat(updated.getRolloutStage()).isEqualTo("production");
        assertThat(updated.getRolloutPercentage()).isEqualTo(100);
        assertThat(updated.getDecision()).isEqualTo("promoted");
        assertThat(updated.getLastDecisionAt()).isNotNull();

        // Capture all SkillEntity saves — both baseline (disabled) and candidate (production/100).
        ArgumentCaptor<SkillEntity> cap = ArgumentCaptor.forClass(SkillEntity.class);
        verify(skillRepository, atLeastOnce()).save(cap.capture());
        List<SkillEntity> saves = cap.getAllValues();
        assertThat(saves).anySatisfy(s -> {
            assertThat(s.getName()).isEqualTo("my-skill");
            assertThat(s.getRolloutStage()).isEqualTo("disabled");
        });
        assertThat(saves).anySatisfy(s -> {
            assertThat(s.getName()).isEqualTo("my-skill-v2");
            assertThat(s.getRolloutStage()).isEqualTo("production");
            assertThat(s.getRolloutPercentage()).isEqualTo(100);
        });

        // promoteCandidate called with the candidate SkillEntity.
        ArgumentCaptor<SkillEntity> promoteCap = ArgumentCaptor.forClass(SkillEntity.class);
        verify(skillAbEvalService, times(1)).promoteCandidate(promoteCap.capture());
        assertThat(promoteCap.getValue().getName()).isEqualTo("my-skill-v2");
    }

    @Test
    @DisplayName("publish rejects when canary not in stage=canary")
    void publish_throwsConflict_whenStageNotCanary() {
        CanaryRolloutEntity c = activeCanary(7L, 100);
        c.setRolloutStage(CanaryRolloutEntity.STAGE_ROLLED_BACK);
        when(canaryRepository.findById(7L)).thenReturn(Optional.of(c));

        assertThatThrownBy(() -> service.publish(7L))
                .isInstanceOf(CanaryStateException.class);
        verify(skillAbEvalService, never()).promoteCandidate(any());
    }

    @Test
    @DisplayName("publish degrades gracefully when candidate skill row missing — promote skipped, rollout still flipped")
    void publish_skipsPromote_whenCandidateSkillMissing() {
        when(canaryRepository.findById(7L)).thenReturn(Optional.of(activeCanary(7L, 50)));
        when(canaryRepository.save(any(CanaryRolloutEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(skillRepository.findByName("my-skill-v2")).thenReturn(Optional.empty());

        CanaryRolloutEntity updated = service.publish(7L);

        // Canary row still transitioned (source of truth).
        assertThat(updated.getRolloutStage()).isEqualTo("production");
        // Promote not called when candidate row missing.
        verify(skillAbEvalService, never()).promoteCandidate(any());
    }

    // ───────────────────────── rollback ────────────────────────

    @Test
    @DisplayName("rollback transitions canary→rolled_back + syncs candidate SkillEntity, baseline untouched")
    void rollback_transitionsAndSyncsCandidateOnly() {
        when(canaryRepository.findById(7L)).thenReturn(Optional.of(activeCanary(7L, 25)));
        when(canaryRepository.save(any(CanaryRolloutEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        CanaryRolloutEntity updated = service.rollback(7L, "manual operator rollback");

        assertThat(updated.getRolloutStage()).isEqualTo("rolled_back");
        assertThat(updated.getRolloutPercentage()).isEqualTo(0);
        assertThat(updated.getDecision()).isEqualTo("rolled_back");

        // Only candidate is saved; baseline is not touched by rollback.
        ArgumentCaptor<SkillEntity> cap = ArgumentCaptor.forClass(SkillEntity.class);
        verify(skillRepository, times(1)).save(cap.capture());
        SkillEntity savedSkill = cap.getValue();
        assertThat(savedSkill.getName()).isEqualTo("my-skill-v2");
        assertThat(savedSkill.getRolloutStage()).isEqualTo("rolled_back");
        assertThat(savedSkill.getRolloutPercentage()).isEqualTo(0);
    }

    @Test
    @DisplayName("rollback rejects when canary already terminal")
    void rollback_throwsConflict_whenStageIsTerminal() {
        CanaryRolloutEntity c = activeCanary(7L, 100);
        c.setRolloutStage(CanaryRolloutEntity.STAGE_PRODUCTION);
        when(canaryRepository.findById(7L)).thenReturn(Optional.of(c));

        assertThatThrownBy(() -> service.rollback(7L, "manual"))
                .isInstanceOf(CanaryStateException.class);
    }

    // ───────────────────────── autoRollbackCheck ───────────────

    @Test
    @DisplayName("autoRollbackCheck triggers rollback when ratio > 1.5 AND samples >= 50")
    void autoRollbackCheck_triggers_whenRatioExceedsAndSamplesEnough() {
        when(canaryRepository.findById(7L)).thenReturn(Optional.of(activeCanary(7L, 25)));
        when(canaryRepository.save(any(CanaryRolloutEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        // control 100/10 = 10%; candidate 60/12 = 20%; ratio 2.0 > 1.5
        when(snapshotRepository.findAllByCanaryId(7L)).thenReturn(List.of(
                snapshot(100, 10, 60, 12)
        ));

        boolean triggered = service.autoRollbackCheck(7L);

        assertThat(triggered).isTrue();
        ArgumentCaptor<CanaryRolloutEntity> cap = ArgumentCaptor.forClass(CanaryRolloutEntity.class);
        verify(canaryRepository, atLeastOnce()).save(cap.capture());
        CanaryRolloutEntity last = cap.getAllValues().get(cap.getAllValues().size() - 1);
        assertThat(last.getRolloutStage()).isEqualTo("rolled_back");
        assertThat(last.getRolloutPercentage()).isEqualTo(0);
    }

    @Test
    @DisplayName("autoRollbackCheck does NOT trigger when candidate samples < 50")
    void autoRollbackCheck_doesNotTrigger_belowSampleThreshold() {
        when(canaryRepository.findById(7L)).thenReturn(Optional.of(activeCanary(7L, 25)));
        when(snapshotRepository.findAllByCanaryId(7L)).thenReturn(List.of(
                snapshot(100, 5, 30, 20)
        ));

        boolean triggered = service.autoRollbackCheck(7L);

        assertThat(triggered).isFalse();
        verify(canaryRepository, never()).save(any(CanaryRolloutEntity.class));
    }

    @Test
    @DisplayName("autoRollbackCheck does NOT trigger when ratio <= 1.5")
    void autoRollbackCheck_doesNotTrigger_whenRatioUnderThreshold() {
        when(canaryRepository.findById(7L)).thenReturn(Optional.of(activeCanary(7L, 25)));
        when(snapshotRepository.findAllByCanaryId(7L)).thenReturn(List.of(
                snapshot(100, 10, 60, 8)  // 13.3% / 10% = 1.33
        ));

        boolean triggered = service.autoRollbackCheck(7L);

        assertThat(triggered).isFalse();
    }

    @Test
    @DisplayName("autoRollbackCheck no-op on empty snapshot table (Phase 1.4 not running yet)")
    void autoRollbackCheck_noOp_whenSnapshotsEmpty() {
        when(canaryRepository.findById(7L)).thenReturn(Optional.of(activeCanary(7L, 25)));
        when(snapshotRepository.findAllByCanaryId(7L)).thenReturn(List.of());

        boolean triggered = service.autoRollbackCheck(7L);

        assertThat(triggered).isFalse();
        verify(canaryRepository, never()).save(any(CanaryRolloutEntity.class));
    }

    @Test
    @DisplayName("autoRollbackCheck no-op when stage is not canary")
    void autoRollbackCheck_noOp_whenStageIsTerminal() {
        CanaryRolloutEntity c = activeCanary(7L, 100);
        c.setRolloutStage(CanaryRolloutEntity.STAGE_PRODUCTION);
        when(canaryRepository.findById(7L)).thenReturn(Optional.of(c));

        boolean triggered = service.autoRollbackCheck(7L);

        assertThat(triggered).isFalse();
        verify(snapshotRepository, never()).findAllByCanaryId(anyLong());
    }

    @Test
    @DisplayName("autoRollbackCheck stays lenient when control rate is zero (no flap on first candidate failure)")
    void autoRollbackCheck_lenient_whenControlPerfect() {
        when(canaryRepository.findById(7L)).thenReturn(Optional.of(activeCanary(7L, 25)));
        when(snapshotRepository.findAllByCanaryId(7L)).thenReturn(List.of(
                snapshot(100, 0, 100, 5)
        ));

        boolean triggered = service.autoRollbackCheck(7L);

        assertThat(triggered).isFalse();
    }

    // ───────────────────────── listByAgent / findById / metrics

    @Test
    @DisplayName("listByAgent returns all rollouts when stage filter omitted")
    void listByAgent_returnsAll_whenStageNull() {
        CanaryRolloutEntity a = activeCanary(1L, 25);
        CanaryRolloutEntity b = activeCanary(2L, 50);
        b.setRolloutStage(CanaryRolloutEntity.STAGE_ROLLED_BACK);
        when(canaryRepository.findByAgentIdAndSurfaceType(42L, "skill")).thenReturn(List.of(a, b));

        List<CanaryRolloutEntity> result = service.listByAgent(42L, null, null);

        assertThat(result).containsExactly(a, b);
    }

    @Test
    @DisplayName("listByAgent filters by stage when provided")
    void listByAgent_filtersByStage() {
        CanaryRolloutEntity a = activeCanary(1L, 25);
        CanaryRolloutEntity b = activeCanary(2L, 50);
        b.setRolloutStage(CanaryRolloutEntity.STAGE_ROLLED_BACK);
        when(canaryRepository.findByAgentIdAndSurfaceType(42L, "skill")).thenReturn(List.of(a, b));

        List<CanaryRolloutEntity> result = service.listByAgent(42L, "skill", "canary");

        assertThat(result).containsExactly(a);
    }

    @Test
    @DisplayName("listByAgent rejects null agentId")
    void listByAgent_rejectsNullAgentId() {
        assertThatThrownBy(() -> service.listByAgent(null, "skill", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("findById returns entity when present, NoSuchElementException otherwise")
    void findById_returnsOrThrows() {
        CanaryRolloutEntity c = activeCanary(7L, 25);
        when(canaryRepository.findById(7L)).thenReturn(Optional.of(c));
        when(canaryRepository.findById(99L)).thenReturn(Optional.empty());

        assertThat(service.findById(7L)).isEqualTo(c);
        assertThatThrownBy(() -> service.findById(99L))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("findMetricSnapshots returns paged list when canary exists, 404 when missing")
    void findMetricSnapshots_returnsOrThrows() {
        when(canaryRepository.existsById(7L)).thenReturn(true);
        when(canaryRepository.existsById(99L)).thenReturn(false);
        CanaryMetricSnapshotEntity snap = snapshot(50, 5, 25, 3);
        when(snapshotRepository.findByCanaryIdOrderByBucketAtDesc(eq(7L), any(Pageable.class)))
                .thenReturn(List.of(snap));

        List<CanaryMetricSnapshotEntity> result = service.findMetricSnapshots(7L, 24);

        assertThat(result).containsExactly(snap);
        // Verify limit propagated to the pageable.
        ArgumentCaptor<Pageable> pageCap = ArgumentCaptor.forClass(Pageable.class);
        verify(snapshotRepository).findByCanaryIdOrderByBucketAtDesc(eq(7L), pageCap.capture());
        assertThat(pageCap.getValue().getPageSize()).isEqualTo(24);

        // Missing canary → 404 path.
        assertThatThrownBy(() -> service.findMetricSnapshots(99L, 24))
                .isInstanceOf(NoSuchElementException.class);

        // limit <= 0 → empty list (no DB call).
        assertThat(service.findMetricSnapshots(7L, 0)).isEmpty();
    }
}
