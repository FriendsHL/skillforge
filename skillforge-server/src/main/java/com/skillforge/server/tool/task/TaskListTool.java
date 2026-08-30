package com.skillforge.server.tool.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.dto.SessionTaskResponse;
import com.skillforge.server.dto.SessionTaskSnapshotResponse;
import com.skillforge.server.service.TaskToolOperations;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TaskListTool implements Tool {
    public static final String NAME = "TaskList";
    private static final Set<String> SUPPORTED_STATUSES =
            Set.of("pending", "in_progress", "completed", "deleted");

    private final TaskToolOperations taskService;
    private final ObjectMapper objectMapper;

    public TaskListTool(TaskToolOperations taskService, ObjectMapper objectMapper) {
        this.taskService = taskService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String getDescription() {
        return "列出当前 Session 的持久化任务，用于恢复进度、寻找 Task ID 或决定下一步。"
                + "默认排除 deleted，并限制返回数量。";
    }

    @Override
    public ToolSchema getToolSchema() {
        return new ToolSchema(NAME, getDescription(), Map.of(
                "type", "object",
                "properties", Map.of(
                        "statuses", Map.of(
                                "type", "array",
                                "items", Map.of(
                                        "type", "string",
                                        "enum", List.of("pending", "in_progress", "completed", "deleted"))),
                        "includeDeleted", Map.of("type", "boolean", "description", "默认 false"),
                        "availableOnly", Map.of("type", "boolean",
                                "description", "Team 中只返回 pending、未阻塞且未被领取的任务"),
                        "limit", Map.of("type", "integer", "minimum", 1, "maximum", 200))));
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        if (TaskToolSupport.sessionId(context) == null || TaskToolSupport.userId(context) == null) {
            return TaskToolSupport.contextError(objectMapper);
        }
        try {
            boolean includeDeleted = Boolean.TRUE.equals(
                    TaskToolSupport.value(input, "includeDeleted", "include_deleted"));
            int limit = TaskToolSupport.integer(input, "limit", 100, 200);
            boolean availableOnly = Boolean.TRUE.equals(
                    TaskToolSupport.value(input, "availableOnly", "available_only"));
            Set<String> statuses = Set.copyOf(TaskToolSupport.strings(input, "statuses"));
            if (!SUPPORTED_STATUSES.containsAll(statuses)) {
                throw new IllegalArgumentException(
                        "statuses must contain only pending, in_progress, completed, or deleted");
            }
            SessionTaskSnapshotResponse snapshot = taskService.snapshot(
                    TaskToolSupport.sessionId(context),
                    TaskToolSupport.userId(context),
                    includeDeleted,
                    availableOnly);
            List<SessionTaskResponse> matched = snapshot.tasks().stream()
                    .filter(task -> statuses.isEmpty() || statuses.contains(task.status()))
                    .toList();
            List<SessionTaskResponse> returned = matched.stream().limit(limit).toList();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("sessionId", snapshot.sessionId());
            result.put("summary", snapshot.summary());
            result.put("total", matched.size());
            result.put("returned", returned.size());
            result.put("truncated", matched.size() > returned.size());
            result.put("tasks", returned);
            return SkillResult.success(TaskToolSupport.json(result, objectMapper));
        } catch (Exception e) {
            return TaskToolSupport.error(e, objectMapper);
        }
    }
}
