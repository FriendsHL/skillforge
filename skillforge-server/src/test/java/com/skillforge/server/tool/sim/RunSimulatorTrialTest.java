package com.skillforge.server.tool.sim;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.eval.usersim.SimulatorTrialOrchestrator;
import com.skillforge.server.eval.usersim.SimulatorTrialOrchestrator.SimulationOutcome;
import com.skillforge.server.eval.usersim.SimulatorTrialOrchestrator.TrialRequest;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V5 EVAL-DYNAMIC-USER-SIM Phase 1.2 — unit tests for the {@code RunSimulatorTrial}
 * tool. Verifies input validation + delegation to the orchestrator. The actual
 * ping-pong loop is exercised in integration / orchestrator-level tests.
 */
@ExtendWith(MockitoExtension.class)
class RunSimulatorTrialTest {

    @Mock
    private SimulatorTrialOrchestrator orchestrator;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private RunSimulatorTrial tool;

    @BeforeEach
    void setUp() {
        tool = new RunSimulatorTrial(orchestrator, objectMapper);
    }

    @Test
    @DisplayName("execute delegates to orchestrator and returns serialized outcome")
    void execute_happyPath_delegates() throws Exception {
        when(orchestrator.runTrial(any(TrialRequest.class)))
                .thenReturn(new SimulationOutcome("trial-1", "sess-1", 6, "task_completed",
                        List.of()));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("scenarioId", "scen-1");
        input.put("persona", "销售经理急性子 — 短句催进度");
        input.put("candidateAgentVersionId", "ver-42");
        input.put("candidateSurfaceType", "prompt");
        input.put("maxTurns", 8);

        SkillResult result = tool.execute(input, new SkillContext());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("\"trialId\":\"trial-1\"");
        assertThat(result.getOutput()).contains("\"sessionId\":\"sess-1\"");
        assertThat(result.getOutput()).contains("\"turnsUsed\":6");
        assertThat(result.getOutput()).contains("\"terminationReason\":\"task_completed\"");

        ArgumentCaptor<TrialRequest> captured = ArgumentCaptor.forClass(TrialRequest.class);
        verify(orchestrator).runTrial(captured.capture());
        TrialRequest req = captured.getValue();
        assertThat(req.scenarioId()).isEqualTo("scen-1");
        assertThat(req.candidateAgentVersionId()).isEqualTo("ver-42");
        assertThat(req.candidateSurfaceType()).isEqualTo("prompt");
        assertThat(req.maxTurns()).isEqualTo(8);
    }

    @Test
    @DisplayName("execute returns validation error when scenarioId missing")
    void execute_missingScenarioId_validationError() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("persona", "DBA 老手");

        SkillResult result = tool.execute(input, new SkillContext());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("scenarioId");
        verify(orchestrator, never()).runTrial(any());
    }

    @Test
    @DisplayName("execute returns validation error when persona missing")
    void execute_missingPersona_validationError() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("scenarioId", "scen-1");

        SkillResult result = tool.execute(input, new SkillContext());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("persona");
        verify(orchestrator, never()).runTrial(any());
    }

    @Test
    @DisplayName("execute rejects partial candidate spec (versionId without surfaceType)")
    void execute_partialCandidate_validationError() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("scenarioId", "scen-1");
        input.put("persona", "实习生小白");
        input.put("candidateAgentVersionId", "ver-1");
        // candidateSurfaceType omitted

        SkillResult result = tool.execute(input, new SkillContext());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("candidateSurfaceType");
        verify(orchestrator, never()).runTrial(any());
    }

    @Test
    @DisplayName("execute accepts persona-only kickoff (no candidate fields)")
    void execute_noCandidate_runsBaseline() throws Exception {
        when(orchestrator.runTrial(any(TrialRequest.class)))
                .thenReturn(new SimulationOutcome("trial-2", "sess-2", 4, "max_turns",
                        List.of("超 max_turns")));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("scenarioId", "scen-2");
        input.put("persona", "CEO 高高在上");

        SkillResult result = tool.execute(input, new SkillContext());

        assertThat(result.isSuccess()).isTrue();
        ArgumentCaptor<TrialRequest> captured = ArgumentCaptor.forClass(TrialRequest.class);
        verify(orchestrator).runTrial(captured.capture());
        TrialRequest req = captured.getValue();
        assertThat(req.candidateAgentVersionId()).isNull();
        assertThat(req.candidateSurfaceType()).isNull();
        assertThat(req.maxTurns()).isNull();   // orchestrator falls back to yaml default
    }

    @Test
    @DisplayName("execute rejects behavior_rule surface at validation (V5 known limitation)")
    void execute_behaviorRuleSurface_rejected() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("scenarioId", "scen-1");
        input.put("persona", "DBA 老手");
        input.put("candidateAgentVersionId", "rule-v1");
        input.put("candidateSurfaceType", "behavior_rule");

        SkillResult result = tool.execute(input, new SkillContext());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("behavior_rule");
        assertThat(result.getError()).contains("V5.1 backlog");
        verify(orchestrator, never()).runTrial(any());
    }

    @Test
    @DisplayName("execute propagates orchestrator IllegalArgumentException as validation error")
    void execute_orchestratorIllegalArg_propagates() {
        when(orchestrator.runTrial(any(TrialRequest.class)))
                .thenThrow(new IllegalArgumentException("Scenario not found: scen-x"));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("scenarioId", "scen-x");
        input.put("persona", "数据分析师细心");

        SkillResult result = tool.execute(input, new SkillContext());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("Scenario not found");
    }
}
