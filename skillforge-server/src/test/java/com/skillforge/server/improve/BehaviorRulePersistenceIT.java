package com.skillforge.server.improve;

import com.skillforge.server.AbstractPostgresIT;
import com.skillforge.server.entity.BehaviorRuleAbRunEntity;
import com.skillforge.server.entity.BehaviorRuleVersionEntity;
import com.skillforge.server.repository.BehaviorRuleAbRunRepository;
import com.skillforge.server.repository.BehaviorRuleVersionRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MULTI-SURFACE-FLYWHEEL V4 Phase 1.1 — persistence IT for V82's two new
 * tables. Mirrors {@code CanaryPersistenceIT}: shared Testcontainers PG +
 * Flyway-applied schema, no schema mocking. Test isolation comes from
 * {@code @DataJpaTest}'s default rollback.
 *
 * <p>Covers (per tech-design §7.3 Phase 1.1 test list):
 * <ol>
 *   <li>{@code t_behavior_rule_version} round-trip ({@code save} →
 *       {@code findByAgentIdAndStatus}).</li>
 *   <li>{@code t_behavior_rule_ab_run} round-trip.</li>
 *   <li>Partial UNIQUE {@code uq_brv_one_active} rejects a second
 *       {@code status='active'} row for the same agent; allows
 *       retired / candidate / rejected to coexist.</li>
 *   <li>Status transitions (candidate → active → retired) preserve
 *       partial UNIQUE invariant when done in the correct order.</li>
 * </ol>
 */
@DisplayName("MULTI-SURFACE-FLYWHEEL V4 behavior_rule persistence IT")
class BehaviorRulePersistenceIT extends AbstractPostgresIT {

    @Autowired
    private BehaviorRuleVersionRepository versionRepository;

    @Autowired
    private BehaviorRuleAbRunRepository abRunRepository;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void cleanUp() {
        abRunRepository.deleteAll();
        versionRepository.deleteAll();
    }

    private BehaviorRuleVersionEntity version(String agentId, int versionNumber, String status, String rulesJson) {
        BehaviorRuleVersionEntity v = new BehaviorRuleVersionEntity();
        v.setId(UUID.randomUUID().toString());
        v.setAgentId(agentId);
        v.setVersionNumber(versionNumber);
        v.setStatus(status);
        v.setRulesJson(rulesJson);
        v.setSource(BehaviorRuleVersionEntity.SOURCE_ATTRIBUTION);
        v.setImprovementRationale("test rationale " + versionNumber);
        v.setSourceEventId(42L);
        return v;
    }

    private BehaviorRuleAbRunEntity abRun(String agentId, String baselineVersionId, String candidateVersionId) {
        BehaviorRuleAbRunEntity r = new BehaviorRuleAbRunEntity();
        r.setId(UUID.randomUUID().toString());
        r.setAgentId(agentId);
        r.setBaselineVersionId(baselineVersionId);
        r.setCandidateVersionId(candidateVersionId);
        r.setBaselineEvalRunId("eval-anchor-1");
        r.setStatus(BehaviorRuleAbRunEntity.STATUS_RUNNING);
        r.setBaselinePassRate(40.0);
        r.setCandidatePassRate(58.0);
        r.setDeltaPassRate(18.0);
        r.setTriggeredByUserId(7L);
        return r;
    }

    @Test
    @DisplayName("t_behavior_rule_version saves round-trip with all columns + createdAt auto-stamp")
    void version_saveRoundTrip() {
        BehaviorRuleVersionEntity saved = versionRepository.save(
                version("99", 1, BehaviorRuleVersionEntity.STATUS_ACTIVE,
                        "[{\"id\":\"r1\",\"priority\":1,\"when\":\"x\",\"then\":\"y\",\"rationale\":\"z\"}]"));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();  // @PrePersist stamps when caller doesn't.

        Optional<BehaviorRuleVersionEntity> reloaded = versionRepository.findById(saved.getId());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getAgentId()).isEqualTo("99");
        assertThat(reloaded.get().getVersionNumber()).isEqualTo(1);
        assertThat(reloaded.get().getStatus()).isEqualTo(BehaviorRuleVersionEntity.STATUS_ACTIVE);
        assertThat(reloaded.get().getRulesJson()).contains("\"id\":\"r1\"");
        assertThat(reloaded.get().getSource()).isEqualTo(BehaviorRuleVersionEntity.SOURCE_ATTRIBUTION);
        assertThat(reloaded.get().getSourceEventId()).isEqualTo(42L);

        // findByAgentIdAndStatus is the surface's primary lookup path.
        Optional<BehaviorRuleVersionEntity> activeForAgent =
                versionRepository.findByAgentIdAndStatus("99", BehaviorRuleVersionEntity.STATUS_ACTIVE);
        assertThat(activeForAgent).isPresent();
        assertThat(activeForAgent.get().getId()).isEqualTo(saved.getId());

        // findMaxVersionNumber for next-version allocation.
        assertThat(versionRepository.findMaxVersionNumber("99")).contains(1);
        assertThat(versionRepository.findMaxVersionNumber("no-such-agent")).isEmpty();
    }

    @Test
    @DisplayName("t_behavior_rule_ab_run saves round-trip with all columns + startedAt auto-stamp")
    void abRun_saveRoundTrip() {
        BehaviorRuleVersionEntity baseline = versionRepository.save(
                version("88", 1, BehaviorRuleVersionEntity.STATUS_ACTIVE, "[]"));
        BehaviorRuleVersionEntity candidate = versionRepository.save(
                version("88", 2, BehaviorRuleVersionEntity.STATUS_CANDIDATE, "[]"));

        BehaviorRuleAbRunEntity saved = abRunRepository.save(
                abRun("88", baseline.getId(), candidate.getId()));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getStartedAt()).isNotNull();  // @PrePersist stamps when caller doesn't.

        Optional<BehaviorRuleAbRunEntity> reloaded = abRunRepository.findById(saved.getId());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getAgentId()).isEqualTo("88");
        assertThat(reloaded.get().getBaselineVersionId()).isEqualTo(baseline.getId());
        assertThat(reloaded.get().getCandidateVersionId()).isEqualTo(candidate.getId());
        assertThat(reloaded.get().getStatus()).isEqualTo(BehaviorRuleAbRunEntity.STATUS_RUNNING);
        assertThat(reloaded.get().getBaselinePassRate()).isEqualTo(40.0);
        assertThat(reloaded.get().getCandidatePassRate()).isEqualTo(58.0);
        assertThat(reloaded.get().getDeltaPassRate()).isEqualTo(18.0);
        assertThat(reloaded.get().isPromoted()).isFalse();

        // Index lookup paths used by Phase 1.2 callers.
        List<BehaviorRuleAbRunEntity> byStatus = abRunRepository.findByAgentIdAndStatus(
                "88", BehaviorRuleAbRunEntity.STATUS_RUNNING);
        assertThat(byStatus).hasSize(1);

        List<BehaviorRuleAbRunEntity> byCandidate = abRunRepository
                .findByCandidateVersionIdOrderByStartedAtDesc(candidate.getId());
        assertThat(byCandidate).hasSize(1);
    }

    @Test
    @DisplayName("uq_brv_one_active partial UNIQUE rejects 2 active rows for same agent; allows other statuses")
    void version_partialUniqueOneActivePerAgent() {
        BehaviorRuleVersionEntity active1 = versionRepository.save(
                version("agentA", 1, BehaviorRuleVersionEntity.STATUS_ACTIVE, "[]"));
        entityManager.flush();
        assertThat(active1.getId()).isNotNull();

        // Other statuses for the same agent must NOT trip the partial UNIQUE.
        versionRepository.save(version("agentA", 2, BehaviorRuleVersionEntity.STATUS_CANDIDATE, "[]"));
        versionRepository.save(version("agentA", 3, BehaviorRuleVersionEntity.STATUS_RETIRED, "[]"));
        versionRepository.save(version("agentA", 4, BehaviorRuleVersionEntity.STATUS_REJECTED, "[]"));
        entityManager.flush();

        // An active row for a different agent is also fine.
        versionRepository.save(version("agentB", 1, BehaviorRuleVersionEntity.STATUS_ACTIVE, "[]"));
        entityManager.flush();

        // A SECOND active row for agentA must violate uq_brv_one_active.
        versionRepository.save(version("agentA", 5, BehaviorRuleVersionEntity.STATUS_ACTIVE, "[]"));

        assertThatThrownBy(entityManager::flush)
                .isInstanceOfAny(DataIntegrityViolationException.class,
                                 jakarta.persistence.PersistenceException.class);
    }

    @Test
    @DisplayName("status transition candidate → active → retired preserves partial UNIQUE invariant")
    void version_statusTransitions_preserveInvariant() {
        // Start: one active baseline.
        BehaviorRuleVersionEntity baseline = versionRepository.save(
                version("agentC", 1, BehaviorRuleVersionEntity.STATUS_ACTIVE, "[]"));
        // A candidate row exists too.
        BehaviorRuleVersionEntity candidate = versionRepository.save(
                version("agentC", 2, BehaviorRuleVersionEntity.STATUS_CANDIDATE, "[]"));
        entityManager.flush();

        // Manual promote-style transition: retire baseline FIRST + flush, then
        // flip candidate to active. This is the exact pattern
        // BehaviorRulePromotionService.promote follows.
        baseline.setStatus(BehaviorRuleVersionEntity.STATUS_RETIRED);
        versionRepository.saveAndFlush(baseline);
        candidate.setStatus(BehaviorRuleVersionEntity.STATUS_ACTIVE);
        candidate.setPromotedAt(Instant.now());
        versionRepository.saveAndFlush(candidate);

        Optional<BehaviorRuleVersionEntity> nowActive = versionRepository
                .findByAgentIdAndStatus("agentC", BehaviorRuleVersionEntity.STATUS_ACTIVE);
        assertThat(nowActive).isPresent();
        assertThat(nowActive.get().getId()).isEqualTo(candidate.getId());
        assertThat(nowActive.get().getPromotedAt()).isNotNull();

        // Order check: retiring then activating must be safe — the DB never
        // had two active rows at the same time.
        List<BehaviorRuleVersionEntity> allForAgent =
                versionRepository.findByAgentIdOrderByVersionNumberDesc("agentC");
        assertThat(allForAgent).extracting(BehaviorRuleVersionEntity::getStatus)
                .containsExactlyInAnyOrder(
                        BehaviorRuleVersionEntity.STATUS_ACTIVE,
                        BehaviorRuleVersionEntity.STATUS_RETIRED);
    }
}
