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
 * OPT-LOOP-FRAMEWORK Sprint 1 (V124, 2026-05-28): row per flywheel orchestrator
 * run. Generalises the OPT-REPORT-V1 {@code OptReportEntity} (V97) so the same
 * table records every loop kind (memory_curation / attribution / metrics_collection
 * / custom), not just opt_report.
 *
 * <p>Lifecycle (unchanged from OPT-REPORT-V1):
 * <pre>
 *   pending → running → completed
 *                    └→ error
 * </pre>
 *
 * <p>3 new columns vs the V97 schema (see V124 migration):
 * <ul>
 *   <li>{@code trigger_source}: who fired the run
 *       (cron / user_manual / api / event)</li>
 *   <li>{@code input_json}: free-schema JSONB carrying the loop-specific
 *       kickoff params. OPT-REPORT historical rows backfilled to
 *       {@code {agentId, windowDays, windowStart, windowEnd}}.</li>
 *   <li>{@code loop_kind}: which orchestrator owns the row (opt_report /
 *       memory_curation / attribution / metrics_collection / custom)</li>
 * </ul>
 *
 * <p>OPT-REPORT-V1 back-compat surface ({@code OptReportService.startReport} +
 * {@code OptReportController} REST endpoints + {@code opt_report_completed} WS
 * event) is preserved on top of this entity — see
 * {@code OptReportService} javadoc.
 */
@Entity
@Table(name = "t_flywheel_run")
@EntityListeners(AuditingEntityListener.class)
public class FlywheelRunEntity {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_ERROR = "error";

    public static final String LOOP_KIND_OPT_REPORT = "opt_report";
    public static final String LOOP_KIND_MEMORY_CURATION = "memory_curation";
    public static final String LOOP_KIND_ATTRIBUTION = "attribution";
    public static final String LOOP_KIND_METRICS_COLLECTION = "metrics_collection";
    public static final String LOOP_KIND_CUSTOM = "custom";

    public static final String TRIGGER_SOURCE_CRON = "cron";
    public static final String TRIGGER_SOURCE_USER_MANUAL = "user_manual";
    public static final String TRIGGER_SOURCE_API = "api";
    public static final String TRIGGER_SOURCE_EVENT = "event";

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
     * Stored as JSONB in Postgres; mapped to {@code String} on the Java side to
     * keep the entity dependency-light (matches the OPT-REPORT-V1 convention).
     * Callers serialize / deserialize at the service boundary.
     */
    @Column(name = "summary_json", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String summaryJson;

    @Column(name = "error_reason", columnDefinition = "TEXT")
    private String errorReason;

    @Column(name = "generator_session_id", length = 36)
    private String generatorSessionId;

    /**
     * V124 new column. One of the {@code TRIGGER_SOURCE_*} constants.
     * Defaults to {@code user_manual} for OPT-REPORT-V1 historical rows.
     */
    @Column(name = "trigger_source", nullable = false, length = 32)
    private String triggerSource = TRIGGER_SOURCE_USER_MANUAL;

    /**
     * V124 new column. JSONB free-schema carrying loop-kind-specific kickoff
     * params (no Java-side schema enforced — each {@code loop_kind} defines
     * its own keys). OPT-REPORT-V1 rows backfilled to
     * {@code {agentId, windowDays, windowStart, windowEnd}}.
     */
    @Column(name = "input_json", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String inputJson = "{}";

    /**
     * V124 new column. One of the {@code LOOP_KIND_*} constants. Defaults to
     * {@code opt_report} for OPT-REPORT-V1 historical rows.
     */
    @Column(name = "loop_kind", nullable = false, length = 32)
    private String loopKind = LOOP_KIND_OPT_REPORT;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public FlywheelRunEntity() {}

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
    public void setGeneratorSessionId(String generatorSessionId) {
        this.generatorSessionId = generatorSessionId;
    }

    public String getTriggerSource() { return triggerSource; }
    public void setTriggerSource(String triggerSource) { this.triggerSource = triggerSource; }

    public String getInputJson() { return inputJson; }
    public void setInputJson(String inputJson) { this.inputJson = inputJson; }

    public String getLoopKind() { return loopKind; }
    public void setLoopKind(String loopKind) { this.loopKind = loopKind; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
