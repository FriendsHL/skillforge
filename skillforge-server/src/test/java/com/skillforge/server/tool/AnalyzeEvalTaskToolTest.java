package com.skillforge.server.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.entity.EvalTaskEntity;
import com.skillforge.server.repository.EvalTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AnalyzeEvalTaskTool")
class AnalyzeEvalTaskToolTest {

    @Mock
    private EvalTaskRepository evalTaskRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AnalyzeEvalTaskTool tool;

    @BeforeEach
    void setUp() {
        tool = new AnalyzeEvalTaskTool(evalTaskRepository, objectMapper);
    }

    @Test
    @DisplayName("execute_persistsSummarySuggestionAndSessionLink")
    void execute_persistsSummarySuggestionAndSessionLink() {
        EvalTaskEntity task = new EvalTaskEntity();
        task.setId("task-1");
        when(evalTaskRepository.findById("task-1")).thenReturn(Optional.of(task));
        when(evalTaskRepository.save(any(EvalTaskEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SkillResult result = tool.execute(Map.of(
                "taskId", "task-1",
                "attributionSummary", "Main failures came from missing repo context.",
                "improvementSuggestion", "Teach the agent to inspect repository interfaces before editing."
        ), new SkillContext("/tmp", "analysis-s1", 7L));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("\"taskId\":\"task-1\"");

        ArgumentCaptor<EvalTaskEntity> captor = ArgumentCaptor.forClass(EvalTaskEntity.class);
        verify(evalTaskRepository).save(captor.capture());
        EvalTaskEntity saved = captor.getValue();
        assertThat(saved.getAttributionSummary()).contains("missing repo context");
        assertThat(saved.getImprovementSuggestion()).contains("inspect repository interfaces");
        assertThat(saved.getAnalysisSessionId()).isEqualTo("analysis-s1");
    }

    @Test
    @DisplayName("execute_rejectsMissingUpdateFields")
    void execute_rejectsMissingUpdateFields() {
        SkillResult result = tool.execute(Map.of("taskId", "task-1"), new SkillContext());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("at least one");
    }

    @Test
    @DisplayName("execute_rejectsMissingTask")
    void execute_rejectsMissingTask() {
        when(evalTaskRepository.findById("missing")).thenReturn(Optional.empty());

        SkillResult result = tool.execute(Map.of(
                "taskId", "missing",
                "attributionSummary", "summary"
        ), new SkillContext());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("not found");
    }
}
