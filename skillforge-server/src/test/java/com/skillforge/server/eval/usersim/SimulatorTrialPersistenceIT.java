package com.skillforge.server.eval.usersim;

import com.skillforge.server.AbstractPostgresIT;
import com.skillforge.server.entity.SimulatorTrialEntity;
import com.skillforge.server.repository.SimulatorTrialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V5 EVAL-DYNAMIC-USER-SIM Phase 1.2 — JPA persistence integration tests for
 * {@link SimulatorTrialEntity} + {@link SimulatorTrialRepository}.
 *
 * <p>Runs against the shared PostgreSQL container with V85 Flyway migration
 * applied (creates {@code t_simulator_trial} + 3 indexes).
 *
 * <p>Covers:
 * <ol>
 *   <li>Round-trip {@code save} + {@code findById} preserves all 10 fields.</li>
 *   <li>{@code findByScenarioId} narrows correctly.</li>
 *   <li>{@code findByCandidateAgentVersionIdAndCandidateSurfaceType} works.</li>
 *   <li>{@code findBySessionId} works.</li>
 *   <li>Paginated overload returns Page metadata.</li>
 * </ol>
 */
@DisplayName("V5 SimulatorTrial persistence IT")
class SimulatorTrialPersistenceIT extends AbstractPostgresIT {

    @Autowired
    private SimulatorTrialRepository trialRepository;

    @BeforeEach
    void cleanUp() {
        trialRepository.deleteAll();
    }

    @Test
    @DisplayName("save + findById round-trips all 10 fields including createdAt auditing")
    void saveAndFindById_roundtripsAllFields() {
        SimulatorTrialEntity t = newTrial("scen-1", "ver-A", "prompt",
                "销售经理急性子 — 短句", "sess-1", 7, "task_completed",
                "用户开始追问细节，agent 给出结论");
        SimulatorTrialEntity saved = trialRepository.save(t);

        assertThat(saved.getCreatedAt()).isNotNull();   // auditing populated

        SimulatorTrialEntity found = trialRepository.findById(saved.getTrialId()).orElseThrow();
        assertThat(found.getScenarioId()).isEqualTo("scen-1");
        assertThat(found.getCandidateAgentVersionId()).isEqualTo("ver-A");
        assertThat(found.getCandidateSurfaceType()).isEqualTo("prompt");
        assertThat(found.getPersona()).startsWith("销售经理急性子");
        assertThat(found.getSessionId()).isEqualTo("sess-1");
        assertThat(found.getTurnsUsed()).isEqualTo(7);
        assertThat(found.getTerminationReason()).isEqualTo("task_completed");
        assertThat(found.getObservedFailureSignals()).isEqualTo("用户开始追问细节，agent 给出结论");
    }

    @Test
    @DisplayName("findByScenarioId returns only rows for that scenario")
    void findByScenarioId_narrows() {
        trialRepository.save(newTrial("scen-A", null, null, "p1", "s1", 0, null, null));
        trialRepository.save(newTrial("scen-A", "v1", "prompt", "p2", "s2", 3, "max_turns", null));
        trialRepository.save(newTrial("scen-B", null, null, "p3", "s3", 0, null, null));

        List<SimulatorTrialEntity> rowsA = trialRepository.findByScenarioId("scen-A");
        List<SimulatorTrialEntity> rowsB = trialRepository.findByScenarioId("scen-B");

        assertThat(rowsA).hasSize(2);
        assertThat(rowsB).hasSize(1);
    }

    @Test
    @DisplayName("findByCandidateAgentVersionIdAndCandidateSurfaceType narrows on the V85 composite index")
    void findByCandidateAndSurface_narrows() {
        trialRepository.save(newTrial("s1", "v1", "prompt", "p", "ses1", 0, null, null));
        trialRepository.save(newTrial("s1", "v1", "skill", "p", "ses2", 0, null, null));
        trialRepository.save(newTrial("s1", "v2", "prompt", "p", "ses3", 0, null, null));

        List<SimulatorTrialEntity> v1Prompt = trialRepository
                .findByCandidateAgentVersionIdAndCandidateSurfaceType("v1", "prompt");
        assertThat(v1Prompt).hasSize(1);
        assertThat(v1Prompt.get(0).getSessionId()).isEqualTo("ses1");
    }

    @Test
    @DisplayName("findBySessionId picks the trial linked to a session")
    void findBySessionId_returnsLinkedTrial() {
        SimulatorTrialEntity saved = trialRepository.save(
                newTrial("scen-X", null, null, "p", "sess-unique", 0, null, null));

        List<SimulatorTrialEntity> rows = trialRepository.findBySessionId("sess-unique");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getTrialId()).isEqualTo(saved.getTrialId());
    }

    @Test
    @DisplayName("paginated findByScenarioId returns Page metadata for the FE list endpoint (Phase 1.3)")
    void findByScenarioId_pageable_pagesCorrectly() {
        for (int i = 0; i < 5; i++) {
            trialRepository.save(newTrial("scen-paged", null, null,
                    "persona-" + i, "sess-" + i, i, null, null));
        }

        var page0 = trialRepository.findByScenarioId("scen-paged", PageRequest.of(0, 2));
        var page1 = trialRepository.findByScenarioId("scen-paged", PageRequest.of(1, 2));

        assertThat(page0.getTotalElements()).isEqualTo(5);
        assertThat(page0.getContent()).hasSize(2);
        assertThat(page1.getContent()).hasSize(2);
        assertThat(page0.getTotalPages()).isEqualTo(3);
    }

    private SimulatorTrialEntity newTrial(String scenarioId, String candidateVersionId,
                                           String surfaceType, String persona, String sessionId,
                                           int turnsUsed, String terminationReason,
                                           String observedSignals) {
        SimulatorTrialEntity t = new SimulatorTrialEntity();
        t.setTrialId(UUID.randomUUID().toString());
        t.setScenarioId(scenarioId);
        t.setCandidateAgentVersionId(candidateVersionId);
        t.setCandidateSurfaceType(surfaceType);
        t.setPersona(persona);
        t.setSessionId(sessionId);
        t.setTurnsUsed(turnsUsed);
        t.setTerminationReason(terminationReason);
        t.setObservedFailureSignals(observedSignals);
        return t;
    }
}
