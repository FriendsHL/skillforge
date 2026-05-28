package com.skillforge.server.tool.flywheel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.flywheel.orchestrator.OrchestrationStepCompletedEvent;
import com.skillforge.server.flywheel.run.FlywheelRunService;
import com.skillforge.server.flywheel.run.FlywheelRunStepEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OPT-LOOP-FRAMEWORK Sprint 2 FR-3 (2nd Tool): worker SubAgent calls this at
 * the end of its run to report its structured output back to the parent
 * {@code OrchestratorAgentExecutor}.
 *
 * <p>Mirrors the OPT-REPORT-V1 {@code RecordBatchAnnotations} write-back
 * pattern (Plan §1.3) but generalised — the output is a free-schema JSON
 * object rather than just an annotation count, so future orchestrators
 * (memory-curator → MemoryProposal list; attribution-batch → patternId list;
 * metrics-collector → metric snapshot) can persist their own shape without
 * a new Tool per loop_kind.
 *
 * <h2>What this Tool does</h2>
 * <ol>
 *   <li>Transitions the {@code FlywheelRunStepEntity} row from
 *       {@code pending} to {@code completed} / {@code error} via
 *       {@code FlywheelRunService.transitionStepStatus} — that call writes
 *       the row + publishes {@code StepStateChangedEvent} (AFTER_COMMIT WS
 *       broadcast).</li>
 *   <li>Publishes {@code OrchestrationStepCompletedEvent} — consumed by
 *       {@code WorkerCompletionListener} to release the parent executor's
 *       pending future.</li>
 * </ol>
 *
 * <p>Tool not @Transactional — the row write happens inside the service
 * method which owns its own tx (Plan §2 Design 5 Rule 3).
 */
public class RecordOrchestrationStepResult implements Tool {

    private static final Logger log = LoggerFactory.getLogger(RecordOrchestrationStepResult.class);

    private static final Set<String> ALLOWED_STATUS = Set.of(
            FlywheelRunStepEntity.STATUS_COMPLETED,
            FlywheelRunStepEntity.STATUS_ERROR);

    private final FlywheelRunService flywheelRunService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final ObjectMapper objectMapper;

    public RecordOrchestrationStepResult(FlywheelRunService flywheelRunService,
                                         ApplicationEventPublisher applicationEventPublisher,
                                         ObjectMapper objectMapper) {
        this.flywheelRunService = flywheelRunService;
        this.applicationEventPublisher = applicationEventPublisher;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "RecordOrchestrationStepResult";
    }

    @Override
    public String getDescription() {
        return "OPT-LOOP-FRAMEWORK worker-side completion: call ONCE at the very end of your "
                + "orchestrator worker run. Persists your structured outputJson + terminal "
                + "status onto t_flywheel_run_step and unblocks the parent orchestrator that "
                + "is waiting on DispatchOrchestrationStep. stepRunId is provided in your "
                + "kickoff message. status defaults to 'completed'; pass status='error' + "
                + "errorReason when the run failed mid-way. outputJson is your free-schema "
                + "result payload (e.g. {memoryProposalIds:[...]} for memory-curator).";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("stepRunId", Map.of(
                "type", "string",
                "description", "Step UUID (from the orchestrator kickoff message)."));
        properties.put("status", Map.of(
                "type", "string",
                "description", "Terminal status; default 'completed'.",
                "enum", List.copyOf(ALLOWED_STATUS)));
        properties.put("outputJson", Map.of(
                "type", "object",
                "description", "Free-schema result payload — persisted to step_output_json + "
                        + "surfaced to the parent orchestrator as StepResult.outputJson."));
        properties.put("errorReason", Map.of(
                "type", "string",
                "description", "Required when status='error'; ignored otherwise."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("stepRunId", "status"));
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            if (input == null) {
                return SkillResult.validationError("input is required");
            }
            String stepRunId = asString(input.get("stepRunId"));
            if (stepRunId == null) {
                return SkillResult.validationError("stepRunId is required");
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
            Object outputJsonObj = input.get("outputJson");
            JsonNode outputJson = outputJsonObj == null
                    ? null
                    : objectMapper.valueToTree(outputJsonObj);

            // Row transition (own @Transactional; publishes StepStateChangedEvent for WS).
            try {
                flywheelRunService.transitionStepStatus(stepRunId, status, outputJson, errorReason);
            } catch (IllegalArgumentException notFound) {
                return SkillResult.validationError(notFound.getMessage());
            } catch (IllegalStateException raceErr) {
                // Step might have flipped to terminal already via the fallback path.
                // Treat as idempotent — we still surface "ok" so the worker's loop
                // can terminate cleanly. Listener will see this event and no-op.
                log.debug("RecordOrchestrationStepResult: stepRunId={} already terminal, treating as idempotent",
                        stepRunId);
            }

            // Notify the executor's pending future via the event bus.
            try {
                applicationEventPublisher.publishEvent(new OrchestrationStepCompletedEvent(
                        stepRunId, status, outputJson, errorReason,
                        OrchestrationStepCompletedEvent.TRIGGER_TOOL));
            } catch (Exception evtErr) {
                log.warn("RecordOrchestrationStepResult: publish event failed for stepRunId={}: {}",
                        stepRunId, evtErr.getMessage());
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ok", true);
            payload.put("stepRunId", stepRunId);
            payload.put("status", status);

            log.info("RecordOrchestrationStepResult: stepRunId={} status={}", stepRunId, status);
            return SkillResult.success(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.error("RecordOrchestrationStepResult execute failed", e);
            return SkillResult.error("RecordOrchestrationStepResult error: " + e.getMessage());
        }
    }

    private static String asString(Object o) {
        if (o == null) return null;
        String s = o.toString();
        return s.isBlank() ? null : s;
    }
}
