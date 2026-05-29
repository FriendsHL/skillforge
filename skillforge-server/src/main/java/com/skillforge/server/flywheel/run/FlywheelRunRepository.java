package com.skillforge.server.flywheel.run;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * OPT-LOOP-FRAMEWORK Sprint 1: JPA access for {@link FlywheelRunEntity}.
 *
 * <p>The convenience query {@link #findByAgentIdAndLoopKindOrderByCreatedAtDesc}
 * preserves the OPT-REPORT-V1 "reports for an agent newest-first" lookup —
 * callers still need it scoped by {@code loop_kind} because the table now
 * mixes runs from all orchestrators.
 */
public interface FlywheelRunRepository extends JpaRepository<FlywheelRunEntity, String> {

    /**
     * Per-agent newest-first lookup scoped by a {@code loop_kind}. The OPT-REPORT
     * controller passes {@code loop_kind='opt_report'} to mimic the V97 query
     * shape exactly; future "All Flywheel Runs" callers can pass any of the
     * {@code FlywheelRunEntity.LOOP_KIND_*} constants.
     */
    @Query("""
            SELECT r FROM FlywheelRunEntity r
            WHERE r.agentId = :agentId
              AND r.loopKind = :loopKind
            ORDER BY r.createdAt DESC, r.id DESC
            """)
    List<FlywheelRunEntity> findByAgentIdAndLoopKindOrderByCreatedAtDesc(
            @Param("agentId") Long agentId,
            @Param("loopKind") String loopKind,
            Pageable pageable);

    /**
     * AUTOEVOLVING V1 Sprint 2 (Task G): the "All Workflow Runs" listing —
     * newest-first across every workflow run regardless of agent. {@code id DESC}
     * is the stable tie-breaker (matches the OPT-REPORT query shape).
     */
    List<FlywheelRunEntity> findByLoopKindOrderByCreatedAtDescIdDesc(
            String loopKind, Pageable pageable);

    /** Status-filtered variant of {@link #findByLoopKindOrderByCreatedAtDescIdDesc}. */
    List<FlywheelRunEntity> findByLoopKindAndStatusOrderByCreatedAtDescIdDesc(
            String loopKind, String status, Pageable pageable);

    long countByLoopKind(String loopKind);

    long countByLoopKindAndStatus(String loopKind, String status);
}
