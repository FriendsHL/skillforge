package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * V5 EVAL-DYNAMIC-USER-SIM Phase 1.2 (V85): per-trial elaborated outcome
 * metadata. Each row is one (scenario × candidate × persona) trial run by the
 * UserSimulatorAgent — captures session linkage + termination diagnostics for
 * dashboard surfacing.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@code SimulatorTrialOrchestrator.runTrial} inserts a PENDING row
 *       (turns_used=0, termination_reason=null) before starting the ping-pong
 *       loop.</li>
 *   <li>UserSim agent may call {@code RecordSimulationResult} tool mid-loop
 *       when it decides to terminate — that path updates the row.</li>
 *   <li>Orchestrator at outer-loop close updates the final
 *       {@code turns_used} + {@code termination_reason} regardless (idempotent
 *       overwrite if UserSim already set them).</li>
 * </ol>
 */
@Entity
@Table(name = "t_simulator_trial")
@EntityListeners(AuditingEntityListener.class)
public class SimulatorTrialEntity {

    @Id
    @Column(name = "trial_id", length = 36)
    private String trialId;

    @Column(name = "scenario_id", nullable = false, length = 36)
    private String scenarioId;

    @Column(name = "candidate_agent_version_id", length = 64)
    private String candidateAgentVersionId;

    @Column(name = "candidate_surface_type", length = 32)
    private String candidateSurfaceType;

    @Column(name = "persona", nullable = false, columnDefinition = "TEXT")
    private String persona;

    @Column(name = "session_id", nullable = false, length = 36)
    private String sessionId;

    @Column(name = "turns_used", nullable = false)
    private Integer turnsUsed = 0;

    @Column(name = "termination_reason", length = 64)
    private String terminationReason;

    @Column(name = "observed_failure_signals", columnDefinition = "TEXT")
    private String observedFailureSignals;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public SimulatorTrialEntity() {
    }

    public String getTrialId() { return trialId; }
    public void setTrialId(String trialId) { this.trialId = trialId; }

    public String getScenarioId() { return scenarioId; }
    public void setScenarioId(String scenarioId) { this.scenarioId = scenarioId; }

    public String getCandidateAgentVersionId() { return candidateAgentVersionId; }
    public void setCandidateAgentVersionId(String candidateAgentVersionId) {
        this.candidateAgentVersionId = candidateAgentVersionId;
    }

    public String getCandidateSurfaceType() { return candidateSurfaceType; }
    public void setCandidateSurfaceType(String candidateSurfaceType) {
        this.candidateSurfaceType = candidateSurfaceType;
    }

    public String getPersona() { return persona; }
    public void setPersona(String persona) { this.persona = persona; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public Integer getTurnsUsed() { return turnsUsed; }
    public void setTurnsUsed(Integer turnsUsed) { this.turnsUsed = turnsUsed; }

    public String getTerminationReason() { return terminationReason; }
    public void setTerminationReason(String terminationReason) { this.terminationReason = terminationReason; }

    public String getObservedFailureSignals() { return observedFailureSignals; }
    public void setObservedFailureSignals(String observedFailureSignals) {
        this.observedFailureSignals = observedFailureSignals;
    }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
