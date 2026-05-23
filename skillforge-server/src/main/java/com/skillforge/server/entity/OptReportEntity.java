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
 * OPT-REPORT-V1 (V97): one row per "Generate Report" click on a target agent.
 *
 * <p>Lifecycle:
 * <pre>
 *   pending → running → completed
 *                    └→ error
 * </pre>
 *
 * <p>{@link #status} starts as {@code pending} when the controller inserts
 * the row, transitions to {@code running} once the report-generator session
 * is launched, then to {@code completed} when the agent calls
 * {@code WriteOptReport}. Error path is reserved for explicit failure
 * surfacing (V1 does not auto-rollback running rows on timeout — Phase 2
 * follow-up).
 *
 * <p>{@link #generatorSessionId} is the session id of the spawned
 * report-generator agent run; observers can join on {@code t_session.id} to
 * walk the run's tool calls / messages for debugging.
 *
 * <p>{@code content_md} + {@code summary_json} are populated by
 * {@link com.skillforge.server.tool.optreport.WriteOptReportTool} once the
 * agent finishes its 7-step pipeline.
 */
@Entity
@Table(name = "t_opt_report")
@EntityListeners(AuditingEntityListener.class)
public class OptReportEntity {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_ERROR = "error";

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Column(name = "window_start", nullable = false)
    private Instant windowStart;

    @Column(name = "window_end", nullable = false)
    private Instant windowEnd;

    @Column(nullable = false, length = 16)
    private String status = STATUS_PENDING;

    @Column(name = "content_md", columnDefinition = "TEXT")
    private String contentMd;

    /**
     * Stored as JSONB in Postgres; we map to {@code String} on the Java side
     * to keep the entity dependency-light. Callers serialize / deserialize at
     * the service boundary.
     */
    @Column(name = "summary_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String summaryJson;

    @Column(name = "error_reason", columnDefinition = "TEXT")
    private String errorReason;

    @Column(name = "generator_session_id", length = 36)
    private String generatorSessionId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public OptReportEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Long getAgentId() { return agentId; }
    public void setAgentId(Long agentId) { this.agentId = agentId; }

    public Instant getWindowStart() { return windowStart; }
    public void setWindowStart(Instant windowStart) { this.windowStart = windowStart; }

    public Instant getWindowEnd() { return windowEnd; }
    public void setWindowEnd(Instant windowEnd) { this.windowEnd = windowEnd; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getContentMd() { return contentMd; }
    public void setContentMd(String contentMd) { this.contentMd = contentMd; }

    public String getSummaryJson() { return summaryJson; }
    public void setSummaryJson(String summaryJson) { this.summaryJson = summaryJson; }

    public String getErrorReason() { return errorReason; }
    public void setErrorReason(String errorReason) { this.errorReason = errorReason; }

    public String getGeneratorSessionId() { return generatorSessionId; }
    public void setGeneratorSessionId(String generatorSessionId) { this.generatorSessionId = generatorSessionId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
