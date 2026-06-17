package com.skillforge.server.tool.optreport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.flywheel.run.FlywheelRunStepEntity;
import com.skillforge.server.flywheel.run.FlywheelRunStepRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * OPT-REPORT-V1 — STEP B of the {@code session-batch-annotator} SubAgent.
 *
 * <p>After the worker finishes (or hits errors on) its batch of 1-5
 * sessions, it calls this tool to update the parent
 * {@link FlywheelRunStepEntity} row with the actual {@code annotations_written_count}
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
            FlywheelRunStepEntity.STATUS_COMPLETED,
            FlywheelRunStepEntity.STATUS_ERROR);

    private final FlywheelRunStepRepository batchRepository;
    private final ObjectMapper objectMapper;

    public RecordBatchAnnotationsTool(FlywheelRunStepRepository batchRepository,
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
                status = FlywheelRunStepEntity.STATUS_COMPLETED;
            }
            if (!ALLOWED_STATUS.contains(status)) {
                return SkillResult.validationError(
                        "status must be one of " + ALLOWED_STATUS + "; got '" + status + "'");
            }
            String errorReason = asString(input.get("errorReason"));
            String reportId = asString(input.get("reportId"));
            Object sessionIdsRaw = input.get("sessionIds");

            // Robustness (provider-agnostic): the batchId is supposed to be a UUID the
            // driving model generated, but a weaker model can emit a longer / non-UUID
            // string. t_flywheel_run_step.id is varchar(36), so persisting it verbatim
            // raises a DataIntegrityViolation ("value too long") that aborts the entire
            // opt-report. Normalize to a stable 36-char id (UUID passthrough, else a
            // deterministic derived UUID) so the upsert dedup still works and a bad
            // model-supplied id can never crash report generation.
            String stepId = normalizeStepId(batchId);

            Optional<FlywheelRunStepEntity> opt = batchRepository.findById(stepId);
            FlywheelRunStepEntity batch;
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
                batch = new FlywheelRunStepEntity();
                batch.setId(stepId);
                batch.setRunId(reportId);
                batch.setStepInputJson(sessionIdsJson);
                batch.setSubAgentSessionId(context.getSessionId());
            } else {
                batch = opt.get();
            }
            batch.setStatus(status);
            batch.setStepOutputCount(count);
            if (FlywheelRunStepEntity.STATUS_ERROR.equals(status)) {
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

    /**
     * Map a model-supplied {@code batchId} to an id that fits the
     * {@code t_flywheel_run_step.id} varchar(36) column. Ids of length &le; 36 pass
     * through unchanged (preserves all existing behavior + data, since they already
     * fit); only an over-length id — which a weaker driving model can emit instead
     * of a UUID — is replaced with a deterministic name-based UUID derived from it.
     * Being deterministic, the same {@code batchId} always maps to the same id, so
     * the worker's first/subsequent-call upsert dedup still holds, and an
     * over-length id can never overflow the column and abort the whole opt-report.
     */
    private static String normalizeStepId(String batchId) {
        if (batchId.length() <= 36) {
            return batchId;
        }
        return UUID.nameUUIDFromBytes(batchId.getBytes(StandardCharsets.UTF_8)).toString();
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
