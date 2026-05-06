package com.skillforge.server.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.entity.EvalTaskEntity;
import com.skillforge.server.repository.EvalTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * EVAL-V2 M3c: lets an analysis chat persist its structured conclusions back
 * onto {@code t_eval_task}.
 *
 * <p>The analysis agent remains conversational, but the task summary fields
 * need a durable write path so the task detail page can surface the latest
 * attribution summary and improvement suggestion without scraping chat text.
 */
public class AnalyzeEvalTaskTool implements Tool {

    public static final String NAME = "AnalyzeEvalTask";

    private static final Logger log = LoggerFactory.getLogger(AnalyzeEvalTaskTool.class);

    private final EvalTaskRepository evalTaskRepository;
    private final ObjectMapper objectMapper;

    public AnalyzeEvalTaskTool(EvalTaskRepository evalTaskRepository, ObjectMapper objectMapper) {
        this.evalTaskRepository = evalTaskRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Persist an eval task analysis back to the task record. Use this after reviewing "
                + "an eval task or a failed case to save the final attribution summary and concrete "
                + "improvement suggestion onto the task itself.";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("taskId", Map.of(
                "type", "string",
                "description", "Required eval task id to update."
        ));
        properties.put("attributionSummary", Map.of(
                "type", "string",
                "description", "Optional overall diagnosis summarizing the main failure patterns."
        ));
        properties.put("improvementSuggestion", Map.of(
                "type", "string",
                "description", "Optional concrete prompt/skill/tooling change recommendation."
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", java.util.List.of("taskId"));
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            if (input == null || input.isEmpty()) {
                return SkillResult.validationError("input is required (taskId at minimum)");
            }
            String taskId = stringify(input.get("taskId"));
            if (taskId == null || taskId.isBlank()) {
                return SkillResult.validationError("taskId is required");
            }
            String attributionSummary = stringify(input.get("attributionSummary"));
            String improvementSuggestion = stringify(input.get("improvementSuggestion"));
            if (isBlank(attributionSummary) && isBlank(improvementSuggestion)) {
                return SkillResult.validationError(
                        "at least one of attributionSummary or improvementSuggestion is required");
            }

            EvalTaskEntity task = evalTaskRepository.findById(taskId).orElse(null);
            if (task == null) {
                return SkillResult.validationError("eval task not found: " + taskId);
            }

            if (!isBlank(attributionSummary)) {
                task.setAttributionSummary(attributionSummary.trim());
            }
            if (!isBlank(improvementSuggestion)) {
                task.setImprovementSuggestion(improvementSuggestion.trim());
            }
            if (context != null && !isBlank(context.getSessionId())) {
                task.setAnalysisSessionId(context.getSessionId());
            }
            evalTaskRepository.save(task);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("taskId", task.getId());
            response.put("analysisSessionId", task.getAnalysisSessionId());
            response.put("attributionSummary", task.getAttributionSummary());
            response.put("improvementSuggestion", task.getImprovementSuggestion());
            response.put("message", "Eval task analysis persisted");
            return SkillResult.success(objectMapper.writeValueAsString(response));
        } catch (Exception e) {
            log.error("AnalyzeEvalTask execute failed", e);
            return SkillResult.error("AnalyzeEvalTask error: " + e.getMessage());
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String stringify(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
