package com.skillforge.server.tool.optreport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.entity.OptReportBatchEntity;
import com.skillforge.server.repository.OptReportBatchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * OPT-REPORT-V1 — STEP B of the {@code session-batch-annotator} SubAgent.
 *
 * <p>After the worker finishes (or hits errors on) its batch of 1-5
 * sessions, it calls this tool to update the parent
 * {@link OptReportBatchEntity} row with the actual {@code annotations_written_count}
 * and a terminal status ({@code completed} or {@code error}).
 *
 * <p>UPSERT semantics (V99 fix): the parent only generates a fresh UUID and
 * passes it in the SubAgent kickoff message — no Java code creates the row
 * upfront. The first call therefore INSERTs the row using the supplied
 * {@code reportId} + {@code sessionIds}; subsequent calls UPDATE in place.
 * Re-write is permitted so a transient error can be later corrected to
 * {@code completed} via re-dispatch (Phase 2 may tighten this).
 */
public class RecordBatchAnnotationsTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(RecordBatchAnnotationsTool.class);

    private static final Set<String> ALLOWED_STATUS = Set.of(
            OptReportBatchEntity.STATUS_COMPLETED,
            OptReportBatchEntity.STATUS_ERROR);

    private final OptReportBatchRepository batchRepository;
    private final ObjectMapper objectMapper;

    public RecordBatchAnnotationsTool(OptReportBatchRepository batchRepository,
                                      ObjectMapper objectMapper) {
        this.batchRepository = batchRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "RecordBatchAnnotations";
    }

    @Override
    public String getDescription() {
        return "OPT-REPORT-V1 STEP B: call once at the end of your "
                + "session-batch-annotator run to record how many annotation rows you "
                + "actually wrote, and whether the batch completed cleanly. "
                + "UPSERTs the t_opt_report_batch row — if batchId does not yet "
                + "exist, creates it (parent only generates the UUID, doesn't "
                + "pre-insert), so reportId + sessionIds are required on first "
                + "call. status defaults to 'completed'; pass status='error' + "
                + "errorReason when the batch failed mid-way.";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("batchId", Map.of(
                "type", "string",
                "description", "Batch UUID (from the kickoff message)."));
        properties.put("reportId", Map.of(
                "type", "string",
                "description", "Parent report UUID (from the kickoff message). "
                        + "Required so the upsert can create the batch row when it "
                        + "doesn't yet exist."));
        properties.put("sessionIds", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "Session IDs this batch covered (from the kickoff "
                        + "message). Required on first call; ignored on re-write."));
        properties.put("annotationsWrittenCount", Map.of(
                "type", "integer",
                "description", "Total annotation rows the worker actually wrote across the batch."));
        properties.put("status", Map.of(
                "type", "string",
                "description", "Terminal status; default 'completed'. Use 'error' when the batch failed mid-way.",
                "enum", List.copyOf(ALLOWED_STATUS)));
        properties.put("errorReason", Map.of(
                "type", "string",
                "description", "Required when status='error'; ignored otherwise."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("batchId", "reportId", "sessionIds", "annotationsWrittenCount"));
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            if (input == null) {
                return SkillResult.validationError("input is required");
            }
            String batchId = asString(input.get("batchId"));
            if (batchId == null || batchId.isBlank()) {
                return SkillResult.validationError("batchId is required");
            }
            Integer count = asInt(input.get("annotationsWrittenCount"));
            if (count == null || count < 0) {
                return SkillResult.validationError("annotationsWrittenCount must be a non-negative integer");
            }
            String status = asString(input.get("status"));
            if (status == null || status.isBlank()) {
                status = OptReportBatchEntity.STATUS_COMPLETED;
            }
            if (!ALLOWED_STATUS.contains(status)) {
                return SkillResult.validationError(
                        "status must be one of " + ALLOWED_STATUS + "; got '" + status + "'");
            }
            String errorReason = asString(input.get("errorReason"));
            String reportId = asString(input.get("reportId"));
            Object sessionIdsRaw = input.get("sessionIds");

            Optional<OptReportBatchEntity> opt = batchRepository.findById(batchId);
            OptReportBatchEntity batch;
            if (opt.isEmpty()) {
                // First-call path: parent only generates the UUID, this is where
                // the row gets inserted. Reject if reportId / sessionIds missing.
                if (reportId == null) {
                    return SkillResult.validationError(
                            "reportId is required when batchId is not yet registered (first call)");
                }
                if (!(sessionIdsRaw instanceof List<?> idsList) || idsList.isEmpty()) {
                    return SkillResult.validationError(
                            "sessionIds (non-empty array) is required when batchId is not yet registered");
                }
                String sessionIdsJson;
                try {
                    sessionIdsJson = objectMapper.writeValueAsString(idsList);
                } catch (Exception je) {
                    return SkillResult.validationError("sessionIds could not be serialized: " + je.getMessage());
                }
                batch = new OptReportBatchEntity();
                batch.setId(batchId);
                batch.setReportId(reportId);
                batch.setSessionIdsJson(sessionIdsJson);
                batch.setSubAgentSessionId(context.getSessionId());
            } else {
                batch = opt.get();
            }
            batch.setStatus(status);
            batch.setAnnotationsWrittenCount(count);
            if (OptReportBatchEntity.STATUS_ERROR.equals(status)) {
                batch.setErrorReason(errorReason);
            } else {
                // Clear stale error_reason on a successful re-write.
                batch.setErrorReason(null);
            }
            batchRepository.save(batch);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ok", true);
            payload.put("batchId", batchId);
            payload.put("status", status);

            log.info("RecordBatchAnnotationsTool: batchId={} status={} annotationsWrittenCount={}",
                    batchId, status, count);
            return SkillResult.success(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.error("RecordBatchAnnotationsTool execute failed", e);
            return SkillResult.error("RecordBatchAnnotations error: " + e.getMessage());
        }
    }

    private static String asString(Object o) {
        if (o == null) return null;
        String s = o.toString();
        return s.isBlank() ? null : s;
    }

    private static Integer asInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(o.toString().trim()); }
        catch (NumberFormatException e) { return null; }
    }
}
