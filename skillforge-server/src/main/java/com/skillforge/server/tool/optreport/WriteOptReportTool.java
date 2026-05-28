package com.skillforge.server.tool.optreport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.flywheel.run.FlywheelRunEntity;
import com.skillforge.server.flywheel.run.FlywheelRunRepository;
import com.skillforge.server.flywheel.run.FlywheelRunService;
import com.skillforge.server.optreport.OptReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * OPT-REPORT-V1 — STEP 7 of the {@code report-generator} agent pipeline.
 *
 * <p>OPT-LOOP-FRAMEWORK Sprint 1 (V124, 2026-05-28): persists the markdown
 * report + structured summary JSON via {@link FlywheelRunService#markCompleted}
 * (under the hood writes to {@code t_flywheel_run} but the public REST surface
 * still treats this row as an OPT-REPORT-V1 report). After the
 * Service-managed write fires the generic {@code flywheel_run_status_changed}
 * WS event, this tool also calls {@link OptReportService#onReportCompleted}
 * to emit the OPT-REPORT-specific {@code opt_report_completed} event for
 * backward compat (W6 dual-event design).
 *
 * <p>Validation: the report must already exist (created by
 * {@link OptReportService#startReport}) and be in {@code pending} or
 * {@code running} state. Re-calling on an already-{@code completed} /
 * {@code error} report is rejected — V1 does not support "edit a published
 * report" semantics.
 */
public class WriteOptReportTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(WriteOptReportTool.class);

    private final FlywheelRunRepository reportRepository;
    private final FlywheelRunService flywheelRunService;
    private final OptReportService reportService;
    private final ObjectMapper objectMapper;

    public WriteOptReportTool(FlywheelRunRepository reportRepository,
                              FlywheelRunService flywheelRunService,
                              OptReportService reportService,
                              ObjectMapper objectMapper) {
        this.reportRepository = reportRepository;
        this.flywheelRunService = flywheelRunService;
        this.reportService = reportService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "WriteOptReport";
    }

    @Override
    public String getDescription() {
        return "OPT-REPORT-V1 STEP 7: persist the generated markdown report + "
                + "structured summary JSON to t_flywheel_run (status pending|running → "
                + "completed). reportId must be a UUID returned by the controller's "
                + "POST /api/flywheel/agents/{id}/generate-report; calling on already "
                + "completed/error rows is rejected. Triggers an opt_report_completed "
                + "WebSocket broadcast on success.";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("reportId", Map.of(
                "type", "string",
                "description", "Report UUID (returned by the controller when the run was triggered)."));
        properties.put("contentMd", Map.of(
                "type", "string",
                "description", "Markdown body of the optimization report."));
        properties.put("summaryJson", Map.of(
                "type", "object",
                "description", "Optional structured summary (free-form in V1)."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("reportId", "contentMd"));
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            if (input == null) {
                return SkillResult.validationError("input is required");
            }
            String reportId = asString(input.get("reportId"));
            if (reportId == null || reportId.isBlank()) {
                return SkillResult.validationError("reportId is required");
            }
            String contentMd = asString(input.get("contentMd"));
            if (contentMd == null || contentMd.isBlank()) {
                return SkillResult.validationError("contentMd is required");
            }
            String summaryJson = serializeSummary(input.get("summaryJson"));

            Optional<FlywheelRunEntity> opt = reportRepository.findById(reportId);
            if (opt.isEmpty()) {
                return SkillResult.error("OptReport not found: " + reportId);
            }
            FlywheelRunEntity report = opt.get();
            String status = report.getStatus();
            if (!FlywheelRunEntity.STATUS_PENDING.equals(status)
                    && !FlywheelRunEntity.STATUS_RUNNING.equals(status)) {
                return SkillResult.validationError(
                        "OptReport " + reportId + " is not writable (status=" + status + ")");
            }

            // Service-layer write — also fires the generic
            // `flywheel_run_status_changed` WS event for the dashboard
            // "All Flywheel Runs" page subscribers.
            flywheelRunService.markCompleted(reportId, contentMd, summaryJson);

            // OPT-REPORT-V1 backward-compat WS event — best-effort, never let a
            // broadcast failure mask the successful DB write (operator can still
            // see the row via the UI).
            try {
                reportService.onReportCompleted(reportId);
            } catch (RuntimeException broadcastEx) {
                log.warn("WriteOptReportTool: opt_report_completed WS broadcast failed for reportId={}: {}",
                        reportId, broadcastEx.getMessage());
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ok", true);
            payload.put("reportId", reportId);
            payload.put("contentMdLength", contentMd.length());

            log.info("WriteOptReportTool: reportId={} contentMdLength={} hasSummary={}",
                    reportId, contentMd.length(), summaryJson != null);
            return SkillResult.success(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.error("WriteOptReportTool execute failed", e);
            return SkillResult.error("WriteOptReport error: " + e.getMessage());
        }
    }

    private String serializeSummary(Object raw) throws Exception {
        if (raw == null) return null;
        if (raw instanceof String s) {
            return s.isBlank() ? null : s;
        }
        return objectMapper.writeValueAsString(raw);
    }

    private static String asString(Object o) {
        if (o == null) return null;
        String s = o.toString();
        return s.isBlank() ? null : s;
    }
}
