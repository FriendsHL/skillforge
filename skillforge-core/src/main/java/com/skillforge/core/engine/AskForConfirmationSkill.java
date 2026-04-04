package com.skillforge.core.engine;

import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.Skill;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 子 Agent 专用 Skill，用于向主 Agent 请求确认。
 * 调用时会阻塞子 Agent 线程，直到主 Agent 回复确认或超时。
 */
public class AskForConfirmationSkill implements Skill {

    private static final Logger log = LoggerFactory.getLogger(AskForConfirmationSkill.class);

    private final SubAgentExecutor executor;

    public AskForConfirmationSkill(SubAgentExecutor executor) {
        this.executor = executor;
    }

    @Override
    public String getName() {
        return "AskForConfirmation";
    }

    @Override
    public String getDescription() {
        return "Ask the parent agent for confirmation or clarification before proceeding. "
                + "Use this when you need additional information or approval to continue the task.";
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("question", Map.of(
                "type", "string",
                "description", "The question or clarification to ask the parent agent"
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("question"));

        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        String question = (String) input.get("question");
        if (question == null || question.isBlank()) {
            return SkillResult.error("question is required");
        }

        String taskId = context.getSubAgentTaskId();
        if (taskId == null || taskId.isBlank()) {
            return SkillResult.error("No sub-agent task ID found in context. "
                    + "This skill can only be used by sub-agents.");
        }

        try {
            String response = executor.waitForConfirmation(taskId, question);
            return SkillResult.success(response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SkillResult.error("Confirmation request was interrupted: " + e.getMessage());
        } catch (Exception e) {
            log.error("Error waiting for confirmation for task {}", taskId, e);
            return SkillResult.error("Error waiting for confirmation: " + e.getMessage());
        }
    }
}
