package com.skillforge.server.tool.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.service.SessionTaskService;

import java.util.List;
import java.util.Map;

public class TaskGetTool implements Tool {
    public static final String NAME = "TaskGet";

    private final SessionTaskService taskService;
    private final ObjectMapper objectMapper;

    public TaskGetTool(SessionTaskService taskService, ObjectMapper objectMapper) {
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
        return "按 Task ID 读取当前 Session 的一个持久化任务及其依赖状态。"
                + "已知 ID 时使用；不知道 ID 时先调用 TaskList。";
    }

    @Override
    public ToolSchema getToolSchema() {
        return new ToolSchema(NAME, getDescription(), Map.of(
                "type", "object",
                "properties", Map.of("taskId", Map.of("type", "string")),
                "required", List.of("taskId")));
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        if (TaskToolSupport.sessionId(context) == null || TaskToolSupport.userId(context) == null) {
            return TaskToolSupport.contextError(objectMapper);
        }
        try {
            return SkillResult.success(TaskToolSupport.json(taskService.get(
                    TaskToolSupport.sessionId(context),
                    TaskToolSupport.userId(context),
                    TaskToolSupport.string(input, "taskId", "task_id", "id")), objectMapper));
        } catch (Exception e) {
            return TaskToolSupport.error(e, objectMapper);
        }
    }
}
