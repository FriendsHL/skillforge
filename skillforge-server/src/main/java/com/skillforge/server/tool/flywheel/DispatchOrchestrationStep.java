package com.skillforge.server.tool.flywheel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.flywheel.orchestrator.OrchestratorAgentExecutor;
import com.skillforge.server.flywheel.orchestrator.OrchestratorWorkerSpec;
import com.skillforge.server.flywheel.orchestrator.StepResult;
import com.skillforge.server.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OPT-LOOP-FRAMEWORK Sprint 2 FR-3: parent orchestrator agent calls this Tool
 * to fan out N worker SubAgents in one shot. The Tool returns synchronously
 * once every worker has completed (via the Tool-path or fallback-path).
 *
 * <h2>Sync barrier semantics</h2>
 * The orchestrator's tool-use call is the natural barrier — the LLM blocks on
 * the tool_result, so {@code execute()} waiting on the aggregate future is
 * the right place to converge fan-out results into one structured payload.
 *
 * <h2>Tool not @Transactional</h2>
 * The {@link OrchestratorAgentExecutor#awaitDispatch} call internally does
 * {@code allOf(...).get(timeout)} — putting a transaction around this would
 * pin a JDBC connection for the entire fan-out duration (Plan §2 Design 5
 * Rule 3). Per-step row writes happen inside
 * {@code FlywheelRunService.appendStep / transitionStepStatus} which have
 * their own @Transactional scope.
 *
 * <h2>Input shape</h2>
 * <pre>
 *   parentRunId : string (UUID of the parent FlywheelRunEntity row)
 *   workers     : list of {
 *       agentId        : long,
 *       agentName      : string,
 *       task           : string (worker's first user-message),
 *       inputJson      : object (free-schema, persisted to step.step_input_json),
 *       timeoutSeconds : int   (per-worker soft deadline, default 300)
 *   }
 * </pre>
 *
 * <h2>Output shape</h2>
 * <pre>
 *   {
 *     "allSucceeded": bool,
 *     "stepResults" : [ { stepRunId, status, errorReason, outputJson }, ... ]
 *   }
 * </pre>
 */
public class DispatchOrchestrationStep implements Tool {

    private static final Logger log = LoggerFactory.getLogger(DispatchOrchestrationStep.class);

    /** Buffer added on top of the longest per-worker timeout when sizing the aggregate wait. */
    private static final long AGGREGATE_TIMEOUT_BUFFER_SECONDS = 60L;

    private final OrchestratorAgentExecutor executor;
    private final SessionService sessionService;
    private final ObjectMapper objectMapper;

    public DispatchOrchestrationStep(OrchestratorAgentExecutor executor,
                                     SessionService sessionService,
                                     ObjectMapper objectMapper) {
        this.executor = executor;
        this.sessionService = sessionService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "DispatchOrchestrationStep";
    }

    @Override
    public String getDescription() {
        return "OPT-LOOP-FRAMEWORK fan-out: dispatch a list of worker SubAgents in parallel and "
                + "wait synchronously for all of them. Each worker spec must have agentId + task; "
                + "agentName + inputJson + timeoutSeconds are optional. Returns "
                + "{ stepResults: [{stepRunId,status,errorReason,outputJson}], allSucceeded: bool }. "
                + "Workers should call RecordOrchestrationStepResult before terminating; if they "
                + "don't, the framework falls back to the SessionLoopFinishedEvent path and uses "
                + "the worker's finalMessage as a best-effort output. Do NOT poll — this Tool "
                + "blocks until all workers settle.";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> workerProps = new LinkedHashMap<>();
        workerProps.put("agentId", Map.of(
                "type", "integer",
                "description", "Child agent id (must be a positive long; resolve by name via "
                        + "AgentDiscovery first if you only have a name)."));
        workerProps.put("agentName", Map.of(
                "type", "string",
                "description", "Human-readable agent name; surfaced in logs / WS payload only."));
        workerProps.put("task", Map.of(
                "type", "string",
                "description", "First user message sent to the worker SubAgent."));
        workerProps.put("inputJson", Map.of(
                "type", "object",
                "description", "Free-schema kickoff payload; persisted verbatim to "
                        + "t_flywheel_run_step.step_input_json. Optional."));
        workerProps.put("timeoutSeconds", Map.of(
                "type", "integer",
                "description", "Per-worker soft deadline; default 300s."));

        Map<String, Object> workerSchema = new LinkedHashMap<>();
        workerSchema.put("type", "object");
        workerSchema.put("properties", workerProps);
        workerSchema.put("required", List.of("agentId", "task"));

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("parentRunId", Map.of(
                "type", "string",
                "description", "Parent FlywheelRun UUID (from the orchestrator's kickoff prompt)."));
        properties.put("workers", Map.of(
                "type", "array",
                "items", workerSchema,
                "description", "Non-empty list of worker specs."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("parentRunId", "workers"));
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            if (input == null) {
                return SkillResult.validationError("input is required");
            }
            String parentRunId = asString(input.get("parentRunId"));
            if (parentRunId == null) {
                return SkillResult.validationError("parentRunId is required");
            }
            Object workersObj = input.get("workers");
            if (!(workersObj instanceof List<?> rawWorkers) || rawWorkers.isEmpty()) {
                return SkillResult.validationError("workers (non-empty array) is required");
            }
            String parentSessionId = context.getSessionId();
            if (parentSessionId == null) {
                return SkillResult.error("No parent session in context — cannot dispatch");
            }
            SessionEntity parentSession = sessionService.getSession(parentSessionId);

            List<OrchestratorWorkerSpec> specs = new ArrayList<>(rawWorkers.size());
            long maxTimeoutSeconds = 0L;
            for (int i = 0; i < rawWorkers.size(); i++) {
                Object raw = rawWorkers.get(i);
                if (!(raw instanceof Map<?, ?> rawMap)) {
                    return SkillResult.validationError(
                            "workers[" + i + "] must be an object");
                }
                try {
                    OrchestratorWorkerSpec spec = parseWorker(rawMap);
                    specs.add(spec);
                    if (spec.timeoutSeconds() > maxTimeoutSeconds) {
                        maxTimeoutSeconds = spec.timeoutSeconds();
                    }
                } catch (IllegalArgumentException badInput) {
                    return SkillResult.validationError(
                            "workers[" + i + "]: " + badInput.getMessage());
                }
            }
            long aggregateTimeoutSec = maxTimeoutSeconds + AGGREGATE_TIMEOUT_BUFFER_SECONDS;

            List<StepResult> results = executor.awaitDispatch(
                    parentRunId, parentSession, specs, aggregateTimeoutSec);

            // Build the structured output payload — keep it small + JSON-friendly
            // so the LLM can read it directly. Don't dump outputJson for every
            // worker (can blow up token budget); include only when the orchestrator
            // explicitly asked for it via a small stepResult excerpt.
            List<Map<String, Object>> stepResults = new ArrayList<>(results.size());
            boolean allSucceeded = true;
            for (StepResult r : results) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("stepRunId", r.stepRunId());
                item.put("status", r.status());
                if (r.errorReason() != null) item.put("errorReason", r.errorReason());
                if (r.outputJson() != null) item.put("outputJson", r.outputJson());
                stepResults.add(item);
                if (!r.succeeded()) allSucceeded = false;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("allSucceeded", allSucceeded);
            payload.put("stepResults", stepResults);

            log.info("DispatchOrchestrationStep.execute: parentRunId={} workers={} allSucceeded={}",
                    parentRunId, specs.size(), allSucceeded);
            return SkillResult.success(objectMapper.writeValueAsString(payload));
        } catch (IllegalStateException ise) {
            // depth / concurrency limits from SubAgentRegistry
            return SkillResult.error(ise.getMessage());
        } catch (Exception e) {
            log.error("DispatchOrchestrationStep.execute failed", e);
            return SkillResult.error("DispatchOrchestrationStep error: " + e.getMessage());
        }
    }

    private OrchestratorWorkerSpec parseWorker(Map<?, ?> raw) {
        Long agentId = asLong(raw.get("agentId"));
        if (agentId == null) {
            throw new IllegalArgumentException("agentId is required");
        }
        String agentName = asString(raw.get("agentName"));
        String task = asString(raw.get("task"));
        if (task == null) {
            throw new IllegalArgumentException("task is required");
        }
        String inputJson;
        Object inputJsonObj = raw.get("inputJson");
        if (inputJsonObj == null) {
            inputJson = "{}";
        } else if (inputJsonObj instanceof String s) {
            inputJson = s;
        } else {
            try {
                inputJson = objectMapper.writeValueAsString(inputJsonObj);
            } catch (Exception e) {
                throw new IllegalArgumentException("inputJson could not be serialized: " + e.getMessage());
            }
        }
        Integer timeoutSeconds = asInt(raw.get("timeoutSeconds"));
        int t = timeoutSeconds == null ? 300 : timeoutSeconds;
        return new OrchestratorWorkerSpec(agentId, agentName, task, inputJson, t);
    }

    private static String asString(Object o) {
        if (o == null) return null;
        String s = o.toString();
        return s.isBlank() ? null : s;
    }

    private static Long asLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(o.toString().trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private static Integer asInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(o.toString().trim()); }
        catch (NumberFormatException e) { return null; }
    }
}
