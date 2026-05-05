package com.skillforge.server.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.eval.scenario.BaseScenarioService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EVAL-V2 Q3: contract tests for {@link AddEvalScenarioTool}. Uses a real
 * {@link BaseScenarioService} (with a {@code @TempDir} home) rather than a
 * mock — the tool's value lives in the integration with the service's
 * validation / file-write semantics, so over-mocking would hide regressions.
 */
class AddEvalScenarioToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AddEvalScenarioTool newTool(Path tmpHome) {
        BaseScenarioService svc = new BaseScenarioService(objectMapper, tmpHome.toString());
        return new AddEvalScenarioTool(svc, objectMapper);
    }

    @Test
    @DisplayName("execute: happy path writes file and returns success")
    void execute_happyPath_writesFile(@TempDir Path tmp) {
        AddEvalScenarioTool tool = newTool(tmp);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("id", "sc-tool-01");
        input.put("name", "tool-added case");
        input.put("task", "do the agent task");

        SkillResult result = tool.execute(input, null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("sc-tool-01");
        assertThat(Files.exists(tmp.resolve("sc-tool-01.json"))).isTrue();
    }

    @Test
    @DisplayName("execute: missing required field returns validationError")
    void execute_missingTask_returnsValidationError(@TempDir Path tmp) {
        AddEvalScenarioTool tool = newTool(tmp);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("id", "sc-tool-02");
        input.put("name", "no task");
        // intentionally no task

        SkillResult result = tool.execute(input, null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        assertThat(result.getError()).contains("task");
    }

    @Test
    @DisplayName("execute: path-traversal id returns validationError (no file written)")
    void execute_pathTraversal_rejectedWithoutWrite(@TempDir Path tmp) throws IOException {
        AddEvalScenarioTool tool = newTool(tmp);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("id", "../escape");
        input.put("name", "evil");
        input.put("task", "should not be written");

        SkillResult result = tool.execute(input, null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        // Nothing should have escaped tmpHome
        try (var stream = Files.list(tmp)) {
            assertThat(stream.toList()).isEmpty();
        }
    }

    @Test
    @DisplayName("execute: existing id without force → validationError; force=true overwrites")
    void execute_conflict_andForceOverwrite(@TempDir Path tmp) {
        AddEvalScenarioTool tool = newTool(tmp);

        Map<String, Object> first = new LinkedHashMap<>();
        first.put("id", "sc-dup");
        first.put("name", "first");
        first.put("task", "first task");
        SkillResult ok = tool.execute(first, null);
        assertThat(ok.isSuccess()).isTrue();

        Map<String, Object> conflict = new LinkedHashMap<>();
        conflict.put("id", "sc-dup");
        conflict.put("name", "second");
        conflict.put("task", "second task");
        SkillResult collide = tool.execute(conflict, null);
        assertThat(collide.isSuccess()).isFalse();
        assertThat(collide.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);

        // Now retry with force=true
        Map<String, Object> forced = new LinkedHashMap<>(conflict);
        forced.put("force", true);
        SkillResult overwrite = tool.execute(forced, null);
        assertThat(overwrite.isSuccess()).isTrue();
    }
}
