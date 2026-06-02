package com.skillforge.server.tool.evolve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.flywheel.run.FlywheelRunEntity;
import com.skillforge.server.flywheel.run.FlywheelRunService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AUTOEVOLVE-AGENT-FLYWHEEL Module C (RecordIteration) — {@link RecordIterationTool}:
 * appends an evolve_iteration step with the iteration payload in step_output_json,
 * validates the run is an evolve run, and field validation.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RecordIterationTool")
class RecordIterationToolTest {

    @Mock private FlywheelRunService flywheelRunService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RecordIterationTool tool;

    @BeforeEach
    void setUp() {
        tool = new RecordIterationTool(flywheelRunService, objectMapper);
    }

    private SkillResult run(Map<String, Object> input) {
        return tool.execute(input, new SkillContext("/tmp", "sess", 7L));
    }

    private static FlywheelRunEntity evolveRun(String id) {
        FlywheelRunEntity run = new FlywheelRunEntity();
        run.setId(id);
        run.setLoopKind(FlywheelRunEntity.LOOP_KIND_EVOLVE);
        return run;
    }

    @Test
    @DisplayName("records a kept iteration with full payload into step_output_json")
    void recordsIteration_fullPayload() {
        when(flywheelRunService.findById("evolve-1")).thenReturn(Optional.of(evolveRun("evolve-1")));
        when(flywheelRunService.appendEvolveIterationStep(eq("evolve-1"), eq(3), any(JsonNode.class)))
                .thenReturn("step-99");

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("evolveRunId", "evolve-1");
        input.put("iteration", 3);
        input.put("surface", "prompt");
        input.put("changeDesc", "tightened the system prompt");
        input.put("candidateId", "prompt-v2");
        input.put("baselineScore", 0.41);
        input.put("candidateScore", 0.58);
        input.put("delta", 0.17);
        input.put("kept", true);
        input.put("abRunId", "ab-7");

        SkillResult result = run(input);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("\"stepId\":\"step-99\"");

        ArgumentCaptor<JsonNode> payload = ArgumentCaptor.forClass(JsonNode.class);
        verify(flywheelRunService).appendEvolveIterationStep(eq("evolve-1"), eq(3), payload.capture());
        JsonNode p = payload.getValue();
        assertThat(p.get("iteration").asInt()).isEqualTo(3);
        assertThat(p.get("surface").asText()).isEqualTo("prompt");
        assertThat(p.get("changeDesc").asText()).isEqualTo("tightened the system prompt");
        assertThat(p.get("candidateId").asText()).isEqualTo("prompt-v2");
        assertThat(p.get("baselineScore").asDouble()).isEqualTo(0.41);
        assertThat(p.get("candidateScore").asDouble()).isEqualTo(0.58);
        assertThat(p.get("delta").asDouble()).isEqualTo(0.17);
        assertThat(p.get("kept").asBoolean()).isTrue();
        assertThat(p.get("abRunId").asText()).isEqualTo("ab-7");
    }

    @Test
    @DisplayName("records a not-kept iteration; optional scores omitted are fine")
    void recordsIteration_notKept_minimal() {
        when(flywheelRunService.findById("evolve-1")).thenReturn(Optional.of(evolveRun("evolve-1")));
        when(flywheelRunService.appendEvolveIterationStep(eq("evolve-1"), eq(1), any(JsonNode.class)))
                .thenReturn("step-1");

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("evolveRunId", "evolve-1");
        input.put("iteration", 1);
        input.put("surface", "skill");
        input.put("changeDesc", "new skill draft");
        input.put("candidateId", "draft-1");
        input.put("kept", false);

        SkillResult result = run(input);

        assertThat(result.isSuccess()).isTrue();
        ArgumentCaptor<JsonNode> payload = ArgumentCaptor.forClass(JsonNode.class);
        verify(flywheelRunService).appendEvolveIterationStep(eq("evolve-1"), eq(1), payload.capture());
        JsonNode p = payload.getValue();
        assertThat(p.get("kept").asBoolean()).isFalse();
        assertThat(p.has("baselineScore")).isFalse();
        assertThat(p.has("abRunId")).isFalse();
    }

    @Test
    @DisplayName("§9 line A #3: records a surface=agent iteration with candidateBundle sidecar + global scores")
    void recordsIteration_agentSurface_withBundleSidecar() {
        when(flywheelRunService.findById("evolve-1")).thenReturn(Optional.of(evolveRun("evolve-1")));
        when(flywheelRunService.appendEvolveIterationStep(eq("evolve-1"), eq(4), any(JsonNode.class)))
                .thenReturn("step-agent");

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("evolveRunId", "evolve-1");
        input.put("iteration", 4);
        input.put("surface", "agent");                 // Phase 3 opens this
        input.put("changeDesc", "prompt+rule combined bundle");
        input.put("candidateId", "ab-42");             // main pointer for the bundle
        input.put("candidateBundle", Map.of("promptVersionId", "pv-9", "behaviorRuleVersionId", "brv-3"));
        input.put("baselineScore", 50.0);
        input.put("candidateScore", 66.0);
        input.put("delta", 16.0);
        input.put("kept", true);

        SkillResult result = run(input);

        assertThat(result.isSuccess()).isTrue();
        ArgumentCaptor<JsonNode> payload = ArgumentCaptor.forClass(JsonNode.class);
        verify(flywheelRunService).appendEvolveIterationStep(eq("evolve-1"), eq(4), payload.capture());
        JsonNode p = payload.getValue();
        assertThat(p.get("surface").asText()).isEqualTo("agent");
        // chart-facing fields intact
        assertThat(p.get("candidateId").asText()).isEqualTo("ab-42");
        assertThat(p.get("baselineScore").asDouble()).isEqualTo(50.0);
        assertThat(p.get("candidateScore").asDouble()).isEqualTo(66.0);
        assertThat(p.get("delta").asDouble()).isEqualTo(16.0);
        // bundle sidecar recorded as structured json
        assertThat(p.get("candidateBundle").get("promptVersionId").asText()).isEqualTo("pv-9");
        assertThat(p.get("candidateBundle").get("behaviorRuleVersionId").asText()).isEqualTo("brv-3");
    }

    @Test
    @DisplayName("run not found → validation error, no step appended")
    void runNotFound_validationError() {
        when(flywheelRunService.findById("missing")).thenReturn(Optional.empty());

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("evolveRunId", "missing");
        input.put("iteration", 1);
        input.put("surface", "prompt");
        input.put("changeDesc", "x");
        input.put("candidateId", "c");
        input.put("kept", true);

        SkillResult result = run(input);

        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        assertThat(result.getError()).contains("not found");
        verify(flywheelRunService, never()).appendEvolveIterationStep(any(), anyInt(), any());
    }

    @Test
    @DisplayName("run is not an evolve run → validation error")
    void notEvolveRun_validationError() {
        FlywheelRunEntity optReport = new FlywheelRunEntity();
        optReport.setId("opt-1");
        optReport.setLoopKind(FlywheelRunEntity.LOOP_KIND_OPT_REPORT);
        when(flywheelRunService.findById("opt-1")).thenReturn(Optional.of(optReport));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("evolveRunId", "opt-1");
        input.put("iteration", 1);
        input.put("surface", "prompt");
        input.put("changeDesc", "x");
        input.put("candidateId", "c");
        input.put("kept", true);

        SkillResult result = run(input);

        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        assertThat(result.getError()).contains("not an evolve run");
        verify(flywheelRunService, never()).appendEvolveIterationStep(any(), anyInt(), any());
    }

    @Test
    @DisplayName("missing kept → validation error")
    void missingKept_validationError() {
        when(flywheelRunService.findById("evolve-1")).thenReturn(Optional.of(evolveRun("evolve-1")));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("evolveRunId", "evolve-1");
        input.put("iteration", 1);
        input.put("surface", "prompt");
        input.put("changeDesc", "x");
        input.put("candidateId", "c");

        SkillResult result = run(input);

        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        assertThat(result.getError()).contains("kept is required");
    }

    @Test
    @DisplayName("iteration < 1 → validation error")
    void iterationBelowOne_validationError() {
        when(flywheelRunService.findById("evolve-1")).thenReturn(Optional.of(evolveRun("evolve-1")));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("evolveRunId", "evolve-1");
        input.put("iteration", 0);
        input.put("surface", "prompt");
        input.put("changeDesc", "x");
        input.put("candidateId", "c");
        input.put("kept", true);

        SkillResult result = run(input);

        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        assertThat(result.getError()).contains("iteration");
    }

    @Test
    @DisplayName("tool metadata: name, not read-only")
    void metadata() {
        assertThat(tool.getName()).isEqualTo("RecordIteration");
        assertThat(tool.isReadOnly()).isFalse();
    }
}
