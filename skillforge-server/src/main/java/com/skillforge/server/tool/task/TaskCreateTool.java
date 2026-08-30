package com.skillforge.server.tool.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.service.SessionTaskService;
import com.skillforge.server.service.TaskToolOperations;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TaskCreateTool implements Tool {
    public static final String NAME = "TaskCreate";

    private final TaskToolOperations taskService;
    private final ObjectMapper objectMapper;

    public TaskCreateTool(TaskToolOperations taskService, ObjectMapper objectMapper) {
        this.taskService = taskService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "创建一个可持久化、可依赖和可恢复的 Session 任务。复杂工作先创建可验证任务；简单问答不要调用。"
                + "新任务初始为 pending。subject 是简短目标，description 是验收细节；activeForm 可选，默认等于 subject。";
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("subject", Map.of(
                "type", "string", "description", "简短、可验证的任务目标"));
        properties.put("description", Map.of(
                "type", "string", "description", "范围、约束和完成标准"));
        properties.put("activeForm", Map.of(
                "type", "string", "description", "任务执行中展示的进行时文案"));
        properties.put("metadata", Map.of(
                "type", "object", "description", "可选紧凑 JSON 元数据，最大 16 KiB"));
        return new ToolSchema(NAME, getDescription(), Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("subject", "description")));
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        if (TaskToolSupport.sessionId(context) == null || TaskToolSupport.userId(context) == null) {
            return TaskToolSupport.contextError(objectMapper);
        }
        try {
            SessionTaskService.CreateResult created = taskService.create(TaskToolSupport.sessionId(context),
                    TaskToolSupport.userId(context), new SessionTaskService.CreateCommand(
                            TaskToolSupport.string(input, "subject", "title", "content"),
                            TaskToolSupport.string(input, "description"),
                            TaskToolSupport.string(input, "activeForm", "active_form"),
                            null,
                            TaskToolSupport.map(input, "metadata"),
                            List.of()));
            return SkillResult.success(TaskToolSupport.json(Map.of(
                    "success", true,
                    "task", created.task(),
                    "summary", created.snapshot().summary()), objectMapper));
        } catch (Exception e) {
            return TaskToolSupport.error(e, objectMapper);
        }
    }
}
