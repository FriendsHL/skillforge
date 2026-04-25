package com.skillforge.server.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.Tool;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.skill.TodoStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool that manages an in-session task list to track progress on multi-step work.
 */
public class TodoWriteTool implements Tool {

    private final TodoStore todoStore;
    private final ObjectMapper objectMapper;

    public TodoWriteTool(TodoStore todoStore) {
        this.todoStore = todoStore;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String getName() {
        return "TodoWrite";
    }

    @Override
    public String getDescription() {
        return "Manage an in-session task list to track progress on multi-step work. "
                + "Tasks persist for the duration of the session.";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> taskProperties = new LinkedHashMap<>();
        taskProperties.put("id", Map.of(
                "type", "string",
                "description", "Unique task ID"
        ));
        taskProperties.put("title", Map.of(
                "type", "string",
                "description", "Task description"
        ));
        taskProperties.put("status", Map.of(
                "type", "string",
                "description", "Task status: pending, in_progress, or completed",
                "enum", List.of("pending", "in_progress", "completed")
        ));

        Map<String, Object> taskSchema = new LinkedHashMap<>();
        taskSchema.put("type", "object");
        taskSchema.put("properties", taskProperties);
        taskSchema.put("required", List.of("id", "title", "status"));

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("tasks", Map.of(
                "type", "array",
                "description", "List of task objects",
                "items", taskSchema
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("tasks"));

        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    @SuppressWarnings("unchecked")
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            String sessionId = context.getSessionId();
            if (sessionId == null || sessionId.isBlank()) {
                return SkillResult.error("Session ID is required for TodoWrite");
            }

            Object tasksObj = input.get("tasks");
            if (tasksObj == null) {
                return SkillResult.error("tasks is required");
            }

            List<Map<String, Object>> tasks;
            if (tasksObj instanceof List) {
                tasks = (List<Map<String, Object>>) tasksObj;
            } else {
                return SkillResult.error("tasks must be an array");
            }

            // Validate tasks
            for (Map<String, Object> task : tasks) {
                String id = (String) task.get("id");
                String title = (String) task.get("title");
                String status = (String) task.get("status");

                if (id == null || id.isBlank()) {
                    return SkillResult.error("Each task must have a non-empty 'id'");
                }
                if (title == null || title.isBlank()) {
                    return SkillResult.error("Each task must have a non-empty 'title'");
                }
                if (status == null || !List.of("pending", "in_progress", "completed").contains(status)) {
                    return SkillResult.error("Task '" + id + "' has invalid status: " + status
                            + ". Must be one of: pending, in_progress, completed");
                }
            }

            // Store tasks as JSON
            String tasksJson = objectMapper.writeValueAsString(tasks);
            todoStore.setTasks(sessionId, tasksJson);

            // Format output
            StringBuilder sb = new StringBuilder();
            sb.append("Tasks updated (").append(tasks.size()).append(" total):\n");

            for (Map<String, Object> task : tasks) {
                String id = (String) task.get("id");
                String title = (String) task.get("title");
                String status = (String) task.get("status");

                String marker;
                switch (status) {
                    case "completed":
                        marker = "[x]";
                        break;
                    case "in_progress":
                        marker = "[>]";
                        break;
                    default:
                        marker = "[ ]";
                        break;
                }

                sb.append("- ").append(marker).append(" ").append(id)
                        .append(": ").append(title)
                        .append(" (").append(status).append(")\n");
            }

            return SkillResult.success(sb.toString().trim());
        } catch (Exception e) {
            return SkillResult.error("Failed to update tasks: " + e.getMessage());
        }
    }
}
