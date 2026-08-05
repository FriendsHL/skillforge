package com.skillforge.server.tool.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.dto.SessionTaskResponse;
import com.skillforge.server.dto.SessionTaskSnapshotResponse;
import com.skillforge.server.service.SessionTaskService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TaskUpdateTool implements Tool {
    public static final String NAME = "TaskUpdate";

    private final SessionTaskService taskService;
    private final ObjectMapper objectMapper;

    public TaskUpdateTool(SessionTaskService taskService, ObjectMapper objectMapper) {
        this.taskService = taskService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "更新持久化 Session 任务的状态、内容、负责人或依赖。开始工作前设为 in_progress，"
                + "验证完成后才设为 completed；用户反馈未满足要求时改回 pending。"
                + "将任务设为 in_progress 时，平台会原子暂停同 owner 的旧 in_progress。";
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("taskId", Map.of("type", "string", "description", "Task ID"));
        properties.put("subject", Map.of("type", "string"));
        properties.put("description", Map.of("type", "string"));
        properties.put("activeForm", Map.of("type", "string"));
        properties.put("status", Map.of(
                "type", "string",
                "enum", List.of("pending", "in_progress", "completed", "deleted")));
        properties.put("owner", Map.of("type", "string", "description", "空字符串清除负责人"));
        properties.put("metadata", Map.of("type", "object"));
        for (String name : List.of("addBlockedBy", "addBlocks")) {
            properties.put(name, Map.of("type", "array", "items", Map.of("type", "string")));
        }
        return new ToolSchema(NAME, getDescription(), Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("taskId")));
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        if (TaskToolSupport.sessionId(context) == null || TaskToolSupport.userId(context) == null) {
            return TaskToolSupport.contextError(objectMapper);
        }
        String taskId = TaskToolSupport.string(input, "taskId", "task_id", "id");
        try {
            SessionTaskSnapshotResponse snapshot = taskService.update(
                    TaskToolSupport.sessionId(context),
                    TaskToolSupport.userId(context),
                    taskId,
                    new SessionTaskService.UpdateCommand(
                            TaskToolSupport.string(input, "subject", "title", "content"),
                            TaskToolSupport.string(input, "description"),
                            TaskToolSupport.string(input, "activeForm", "active_form"),
                            TaskToolSupport.string(input, "status"),
                            TaskToolSupport.contains(input, "owner"),
                            TaskToolSupport.string(input, "owner"),
                            TaskToolSupport.contains(input, "metadata"),
                            TaskToolSupport.map(input, "metadata"),
                            TaskToolSupport.strings(input, "addBlockedBy", "add_blocked_by"),
                            List.of(),
                            TaskToolSupport.strings(input, "addBlocks", "add_blocks"),
                            List.of()));
            SessionTaskResponse task = snapshot.tasks().stream()
                    .filter(candidate -> candidate.taskId().equals(taskId))
                    .findFirst()
                    .orElse(null);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("task", task);
            result.put("summary", snapshot.summary());
            return SkillResult.success(TaskToolSupport.json(result, objectMapper));
        } catch (Exception e) {
            return TaskToolSupport.error(e, objectMapper);
        }
    }
}
