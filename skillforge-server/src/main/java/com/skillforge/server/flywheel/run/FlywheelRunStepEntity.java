package com.skillforge.server.flywheel.run;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * OPT-LOOP-FRAMEWORK Sprint 1 (V124, 2026-05-28): per-step fan-out tracking row
 * for a parent {@link FlywheelRunEntity}. Generalises the OPT-REPORT-V1
 * {@code OptReportBatchEntity} (V97) for arbitrary orchestrator step kinds.
 *
 * <p>Lifecycle (unchanged from OPT-REPORT-V1):
 * <pre>
 *   pending → completed
 *          └→ error
 * </pre>
 *
 * <p>Schema changes vs V97:
 * <ul>
 *   <li>Rename column {@code report_id} → {@code run_id} (now points at
 *       generic {@code t_flywheel_run} rows, not just opt_report).</li>
 *   <li>Rename column {@code session_ids_json} → {@code step_input_json}
 *       (still carries the SubAgent kickoff sessionIds for OPT-REPORT;
 *       free-form payload for future step_kinds).</li>
 *   <li>Rename column {@code annotations_written_count} → {@code step_output_count}
 *       (generic positive-int counter; OPT-REPORT keeps populating it with
 *       annotation row count).</li>
 *   <li>New column {@code step_kind}: V1 only value is
 *       {@code subagent_dispatch} (matches the OPT-REPORT batch semantic).</li>
 * </ul>
 */
@Entity
@Table(name = "t_flywheel_run_step")
@EntityListeners(AuditingEntityListener.class)
public class FlywheelRunStepEntity {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_ERROR = "error";

    public static final String STEP_KIND_SUBAGENT_DISPATCH = "subagent_dispatch";

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "run_id", nullable = false, length = 36)
    private String runId;

    @Column(name = "sub_agent_session_id", length = 36)
    private String subAgentSessionId;

    @Column(name = "step_input_json", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String stepInputJson;

    @Column(nullable = false, length = 16)
    private String status = STATUS_PENDING;

    @Column(name = "step_output_count")
    private Integer stepOutputCount;

    @Column(name = "error_reason", columnDefinition = "TEXT")
    private String errorReason;

    /**
     * V124 new column. Identifies what kind of step this row represents.
     * Sprint 1 only value is {@code subagent_dispatch}; future step kinds
     * (e.g. {@code llm_attribution} / {@code db_persist}) added as needed.
     */
    @Column(name = "step_kind", nullable = false, length = 32)
    private String stepKind = STEP_KIND_SUBAGENT_DISPATCH;

    /**
     * V125 new column (OPT-LOOP-FRAMEWORK Sprint 2): free-schema JSONB
     * populated by {@code RecordOrchestrationStepResult} Tool with the worker
     * SubAgent's structured output. OPT-REPORT-V1 historical rows + the
     * {@code RecordBatchAnnotationsTool} path keep this {@code null} — they
     * still use {@code step_output_count}. nullable on purpose so the V125
     * ADD COLUMN didn't need a backfill on the 13 pre-existing OPT-REPORT
     * step rows.
     */
    @Column(name = "step_output_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String stepOutputJson;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public FlywheelRunStepEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }

    public String getSubAgentSessionId() { return subAgentSessionId; }
    public void setSubAgentSessionId(String subAgentSessionId) {
        this.subAgentSessionId = subAgentSessionId;
    }

    public String getStepInputJson() { return stepInputJson; }
    public void setStepInputJson(String stepInputJson) { this.stepInputJson = stepInputJson; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getStepOutputCount() { return stepOutputCount; }
    public void setStepOutputCount(Integer stepOutputCount) {
        this.stepOutputCount = stepOutputCount;
    }

    public String getErrorReason() { return errorReason; }
    public void setErrorReason(String errorReason) { this.errorReason = errorReason; }

    public String getStepKind() { return stepKind; }
    public void setStepKind(String stepKind) { this.stepKind = stepKind; }

    public String getStepOutputJson() { return stepOutputJson; }
    public void setStepOutputJson(String stepOutputJson) { this.stepOutputJson = stepOutputJson; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
