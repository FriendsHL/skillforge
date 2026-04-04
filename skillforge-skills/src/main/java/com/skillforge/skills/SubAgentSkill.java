package com.skillforge.skills;

import com.skillforge.core.engine.SubAgentExecutor;
import com.skillforge.core.engine.SubAgentTask;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.Skill;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 主 Agent 使用的 Skill，用于异步分派任务给子 Agent、查询状态、回复确认和取消任务。
 */
public class SubAgentSkill implements Skill {

    private static final Logger log = LoggerFactory.getLogger(SubAgentSkill.class);

    private final SubAgentExecutor executor;
    private final Function<Long, AgentDefinition> agentLookup;

    public SubAgentSkill(SubAgentExecutor executor, Function<Long, AgentDefinition> agentLookup) {
        this.executor = executor;
        this.agentLookup = agentLookup;
    }

    @Override
    public String getName() {
        return "SubAgent";
    }

    @Override
    public String getDescription() {
        return "Dispatch tasks to sub-agents asynchronously, query their status, "
                + "respond to confirmations, or cancel tasks.";
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> actionProp = new LinkedHashMap<>();
        actionProp.put("type", "string");
        actionProp.put("description", "The action to perform: dispatch, query, respond, cancel, or list");
        actionProp.put("enum", List.of("dispatch", "query", "respond", "cancel", "list"));

        Map<String, Object> agentIdProp = Map.of(
                "type", "integer",
                "description", "Target agent ID (required for dispatch)"
        );

        Map<String, Object> taskProp = Map.of(
                "type", "string",
                "description", "Task description for the sub-agent (required for dispatch)"
        );

        Map<String, Object> maxTurnsProp = Map.of(
                "type", "integer",
                "description", "Maximum number of loops for the sub-agent (optional, default 15)"
        );

        Map<String, Object> taskIdProp = Map.of(
                "type", "string",
                "description", "Task ID (required for query, respond, cancel)"
        );

        Map<String, Object> responseProp = Map.of(
                "type", "string",
                "description", "Response to the sub-agent's confirmation question (required for respond)"
        );

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", actionProp);
        properties.put("agentId", agentIdProp);
        properties.put("task", taskProp);
        properties.put("maxTurns", maxTurnsProp);
        properties.put("taskId", taskIdProp);
        properties.put("response", responseProp);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("action"));

        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        String action = (String) input.get("action");
        if (action == null || action.isBlank()) {
            return SkillResult.error("action is required");
        }

        try {
            return switch (action) {
                case "dispatch" -> handleDispatch(input, context);
                case "query" -> handleQuery(input);
                case "respond" -> handleRespond(input);
                case "cancel" -> handleCancel(input);
                case "list" -> handleList(context);
                default -> SkillResult.error("Unknown action: " + action
                        + ". Supported actions: dispatch, query, respond, cancel, list");
            };
        } catch (Exception e) {
            log.error("SubAgentSkill error for action '{}'", action, e);
            return SkillResult.error("Error executing action '" + action + "': " + e.getMessage());
        }
    }

    private SkillResult handleDispatch(Map<String, Object> input, SkillContext context) {
        Object agentIdObj = input.get("agentId");
        if (agentIdObj == null) {
            return SkillResult.error("agentId is required for dispatch action");
        }
        long agentId = ((Number) agentIdObj).longValue();

        String task = (String) input.get("task");
        if (task == null || task.isBlank()) {
            return SkillResult.error("task is required for dispatch action");
        }

        int maxTurns = 15;
        if (input.containsKey("maxTurns") && input.get("maxTurns") != null) {
            maxTurns = ((Number) input.get("maxTurns")).intValue();
        }

        AgentDefinition targetAgent = agentLookup.apply(agentId);
        if (targetAgent == null) {
            return SkillResult.error("Agent not found with ID: " + agentId);
        }

        String parentSessionId = context.getSessionId();
        SubAgentTask subTask = executor.dispatch(parentSessionId, targetAgent, task, maxTurns);

        return SkillResult.success("Task dispatched successfully.\n"
                + "  taskId: " + subTask.getTaskId() + "\n"
                + "  targetAgent: " + subTask.getTargetAgentName() + "\n"
                + "  status: " + subTask.getStatus());
    }

    private SkillResult handleQuery(Map<String, Object> input) {
        String taskId = (String) input.get("taskId");
        if (taskId == null || taskId.isBlank()) {
            return SkillResult.error("taskId is required for query action");
        }

        SubAgentTask task = executor.queryTask(taskId);
        if (task == null) {
            return SkillResult.error("Task not found: " + taskId);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Task: ").append(task.getTaskId()).append("\n");
        sb.append("  targetAgent: ").append(task.getTargetAgentName()).append("\n");
        sb.append("  status: ").append(task.getStatus()).append("\n");
        sb.append("  task: ").append(task.getTaskDescription()).append("\n");

        if (task.getConfirmQuestion() != null) {
            sb.append("  confirmQuestion: ").append(task.getConfirmQuestion()).append("\n");
        }
        if (task.getResult() != null) {
            sb.append("  result: ").append(task.getResult()).append("\n");
        }
        if (task.getError() != null) {
            sb.append("  error: ").append(task.getError()).append("\n");
        }
        if (task.getToolCallSummary() != null && !task.getToolCallSummary().isEmpty()) {
            sb.append("  toolCalls: ").append(String.join(", ", task.getToolCallSummary())).append("\n");
        }

        return SkillResult.success(sb.toString());
    }

    private SkillResult handleRespond(Map<String, Object> input) {
        String taskId = (String) input.get("taskId");
        if (taskId == null || taskId.isBlank()) {
            return SkillResult.error("taskId is required for respond action");
        }

        String response = (String) input.get("response");
        if (response == null || response.isBlank()) {
            return SkillResult.error("response is required for respond action");
        }

        executor.respondToConfirmation(taskId, response);
        return SkillResult.success("Confirmation response sent to task: " + taskId);
    }

    private SkillResult handleCancel(Map<String, Object> input) {
        String taskId = (String) input.get("taskId");
        if (taskId == null || taskId.isBlank()) {
            return SkillResult.error("taskId is required for cancel action");
        }

        executor.cancelTask(taskId);
        return SkillResult.success("Task cancelled: " + taskId);
    }

    private SkillResult handleList(SkillContext context) {
        String sessionId = context.getSessionId();
        List<SubAgentTask> tasks = executor.listTasks(sessionId);

        if (tasks.isEmpty()) {
            return SkillResult.success("No sub-agent tasks found for this session.");
        }

        String summary = tasks.stream()
                .map(t -> "- " + t.getTaskId() + " | " + t.getTargetAgentName()
                        + " | " + t.getStatus() + " | " + t.getTaskDescription())
                .collect(Collectors.joining("\n"));

        return SkillResult.success("Sub-agent tasks (" + tasks.size() + "):\n" + summary);
    }
}
