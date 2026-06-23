package com.skillforge.server.flywheel.run;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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

    /**
     * AUTOEVOLVING V1 Sprint 2 (Task F — journal-replay): exact cache lookup by
     * the deterministic {@code step_index} (V127). The partial unique index
     * {@code ux_flywheel_run_step_run_idx} guarantees at most one row per
     * {@code (run_id, step_index)}; pairing with {@code step_kind} keeps the
     * {@code agent()} ({@code subagent_dispatch}) and {@code humanApprove()}
     * ({@code human_approve}) lookups distinct. {@code Optional} — a miss during
     * replay is a genuine bug (the step should have been written on the prior run).
     */
    Optional<FlywheelRunStepEntity> findByRunIdAndStepIndexAndStepKind(
            String runId, Integer stepIndex, String stepKind);

    /**
     * AUTOEVOLVING V1 Sprint 2 (Task F — resume): find the parked
     * {@code human_approve} gate step(s) for a run. A paused workflow run has
     * exactly one {@code pending} {@code human_approve} step (the frontier the
     * approve REST call resolves). Returned as a list so the resume path can
     * defensively reject the (impossible) multi-gate case.
     */
    List<FlywheelRunStepEntity> findByRunIdAndStepKindAndStatus(
            String runId, String stepKind, String status);

    /**
     * AUTOEVOLVE-AGENT-FLYWHEEL Module C (FR-C7): per-evolve-run A/B budget
     * counter. Counts {@code evolve_iteration} steps for a run — each recorded
     * iteration corresponds to one A/B trigger, so this is the run's A/B count
     * used to enforce the per-evolve-run cap before firing another
     * {@code TriggerAbEval}. (Soft cap: counts iterations the orchestrator has
     * already recorded via {@code RecordIteration}; documented design — see
     * {@code TriggerAbEvalTool} FR-C7 javadoc.)
     */
    long countByRunIdAndStepKind(String runId, String stepKind);

    /**
     * AUTOEVOLVE-AGENT-FLYWHEEL Module D (FR-D1/FR-D4): all steps of a given
     * {@code step_kind} for a run. Used by {@code EvolveReadService} to fetch
     * all {@code evolve_iteration} steps for a run when computing
     * {@code iterationCount} and {@code finalDelta} in the list endpoint.
     */
    List<FlywheelRunStepEntity> findByRunIdAndStepKind(String runId, String stepKind);

    /**
     * AUTOEVOLVE-AGENT-FLYWHEEL Module D (FR-D3): all {@code evolve_iteration}
     * steps for a run, ordered by {@code step_index} ascending (= iteration
     * number). {@code step_index} is the 1-based iteration counter written by
     * {@code appendEvolveIterationStep}; ordering by it yields the chronological
     * iteration trajectory for the detail endpoint.
     */
    List<FlywheelRunStepEntity> findByRunIdAndStepKindOrderByStepIndexAsc(
            String runId, String stepKind);

    /**
     * AUTOEVOLVE-AGENT-FLYWHEEL Module C (FR-C7 CRIT-1 fix; FR-C7 rolling-window
     * fix 2026-06-24): count {@code evolve_iteration} steps across ALL evolve runs
     * for a given {@code agentId} whose step was created within the budget window
     * ({@code s.createdAt >= :since}). Used as the per-agent A/B budget counter in
     * {@link FlywheelRunService#countEvolveAbTriggersForAgent(Long)} — the cap
     * fires on {@code targetAgentId} (always-required in TriggerAbEval) so an
     * LLM that omits {@code evolveRunId} cannot bypass it.
     *
     * <p><b>Why a rolling window instead of lifetime?</b> The original query
     * counted EVERY {@code evolve_iteration} step for the agent across all time,
     * so a repeatedly-evolved agent permanently accumulated toward the cap and
     * eventually froze (could never trigger another A/B again — agent 3 hit this).
     * Keying on {@code createdAt >= :since} resets the budget every window while
     * STILL counting per-agent: within one window an LLM cannot bypass the cap by
     * opening multiple evolve runs (the CRIT-1 cross-run guard is preserved — the
     * join still aggregates every run for the agent inside the window).
     *
     * <p>The JPQL join traverses:
     * {@code t_flywheel_run_step.run_id → t_flywheel_run.id (agentId = :agentId,
     * loopKind = 'evolve')}, filtered to {@code step.created_at >= :since}.
     */
    @Query("""
            SELECT COUNT(s)
            FROM FlywheelRunStepEntity s
            JOIN FlywheelRunEntity r ON s.runId = r.id
            WHERE r.agentId = :agentId
              AND r.loopKind = 'evolve'
              AND s.stepKind = 'evolve_iteration'
              AND s.createdAt >= :since
            """)
    long countEvolveIterationStepsByAgentIdSince(@Param("agentId") Long agentId,
                                                 @Param("since") Instant since);
}
