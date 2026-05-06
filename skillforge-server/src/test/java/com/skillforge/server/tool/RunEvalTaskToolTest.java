package com.skillforge.server.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.entity.EvalTaskEntity;
import com.skillforge.server.eval.EvalOrchestrator;
import com.skillforge.server.repository.EvalTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EVAL-V2 M3a (b2): unit test for {@link RunEvalTaskTool}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RunEvalTaskTool")
class RunEvalTaskToolTest {

    @Mock
    private EvalOrchestrator evalOrchestrator;

    @Mock
    private EvalTaskRepository evalTaskRepository;

    @Mock
    private ExecutorService evalOrchestratorExecutor;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private RunEvalTaskTool tool;

    @BeforeEach
    void setUp() {
        tool = new RunEvalTaskTool(
                evalOrchestrator, evalTaskRepository,
                evalOrchestratorExecutor, objectMapper);
        when(evalTaskRepository.save(any(EvalTaskEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("execute_missingAgentId_returnsValidationError")
    void execute_missingAgentId_returnsValidationError() {
        SkillResult result = tool.execute(Map.of(), new SkillContext());
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("input is required");
    }

    @Test
    @DisplayName("execute_blankAgentId_returnsValidationError")
    void execute_blankAgentId_returnsValidationError() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("agentId", "");
        SkillResult result = tool.execute(input, new SkillContext());
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("agentId");
    }

    @Test
    @DisplayName("execute_nonNumericAgentId_returnsValidationError")
    void execute_nonNumericAgentId_returnsValidationError() {
        Map<String, Object> input = Map.of("agentId", "not-a-number");
        SkillResult result = tool.execute(input, new SkillContext());
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("numeric");
    }

    @Test
    @DisplayName("execute_validAgentId_savesPendingTaskAndDispatches")
    void execute_validAgentId_savesPendingTaskAndDispatches() {
        Map<String, Object> input = Map.of("agentId", "42");

        SkillResult result = tool.execute(input, new SkillContext("/tmp", "session-x", 7L));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("\"status\":\"PENDING\"");

        ArgumentCaptor<EvalTaskEntity> taskCap = ArgumentCaptor.forClass(EvalTaskEntity.class);
        verify(evalTaskRepository).save(taskCap.capture());
        EvalTaskEntity saved = taskCap.getValue();
        assertThat(saved.getAgentDefinitionId()).isEqualTo("42");
        assertThat(saved.getStatus()).isEqualTo("PENDING");
        assertThat(saved.getDatasetFilter()).isNull();
        assertThat(saved.getTriggeredByUserId()).isEqualTo(7L);

        verify(evalOrchestratorExecutor, times(1)).submit(any(Runnable.class));
    }

    @Test
    @DisplayName("execute_withMapDatasetFilter_serializesToJsonAndPersists")
    void execute_withMapDatasetFilter_serializesToJsonAndPersists() {
        Map<String, Object> filter = Map.of("split", "held_out");
        Map<String, Object> input = Map.of("agentId", "42", "datasetFilter", filter);

        SkillResult result = tool.execute(input, new SkillContext());

        assertThat(result.isSuccess()).isTrue();
        verify(evalTaskRepository).save(argThat(t ->
                t.getDatasetFilter() != null
                        && t.getDatasetFilter().contains("\"split\"")
                        && t.getDatasetFilter().contains("\"held_out\"")));
    }

    @Test
    @DisplayName("execute_withStringDatasetFilter_persistsVerbatim")
    void execute_withStringDatasetFilter_persistsVerbatim() {
        String filterJson = "{\"split\":\"held_out\"}";
        Map<String, Object> input = Map.of("agentId", "42", "datasetFilter", filterJson);

        SkillResult result = tool.execute(input, new SkillContext());

        assertThat(result.isSuccess()).isTrue();
        verify(evalTaskRepository).save(argThat(t -> filterJson.equals(t.getDatasetFilter())));
    }

    @Test
    @DisplayName("getName / getDescription / getToolSchema return non-null")
    void schemaShape() {
        assertThat(tool.getName()).isEqualTo("RunEvalTask");
        assertThat(tool.getDescription()).isNotBlank();
        assertThat(tool.getToolSchema()).isNotNull();
        assertThat(tool.isReadOnly()).isFalse();
    }
}
