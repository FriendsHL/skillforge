package com.skillforge.server.flywheel.run;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * OPT-LOOP-FRAMEWORK Sprint 1: JPA access for {@link FlywheelRunStepEntity}.
 *
 * <p>Sprint 2 (W-2 fix): adds two query methods consumed by the
 * {@code OrchestratorAgentExecutor} framework path —
 * {@link #findBySubAgentSessionIdAndStatus} for the
 * {@code SessionLoopFinishedEvent} fallback listener, and
 * {@link #findByRunIdOrderByCreatedAtAsc} for dashboard / IT step lookup.
 */
public interface FlywheelRunStepRepository extends JpaRepository<FlywheelRunStepEntity, String> {

    /** Replaces the OPT-REPORT-V1 {@code findByReportId} — column renamed to {@code run_id}. */
    List<FlywheelRunStepEntity> findByRunId(String runId);

    /**
     * Sprint 2 (W-2 fix): fallback {@link com.skillforge.server.flywheel.orchestrator.WorkerCompletionListener}
     * path looks up a pending step by the worker's session id. Used when a
     * worker SubAgent fails to call {@code RecordOrchestrationStepResult}
     * before its loop terminates — the {@code SessionLoopFinishedEvent}
     * listener uses this to find the matching pending step and force-complete
     * it with the {@code finalMessage} as the synthesized output.
     *
     * <p>{@code Optional} — the listener can have nothing to do if the worker
     * already called the Tool (status moved off {@code pending}) or if the
     * finished session was never spawned by the framework.
     */
    Optional<FlywheelRunStepEntity> findBySubAgentSessionIdAndStatus(
            String subAgentSessionId, String status);

    /**
     * Sprint 2 (W-2 fix): chronological step list for a given parent run.
     * Used by both
     * {@link com.skillforge.server.flywheel.orchestrator.OrchestratorAgentExecutor}
     * dashboard surfaces and {@code FlywheelRunService.listStepsByRunId}
     * (with {@code @Transactional(readOnly=true)}; consumes Sprint 1 W3
     * findById-readOnly backlog).
     */
    List<FlywheelRunStepEntity> findByRunIdOrderByCreatedAtAsc(String runId);
}
