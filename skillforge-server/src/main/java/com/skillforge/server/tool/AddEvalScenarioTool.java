package com.skillforge.server.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.eval.scenario.BaseScenarioService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EVAL-V2 Q3: lets an agent persist a new "base" eval scenario (system-wide
 * test case, not tied to a specific agent) by writing to
 * {@code ~/.skillforge/eval-scenarios/<id>.json}.
 *
 * <p>This is the agent-facing entry point that mirrors the operator-facing
 * {@code POST /api/eval/scenarios/base} controller endpoint. Both converge
 * on {@link BaseScenarioService} so validation / on-disk format stay
 * consistent. Subsequent {@code ScenarioLoader.loadAll()} calls (i.e. the
 * next eval run) will pick up the newly written scenario.
 */
public class AddEvalScenarioTool implements Tool {

    public static final String NAME = "AddEvalScenario";

    private static final Logger log = LoggerFactory.getLogger(AddEvalScenarioTool.class);

    private final BaseScenarioService baseScenarioService;
    private final ObjectMapper objectMapper;

    public AddEvalScenarioTool(BaseScenarioService baseScenarioService, ObjectMapper objectMapper) {
        this.baseScenarioService = baseScenarioService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Add a new base eval scenario (system-wide test case) to the local "
                + "eval dataset. Use this when the user describes a recurring task that "
                + "should be evaluated against any agent — e.g. \"add an eval that checks "
                + "the agent reads /tmp/foo.txt and returns the first line\". The scenario "
                + "is written to ~/.skillforge/eval-scenarios/<id>.json and is picked up "
                + "by the next eval run automatically. Required fields: id (slug-safe), "
                + "name (display label), task (instruction prose). Oracle is optional but "
                + "recommended.";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("id", Map.of(
                "type", "string",
                "description", "Required scenario id (slug, [a-zA-Z0-9][a-zA-Z0-9._-]{0,63})"
        ));
        properties.put("name", Map.of(
                "type", "string",
                "description", "Required display name shown in dataset browser"
        ));
        properties.put("description", Map.of(
                "type", "string",
                "description", "Optional one-line description of what this case exercises"
        ));
        properties.put("category", Map.of(
                "type", "string",
                "description", "Optional category label (e.g. basic, compaction, error-recovery)"
        ));
        properties.put("split", Map.of(
                "type", "string",
                "description", "Optional split label (e.g. held_out, train). Defaults to held_out."
        ));
        properties.put("task", Map.of(
                "type", "string",
                "description", "Required task prose — the instruction the agent will receive"
        ));
        properties.put("oracle", Map.of(
                "type", "object",
                "description", "Optional oracle config: {type: exact_match|contains|regex|llm_judge, "
                        + "expected: string, expectedList: string[]}"
        ));
        properties.put("setup", Map.of(
                "type", "object",
                "description", "Optional setup data such as {files: {filename: content}} for sandbox seeding"
        ));
        properties.put("toolsHint", Map.of(
                "type", "array",
                "description", "Optional tool name hints (e.g. [\"Read\", \"Bash\"])",
                "items", Map.of("type", "string")
        ));
        properties.put("maxLoops", Map.of(
                "type", "integer",
                "description", "Optional cap on loop iterations (default uses scenario runner default)"
        ));
        properties.put("performanceThresholdMs", Map.of(
                "type", "integer",
                "description", "Optional max execution time in ms before the run is flagged slow"
        ));
        properties.put("tags", Map.of(
                "type", "array",
                "description", "Optional free-form tags",
                "items", Map.of("type", "string")
        ));
        properties.put("force", Map.of(
                "type", "boolean",
                "description", "Overwrite existing scenario with the same id (defaults to false). "
                        + "Without this, attempting to add a duplicate id is rejected so the agent can't silently clobber operator-curated cases."
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        // id is now optional — BaseScenarioService autogens a UUID when omitted.
        schema.put("required", List.of("name", "task"));
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            if (input == null || input.isEmpty()) {
                return SkillResult.validationError("input is required");
            }
            boolean force = input.get("force") instanceof Boolean b ? b
                    : Boolean.parseBoolean(String.valueOf(input.getOrDefault("force", "false")));
            String savedId = baseScenarioService.addBaseScenario(input, force);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("id", savedId);
            response.put("status", "saved");
            response.put("path", baseScenarioService.pathFor(savedId).toString());
            response.put("message", "Scenario " + savedId + " will be picked up by the next eval run.");
            return SkillResult.success(objectMapper.writeValueAsString(response));
        } catch (BaseScenarioService.ScenarioAlreadyExistsException e) {
            // Surface as validationError so the agent sees a clean retryable signal
            // (it can choose to retry with force=true or pick a different id).
            return SkillResult.validationError(e.getMessage());
        } catch (IllegalArgumentException e) {
            return SkillResult.validationError(e.getMessage());
        } catch (Exception e) {
            log.error("AddEvalScenario execute failed", e);
            return SkillResult.error("AddEvalScenario error: " + e.getMessage());
        }
    }
}
