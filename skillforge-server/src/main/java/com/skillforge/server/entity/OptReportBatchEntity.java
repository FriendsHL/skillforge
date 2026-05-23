package com.skillforge.server.entity;

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
 * OPT-REPORT-V1 (V97): per-SubAgent fan-out tracking row.
 *
 * <p>For each batch the report-generator dispatches to a
 * {@code session-batch-annotator} sub session, one row exists here so the
 * parent report has audit visibility on whether the batch succeeded, how
 * many annotation rows it actually wrote, and any error reason.
 *
 * <p>Lifecycle:
 * <pre>
 *   pending → completed
 *          └→ error
 * </pre>
 *
 * <p>The row is created up-front (by the report-generator or service layer)
 * with {@code status='pending'}; the SubAgent calls
 * {@link com.skillforge.server.tool.optreport.RecordBatchAnnotationsTool}
 * once it finishes (or errors out) to update the row.
 *
 * <p>{@code sessionIdsJson} is the JSON-encoded list of session ids the
 * batch is responsible for (kept here purely for audit/forensics — the
 * parent report can be reconstructed from session_annotation rows alone).
 *
 * <p>Stored as JSONB in Postgres but mapped to {@code String} on the Java
 * side to keep entity dependencies light; callers serialize/deserialize at
 * the service boundary.
 */
@Entity
@Table(name = "t_opt_report_batch")
@EntityListeners(AuditingEntityListener.class)
public class OptReportBatchEntity {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_ERROR = "error";

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "report_id", nullable = false, length = 36)
    private String reportId;

    @Column(name = "sub_agent_session_id", length = 36)
    private String subAgentSessionId;

    @Column(name = "session_ids_json", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String sessionIdsJson;

    @Column(nullable = false, length = 16)
    private String status = STATUS_PENDING;

    @Column(name = "annotations_written_count")
    private Integer annotationsWrittenCount;

    @Column(name = "error_reason", columnDefinition = "TEXT")
    private String errorReason;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public OptReportBatchEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getReportId() { return reportId; }
    public void setReportId(String reportId) { this.reportId = reportId; }

    public String getSubAgentSessionId() { return subAgentSessionId; }
    public void setSubAgentSessionId(String subAgentSessionId) { this.subAgentSessionId = subAgentSessionId; }

    public String getSessionIdsJson() { return sessionIdsJson; }
    public void setSessionIdsJson(String sessionIdsJson) { this.sessionIdsJson = sessionIdsJson; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getAnnotationsWrittenCount() { return annotationsWrittenCount; }
    public void setAnnotationsWrittenCount(Integer annotationsWrittenCount) {
        this.annotationsWrittenCount = annotationsWrittenCount;
    }

    public String getErrorReason() { return errorReason; }
    public void setErrorReason(String errorReason) { this.errorReason = errorReason; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
