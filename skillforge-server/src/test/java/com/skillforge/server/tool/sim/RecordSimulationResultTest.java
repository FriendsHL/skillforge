package com.skillforge.server.tool.sim;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.entity.SimulatorTrialEntity;
import com.skillforge.server.repository.SimulatorTrialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V5 EVAL-DYNAMIC-USER-SIM Phase 1.2 — unit tests for the {@code RecordSimulationResult}
 * tool. Validates input + verifies trial row update behavior.
 */
@ExtendWith(MockitoExtension.class)
class RecordSimulationResultTest {

    @Mock
    private SimulatorTrialRepository trialRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private RecordSimulationResult tool;

    @BeforeEach
    void setUp() {
        tool = new RecordSimulationResult(trialRepository, objectMapper);
    }

    @Test
    @DisplayName("execute writes terminationReason + turnsUsed + observedFailureSignals to the trial row")
    void execute_happyPath_writesTrial() throws Exception {
        SimulatorTrialEntity existing = newTrial("trial-1");
        when(trialRepository.findById("trial-1")).thenReturn(Optional.of(existing));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("trialId", "trial-1");
        input.put("terminationReason", "failure_signal");
        input.put("observedFailureSignals", List.of("用户开始重复同一问题", "agent 回答含'抱歉无法'"));
        input.put("turnsUsed", 7);

        SkillResult result = tool.execute(input, new SkillContext());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("\"ok\":true");
        assertThat(result.getOutput()).contains("\"trialId\":\"trial-1\"");

        ArgumentCaptor<SimulatorTrialEntity> saved = ArgumentCaptor.forClass(SimulatorTrialEntity.class);
        verify(trialRepository).save(saved.capture());
        SimulatorTrialEntity captured = saved.getValue();
        assertThat(captured.getTerminationReason()).isEqualTo("failure_signal");
        assertThat(captured.getTurnsUsed()).isEqualTo(7);
        assertThat(captured.getObservedFailureSignals())
                .isEqualTo("用户开始重复同一问题,agent 回答含'抱歉无法'");
    }

    @Test
    @DisplayName("execute returns validation error when trialId is missing")
    void execute_missingTrialId_validationError() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("terminationReason", "task_completed");
        input.put("turnsUsed", 3);

        SkillResult result = tool.execute(input, new SkillContext());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("trialId");
        verify(trialRepository, never()).save(any());
    }

    @Test
    @DisplayName("execute returns validation error when terminationReason is missing")
    void execute_missingReason_validationError() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("trialId", "trial-1");
        input.put("turnsUsed", 3);

        SkillResult result = tool.execute(input, new SkillContext());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("terminationReason");
        verify(trialRepository, never()).save(any());
    }

    @Test
    @DisplayName("execute returns validation error when turnsUsed is missing")
    void execute_missingTurnsUsed_validationError() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("trialId", "trial-1");
        input.put("terminationReason", "task_completed");

        SkillResult result = tool.execute(input, new SkillContext());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("turnsUsed");
        verify(trialRepository, never()).save(any());
    }

    @Test
    @DisplayName("execute returns error when trial row does not exist")
    void execute_unknownTrial_errors() {
        when(trialRepository.findById("trial-x")).thenReturn(Optional.empty());

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("trialId", "trial-x");
        input.put("terminationReason", "task_completed");
        input.put("turnsUsed", 4);

        SkillResult result = tool.execute(input, new SkillContext());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("Trial not found");
        verify(trialRepository, never()).save(any());
    }

    @Test
    @DisplayName("execute treats observedFailureSignals as optional (null on task_completed)")
    void execute_omittedSignals_savesWithoutOverwrite() throws Exception {
        SimulatorTrialEntity existing = newTrial("trial-2");
        existing.setObservedFailureSignals("pre-existing notes");
        when(trialRepository.findById("trial-2")).thenReturn(Optional.of(existing));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("trialId", "trial-2");
        input.put("terminationReason", "task_completed");
        input.put("turnsUsed", 5);

        SkillResult result = tool.execute(input, new SkillContext());

        assertThat(result.isSuccess()).isTrue();
        ArgumentCaptor<SimulatorTrialEntity> saved = ArgumentCaptor.forClass(SimulatorTrialEntity.class);
        verify(trialRepository).save(saved.capture());
        // null observed input → does not overwrite existing notes
        assertThat(saved.getValue().getObservedFailureSignals()).isEqualTo("pre-existing notes");
        assertThat(saved.getValue().getTerminationReason()).isEqualTo("task_completed");
        assertThat(saved.getValue().getTurnsUsed()).isEqualTo(5);
    }

    @Test
    @DisplayName("execute accepts turnsUsed as a numeric String")
    void execute_turnsUsedAsString_parsed() throws Exception {
        SimulatorTrialEntity existing = newTrial("trial-3");
        when(trialRepository.findById("trial-3")).thenReturn(Optional.of(existing));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("trialId", "trial-3");
        input.put("terminationReason", "max_turns");
        input.put("turnsUsed", "10");

        SkillResult result = tool.execute(input, new SkillContext());

        assertThat(result.isSuccess()).isTrue();
        ArgumentCaptor<SimulatorTrialEntity> saved = ArgumentCaptor.forClass(SimulatorTrialEntity.class);
        verify(trialRepository).save(saved.capture());
        assertThat(saved.getValue().getTurnsUsed()).isEqualTo(10);
    }

    private SimulatorTrialEntity newTrial(String id) {
        SimulatorTrialEntity t = new SimulatorTrialEntity();
        t.setTrialId(id);
        t.setScenarioId("scen-1");
        t.setPersona("销售经理急性子");
        t.setSessionId("sess-" + id);
        t.setTurnsUsed(0);
        return t;
    }
}
