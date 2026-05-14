package com.skillforge.server.canary;

import com.skillforge.server.AbstractPostgresIT;
import com.skillforge.server.entity.CanaryMetricSnapshotEntity;
import com.skillforge.server.entity.CanaryRolloutEntity;
import com.skillforge.server.repository.CanaryMetricSnapshotRepository;
import com.skillforge.server.repository.CanaryRolloutRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SKILL-CANARY-ROLLOUT V2 Phase 1.1: persistence integration tests for the
 * two new tables introduced by V77 + the V78 skill column additions.
 *
 * <p>Mirrors {@code SessionAnnotationPersistenceIT}: shared PostgreSQL
 * container with Flyway migrations applied (no schema mocking). Test
 * isolation comes from {@code @DataJpaTest} rolling back each test.
 *
 * <p>Covers (per Phase 1.1 task list):
 * <ol>
 *   <li>{@code t_canary_rollout} round-trip ({@code save} →
 *       {@link CanaryRolloutRepository#findActiveCanaryForSkill}).</li>
 *   <li>{@code uq_canary_active} partial UNIQUE INDEX rejects a second
 *       {@code rollout_stage='canary'} row for the same (agent_id,
 *       surface_type), while allowing other stages to coexist.</li>
 *   <li>{@code t_canary_metric_snapshot} round-trip +
 *       {@link CanaryMetricSnapshotRepository#findByCanaryIdOrderByBucketAtDesc}
 *       returns newest first.</li>
 *   <li>FK {@code ON DELETE CASCADE} purges metric_snapshot child rows when
 *       the parent canary_rollout row is deleted.</li>
 * </ol>
 *
 * <p>Skipped automatically on machines without Docker
 * (@{@code Testcontainers(disabledWithoutDocker = true)} in
 * {@link AbstractPostgresIT}).
 */
@DisplayName("SKILL-CANARY-ROLLOUT V2 persistence IT")
class CanaryPersistenceIT extends AbstractPostgresIT {

    @Autowired
    private CanaryRolloutRepository canaryRepository;

    @Autowired
    private CanaryMetricSnapshotRepository snapshotRepository;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void cleanUp() {
        // Child first (FK cascade would handle it on parent delete, but
        // explicit cleanup keeps each test independent of cascade timing).
        snapshotRepository.deleteAll();
        canaryRepository.deleteAll();
    }

    private CanaryRolloutEntity canary(Long agentId,
                                       String baselineName,
                                       String candidateName,
                                       String stage,
                                       int pct) {
        CanaryRolloutEntity c = new CanaryRolloutEntity();
        c.setSurfaceType(CanaryRolloutEntity.SURFACE_SKILL);
        c.setAgentId(agentId);
        c.setBaselineSkillName(baselineName);
        c.setCandidateSkillName(candidateName);
        c.setRolloutStage(stage);
        c.setRolloutPercentage(pct);
        Instant now = Instant.now();
        c.setStartedAt(now);
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return c;
    }

    private CanaryMetricSnapshotEntity snapshot(Long canaryId, Instant bucketAt) {
        CanaryMetricSnapshotEntity s = new CanaryMetricSnapshotEntity();
        s.setCanaryId(canaryId);
        s.setBucketAt(bucketAt);
        s.setControlSampleSize(100);
        s.setControlSuccessCount(95);
        s.setControlFailureCount(5);
        s.setControlAvgQuality(new BigDecimal("85.50"));
        s.setControlAvgLatency(new BigDecimal("1200.00"));
        s.setControlAvgCost(new BigDecimal("0.012345"));
        s.setCandidateSampleSize(10);
        s.setCandidateSuccessCount(9);
        s.setCandidateFailureCount(1);
        s.setCandidateAvgQuality(new BigDecimal("87.25"));
        s.setCandidateAvgLatency(new BigDecimal("1100.00"));
        s.setCandidateAvgCost(new BigDecimal("0.011000"));
        s.setFailRateRatio(new BigDecimal("1.000"));
        s.setCreatedAt(Instant.now());
        return s;
    }

    @Test
    @DisplayName("t_canary_rollout save round-trips and findActiveCanaryForSkill returns the active row")
    void canaryRollout_saveAndFindActive_roundtrips() {
        CanaryRolloutEntity saved = canaryRepository.save(
                canary(42L, "my-skill", "my-skill-v2",
                        CanaryRolloutEntity.STAGE_CANARY, 10));
        // Non-matching row (different agent) — must NOT be returned.
        canaryRepository.save(
                canary(99L, "my-skill", "my-skill-v2",
                        CanaryRolloutEntity.STAGE_CANARY, 10));
        // Non-matching row (different baseline name).
        canaryRepository.save(
                canary(42L, "other-skill", "other-skill-v2",
                        CanaryRolloutEntity.STAGE_CANARY, 10));
        // Non-matching row (terminal stage — production, not canary).
        canaryRepository.save(
                canary(42L, "old-skill", "old-skill-v2",
                        CanaryRolloutEntity.STAGE_PRODUCTION, 100));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getRolloutStage()).isEqualTo(CanaryRolloutEntity.STAGE_CANARY);

        Optional<CanaryRolloutEntity> hit =
                canaryRepository.findActiveCanaryForSkill(42L, "my-skill");
        assertThat(hit).isPresent();
        assertThat(hit.get().getId()).isEqualTo(saved.getId());
        assertThat(hit.get().getCandidateSkillName()).isEqualTo("my-skill-v2");
        assertThat(hit.get().getRolloutPercentage()).isEqualTo(10);

        // No active canary exists for an unrelated (agent, baseline) pair.
        assertThat(canaryRepository.findActiveCanaryForSkill(42L, "nonexistent"))
                .isEmpty();
        assertThat(canaryRepository.findActiveCanaryForSkill(123L, "my-skill"))
                .isEmpty();
    }

    @Test
    @DisplayName("uq_canary_active partial UNIQUE index rejects 2 active canaries on same (agent, surface); allows non-canary stages to coexist")
    void canaryRollout_uniqueActive_rejectsDuplicateButAllowsOtherStages() {
        // First active canary is fine.
        canaryRepository.save(
                canary(42L, "my-skill", "my-skill-v2",
                        CanaryRolloutEntity.STAGE_CANARY, 10));
        entityManager.flush();

        // A historical production row + a rolled_back row on the same (agent,
        // surface) must NOT conflict with the active canary — the UNIQUE INDEX
        // is partial WHERE rollout_stage='canary'.
        canaryRepository.save(
                canary(42L, "my-skill", "my-skill-v2",
                        CanaryRolloutEntity.STAGE_PRODUCTION, 100));
        canaryRepository.save(
                canary(42L, "my-skill", "my-skill-v3",
                        CanaryRolloutEntity.STAGE_ROLLED_BACK, 0));
        entityManager.flush();  // both above must commit without error

        // A second canary for the same (agent, surface) — must violate.
        canaryRepository.save(
                canary(42L, "my-skill", "my-skill-v3",
                        CanaryRolloutEntity.STAGE_CANARY, 20));

        assertThatThrownBy(entityManager::flush)
                .isInstanceOfAny(DataIntegrityViolationException.class,
                                 jakarta.persistence.PersistenceException.class);
    }

    @Test
    @DisplayName("t_canary_metric_snapshot save round-trips and findByCanaryIdOrderByBucketAtDesc returns newest first")
    void metricSnapshot_saveAndFindNewest_roundtrips() {
        CanaryRolloutEntity parent = canaryRepository.save(
                canary(42L, "my-skill", "my-skill-v2",
                        CanaryRolloutEntity.STAGE_CANARY, 10));

        Instant t0 = Instant.parse("2026-05-14T10:00:00Z");
        Instant t1 = t0.plus(1, ChronoUnit.HOURS);
        Instant t2 = t0.plus(2, ChronoUnit.HOURS);

        snapshotRepository.save(snapshot(parent.getId(), t0));
        snapshotRepository.save(snapshot(parent.getId(), t1));
        snapshotRepository.save(snapshot(parent.getId(), t2));
        // Unrelated snapshot on a different canary — must NOT be returned.
        CanaryRolloutEntity otherCanary = canaryRepository.save(
                canary(99L, "other", "other-v2",
                        CanaryRolloutEntity.STAGE_CANARY, 5));
        snapshotRepository.save(snapshot(otherCanary.getId(), t1));

        List<CanaryMetricSnapshotEntity> got = snapshotRepository
                .findByCanaryIdOrderByBucketAtDesc(parent.getId(), PageRequest.of(0, 24));

        assertThat(got).hasSize(3);
        assertThat(got).extracting(CanaryMetricSnapshotEntity::getBucketAt)
                .containsExactly(t2, t1, t0);  // newest first
        assertThat(got).allSatisfy(s ->
                assertThat(s.getCanaryId()).isEqualTo(parent.getId()));
    }

    @Test
    @DisplayName("deleting a t_canary_rollout cascades to t_canary_metric_snapshot via FK ON DELETE CASCADE")
    void canaryRollout_deleteCascadesToSnapshots() {
        CanaryRolloutEntity parent = canaryRepository.save(
                canary(42L, "my-skill", "my-skill-v2",
                        CanaryRolloutEntity.STAGE_CANARY, 10));
        Long parentId = parent.getId();
        snapshotRepository.save(snapshot(parentId, Instant.parse("2026-05-14T10:00:00Z")));
        snapshotRepository.save(snapshot(parentId, Instant.parse("2026-05-14T11:00:00Z")));
        snapshotRepository.flush();

        // Use a native DELETE so we exercise the FK contract directly (the
        // JPA cascade mapping is intentionally NOT configured — the FK is
        // the contract we're verifying, V74 pattern).
        entityManager.createNativeQuery("DELETE FROM t_canary_rollout WHERE id = :id")
                .setParameter("id", parentId)
                .executeUpdate();
        entityManager.clear();

        assertThat(canaryRepository.findById(parentId)).isEmpty();
        assertThat(snapshotRepository.findByCanaryIdOrderByBucketAtDesc(
                parentId, PageRequest.of(0, 24))).isEmpty();
    }
}
