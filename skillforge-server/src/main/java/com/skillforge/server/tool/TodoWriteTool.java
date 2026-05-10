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
        // Schema parameter is named `todos` to align with Anthropic Claude Code's
        // canonical TodoWrite tool — Claude-family models call this tool with
        // `{"todos": [...]}` from training memory; using `tasks` previously caused
        // [RETRY NEEDED] tool_error misfires when the LLM emitted the canonical name.
        Map<String, Object> todoProperties = new LinkedHashMap<>();
        todoProperties.put("id", Map.of(
                "type", "string",
                "description", "Unique todo ID"
        ));
        todoProperties.put("title", Map.of(
                "type", "string",
                "description", "Todo description"
        ));
        todoProperties.put("status", Map.of(
                "type", "string",
                "description", "Todo status: pending, in_progress, or completed",
                "enum", List.of("pending", "in_progress", "completed")
        ));

        Map<String, Object> todoSchema = new LinkedHashMap<>();
        todoSchema.put("type", "object");
        todoSchema.put("properties", todoProperties);
        todoSchema.put("required", List.of("id", "title", "status"));

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("todos", Map.of(
                "type", "array",
                "description", "List of todo objects",
                "items", todoSchema
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("todos"));

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

            Object todosObj = input.get("todos");
            if (todosObj == null) {
                return SkillResult.error("todos is required");
            }

            List<Map<String, Object>> todos;
            if (todosObj instanceof List) {
                todos = (List<Map<String, Object>>) todosObj;
            } else {
                return SkillResult.error("todos must be an array");
            }

            // Validate todos
            for (Map<String, Object> todo : todos) {
                String id = (String) todo.get("id");
                String title = (String) todo.get("title");
                String status = (String) todo.get("status");

                if (id == null || id.isBlank()) {
                    return SkillResult.error("Each todo must have a non-empty 'id'");
                }
                if (title == null || title.isBlank()) {
                    return SkillResult.error("Each todo must have a non-empty 'title'");
                }
                if (status == null || !List.of("pending", "in_progress", "completed").contains(status)) {
                    return SkillResult.error("Todo '" + id + "' has invalid status: " + status
                            + ". Must be one of: pending, in_progress, completed");
                }
            }

            // Store todos as JSON (TodoStore method names retain `tasks` for backward compat)
            String todosJson = objectMapper.writeValueAsString(todos);
            todoStore.setTasks(sessionId, todosJson);

            // Format output
            StringBuilder sb = new StringBuilder();
            sb.append("Todos updated (").append(todos.size()).append(" total):\n");

            for (Map<String, Object> todo : todos) {
                String id = (String) todo.get("id");
                String title = (String) todo.get("title");
                String status = (String) todo.get("status");

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
            return SkillResult.error("Failed to update todos: " + e.getMessage());
        }
    }
}
