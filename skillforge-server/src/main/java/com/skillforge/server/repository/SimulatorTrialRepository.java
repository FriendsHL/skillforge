package com.skillforge.server.repository;

import com.skillforge.server.entity.SimulatorTrialEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * V5 EVAL-DYNAMIC-USER-SIM Phase 1.2 (V85): repository for {@link SimulatorTrialEntity}.
 *
 * <p>Provides scenario-scoped + candidate-scoped + session-scoped lookups
 * matching the 3 V85 indexes. Phase 1.3 {@code DynamicSimController} consumes
 * the {@code Page<>} overload for paginated list endpoint.
 */
@Repository
public interface SimulatorTrialRepository extends JpaRepository<SimulatorTrialEntity, String> {

    List<SimulatorTrialEntity> findByScenarioId(String scenarioId);

    List<SimulatorTrialEntity> findByCandidateAgentVersionIdAndCandidateSurfaceType(
            String candidateAgentVersionId, String candidateSurfaceType);

    List<SimulatorTrialEntity> findBySessionId(String sessionId);

    Page<SimulatorTrialEntity> findByScenarioId(String scenarioId, Pageable pageable);

    Page<SimulatorTrialEntity> findByCandidateAgentVersionIdAndCandidateSurfaceType(
            String candidateAgentVersionId, String candidateSurfaceType, Pageable pageable);

    /**
     * SYSTEM-AGENT-TYPING Phase 2.1: 7-day output count for the user-simulator
     * monitor card. The table is natively scoped to user-simulator (no other
     * cron writes here). Each row is one simulated trial run — labelled
     * "trials" in the FE card.
     */
    long countByCreatedAtAfter(Instant since);
}
