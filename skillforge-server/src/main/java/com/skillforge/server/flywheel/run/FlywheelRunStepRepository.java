package com.skillforge.server.flywheel.run;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * OPT-LOOP-FRAMEWORK Sprint 1: JPA access for {@link FlywheelRunStepEntity}.
 */
public interface FlywheelRunStepRepository extends JpaRepository<FlywheelRunStepEntity, String> {

    /** Replaces the OPT-REPORT-V1 {@code findByReportId} — column renamed to {@code run_id}. */
    List<FlywheelRunStepEntity> findByRunId(String runId);
}
