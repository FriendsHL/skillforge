package com.skillforge.server.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.entity.EvalTaskEntity;
import com.skillforge.server.eval.EvalOrchestrator;
import com.skillforge.server.repository.EvalTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * EVAL-V2 M3a (b2): agent-facing entry point that lets an agent kick off a new
 * eval task against a given agent definition.
 *
 * <p>Mirrors the operator-facing {@code POST /api/eval/tasks} controller
 * endpoint. Both:
 * <ol>
 *   <li>Insert a {@link EvalTaskEntity} row in {@code PENDING} state with the
 *       optional {@code datasetFilter} JSON.</li>
 *   <li>Submit the actual run to {@code evalOrchestratorExecutor} so the tool
 *       call returns immediately with the new {@code taskId}.</li>
 * </ol>
 *
 * <p>The agent typically calls this from an analysis chat session — e.g.
 * "kick off an eval task for agent X to verify the suggested prompt change
 * doesn't regress" — and uses the returned taskId to poll status via
 * {@link com.skillforge.server.eval.EvalOrchestrator}'s normal completion
 * channels (WS {@code eval_progress} events).
 */
public class RunEvalTaskTool implements Tool {

    public static final String NAME = "RunEvalTask";

    private static final Logger log = LoggerFactory.getLogger(RunEvalTaskTool.class);

    private final EvalOrchestrator evalOrchestrator;
    private final EvalTaskRepository evalTaskRepository;
    private final ExecutorService evalOrchestratorExecutor;
    private final ObjectMapper objectMapper;

    public RunEvalTaskTool(EvalOrchestrator evalOrchestrator,
                            EvalTaskRepository evalTaskRepository,
                            ExecutorService evalOrchestratorExecutor,
                            ObjectMapper objectMapper) {
        this.evalOrchestrator = evalOrchestrator;
        this.evalTaskRepository = evalTaskRepository;
        this.evalOrchestratorExecutor = evalOrchestratorExecutor;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Trigger a new eval task against an agent definition. Returns the taskId; "
                + "the actual scenario runs execute asynchronously and emit eval_progress "
                + "WebSocket events. Use this when the user asks \"run eval against agent X\" "
                + "or after applying prompt changes that need verification. Optional datasetFilter "
                + "is a JSON object selecting a subset of scenarios — e.g. "
                + "{\"split\":\"held_out\"} or {\"tags\":[\"multi-turn\"]}; omit to run the full "
                + "dataset.";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("agentId", Map.of(
                "type", "string",
                "description", "Required agent definition id to evaluate (e.g. \"123\")."
        ));
        properties.put("datasetFilter", Map.of(
                "type", "object",
                "description", "Optional JSON object selecting a subset of scenarios. "
                        + "Examples: {\"split\":\"held_out\"} or {\"tags\":[\"multi-turn\"]}. "
                        + "Omit to run the full dataset."
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", java.util.List.of("agentId"));
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            if (input == null || input.isEmpty()) {
                return SkillResult.validationError("input is required (agentId at minimum)");
            }
            Object rawAgentId = input.get("agentId");
            if (rawAgentId == null) {
                return SkillResult.validationError("agentId is required");
            }
            String agentId = String.valueOf(rawAgentId).trim();
            if (agentId.isEmpty()) {
                return SkillResult.validationError("agentId must be a non-blank string");
            }
            // Validate agentId is parseable as Long — EvalOrchestrator does
            // Long.parseLong; surfacing the validation error here gives a
            // cleaner agent-facing message than letting the executor fail later.
            try {
                Long.parseLong(agentId);
            } catch (NumberFormatException nfe) {
                return SkillResult.validationError("agentId must be a numeric string (got: " + agentId + ")");
            }

            // Optional datasetFilter — accept either a Map (preferred) or a JSON string.
            String datasetFilterJson = null;
            Object rawFilter = input.get("datasetFilter");
            if (rawFilter != null) {
                if (rawFilter instanceof String s && !s.isBlank()) {
                    datasetFilterJson = s;
                } else if (rawFilter instanceof Map<?, ?> m) {
                    datasetFilterJson = objectMapper.writeValueAsString(m);
                } else {
                    datasetFilterJson = objectMapper.writeValueAsString(rawFilter);
                }
            }

            String taskId = UUID.randomUUID().toString();

            // 1. Persist a PENDING row so the task surfaces in /api/eval/tasks
            //    list immediately (before async runEval starts).
            EvalTaskEntity task = new EvalTaskEntity();
            task.setId(taskId);
            task.setAgentDefinitionId(agentId);
            task.setStatus("PENDING");
            task.setStartedAt(Instant.now());
            task.setDatasetFilter(datasetFilterJson);
            // userId comes from SkillContext; nullable at the persistence layer.
            Long userId = context != null ? context.getUserId() : null;
            if (userId != null) {
                task.setTriggeredByUserId(userId);
            }
            evalTaskRepository.save(task);

            // 2. Async dispatch.
            evalOrchestratorExecutor.submit(() ->
                    evalOrchestrator.runEval(agentId, userId, taskId));

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("taskId", taskId);
            response.put("agentId", agentId);
            response.put("status", "PENDING");
            if (datasetFilterJson != null) {
                response.put("datasetFilter", datasetFilterJson);
            }
            response.put("message", "Eval task submitted. Subscribe to eval_progress events for taskId=" + taskId);
            return SkillResult.success(objectMapper.writeValueAsString(response));
        } catch (IllegalArgumentException e) {
            return SkillResult.validationError(e.getMessage());
        } catch (Exception e) {
            log.error("RunEvalTask execute failed", e);
            return SkillResult.error("RunEvalTask error: " + e.getMessage());
        }
    }
}
