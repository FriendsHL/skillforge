package com.skillforge.server.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.ToolCallRecord;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.server.eval.attribution.AttributionEngine;
import com.skillforge.server.eval.attribution.FailureAttribution;
import com.skillforge.server.eval.scenario.EvalScenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * BC-M2a: the {@code tool_error_absence} behavioral oracle (v2) is deterministic
 * and makes NO LLM call. A run PASSES (outcome=100) iff it BOTH engaged the target
 * tool successfully AND did not reproduce the failure signature; anything else —
 * including a do-nothing run that never engaged the tool, or a run that only
 * FAILED the target tool — scores 0 (FAIL). Optional {@code filePath} path-scopes
 * the engagement check to the harvested target file.
 */
@ExtendWith(MockitoExtension.class)
class EvalJudgeToolBehavioralOracleTest {

    @Mock
    private LlmProviderFactory llmProviderFactory;
    @Mock
    private AttributionEngine attributionEngine;
    @Mock
    private LlmProperties llmProperties;

    private EvalJudgeTool judgeTool;

    @BeforeEach
    void setUp() {
        when(llmProperties.getDefaultProvider()).thenReturn("claude");
        lenient().when(attributionEngine.compute(any())).thenReturn(FailureAttribution.NONE);
        judgeTool = new EvalJudgeTool(llmProviderFactory, attributionEngine,
                new ObjectMapper(), llmProperties, 0.7);
    }

    private EvalScenario behavioralScenario() {
        return behavioralScenario(
                "{\"tool\":\"Edit\",\"errorSignature\":\"old_string not found\",\"passWhen\":\"no_match\"}");
    }

    private EvalScenario behavioralScenario(String oracleExpected) {
        EvalScenario scenario = new EvalScenario();
        scenario.setId("badcase-1");
        scenario.setName("badcase-1");
        scenario.setTask("edit the file");
        scenario.setMaxLoops(10);
        scenario.setPerformanceThresholdMs(30000);
        EvalScenario.ScenarioOracle oracle = new EvalScenario.ScenarioOracle();
        oracle.setType("tool_error_absence");
        oracle.setExpected(oracleExpected);
        scenario.setOracle(oracle);
        return scenario;
    }

    private ScenarioRunResult runWith(List<ToolCallRecord> toolCalls) {
        ScenarioRunResult r = new ScenarioRunResult();
        r.setScenarioId("badcase-1");
        r.setStatus("PENDING_JUDGE");
        r.setLoopCount(1);
        r.setExecutionTimeMs(100);
        r.applyToolCallSignals(toolCalls);
        return r;
    }

    private static ToolCallRecord editSuccess(String filePath) {
        return new ToolCallRecord("Edit", filePath == null ? null : Map.of("file_path", filePath),
                "Replaced 1 occurrence", true, 5, 0);
    }

    private static ToolCallRecord editFailure(String output) {
        return new ToolCallRecord("Edit", Map.of("file_path", "/tmp/eval/x/a.txt"), output, false, 5, 0);
    }

    @Test
    @DisplayName("recurrence (failed Edit with signature) → outcome 0 / FAIL, no LLM call")
    void behavioralOracle_signaturePresent_failsDeterministically() {
        ScenarioRunResult run = runWith(List.of(editFailure("old_string not found in file")));

        EvalJudgeOutput out = judgeTool.judge(behavioralScenario(), run);

        assertThat(out.getOutcomeScore()).isEqualTo(0.0);
        assertThat(out.isPass()).isFalse();
        assertThat(run.getStatus()).isEqualTo("FAIL");
        verifyNoInteractions(llmProviderFactory);
    }

    @Test
    @DisplayName("engaged target tool successfully + no signature → outcome 100 / PASS, no LLM call")
    void behavioralOracle_engagedNoSignature_passesDeterministically() {
        ScenarioRunResult run = runWith(List.of(editSuccess("/tmp/eval/x/a.txt")));

        EvalJudgeOutput out = judgeTool.judge(behavioralScenario(), run);

        assertThat(out.getOutcomeScore()).isEqualTo(100.0);
        assertThat(out.isPass()).isTrue();
        assertThat(run.getStatus()).isEqualTo("PASS");
        verifyNoInteractions(llmProviderFactory);
    }

    @Test
    @DisplayName("BC-M2a soundness: no tool calls at all → no engagement → outcome 0 / FAIL")
    void behavioralOracle_noToolCalls_fails() {
        ScenarioRunResult run = runWith(List.of());

        EvalJudgeOutput out = judgeTool.judge(behavioralScenario(), run);

        assertThat(out.getOutcomeScore()).isEqualTo(0.0);
        assertThat(out.isPass()).isFalse();
        assertThat(run.getStatus()).isEqualTo("FAIL");
        verifyNoInteractions(llmProviderFactory);
    }

    @Test
    @DisplayName("only a FAILED target call (no signature) → no successful engagement → outcome 0 / FAIL")
    void behavioralOracle_failedOnlyNoSignature_fails() {
        // Edit was attempted but failed with an UNRELATED error (not the tracked
        // signature) — there is no successful target-tool operation, so engagement
        // is not satisfied and the run must not pass.
        ScenarioRunResult run = runWith(List.of(editFailure("permission denied")));

        EvalJudgeOutput out = judgeTool.judge(behavioralScenario(), run);

        assertThat(out.getOutcomeScore()).isEqualTo(0.0);
        assertThat(out.isPass()).isFalse();
    }

    @Test
    @DisplayName("a different tool failing with the same text is not recurrence (engaged target → PASS)")
    void behavioralOracle_otherToolFailure_doesNotRecur() {
        // A Write failure carrying the same error text must NOT count as Edit
        // recurrence; with a successful Edit also present, engagement holds → PASS.
        ScenarioRunResult run = runWith(List.of(
                new ToolCallRecord("Write", null, "old_string not found in file", false, 5, 0),
                editSuccess("/tmp/eval/x/a.txt")));

        EvalJudgeOutput out = judgeTool.judge(behavioralScenario(), run);

        assertThat(out.getOutcomeScore()).isEqualTo(100.0);
        assertThat(out.isPass()).isTrue();
    }

    @Test
    @DisplayName("path-scope: successful Edit on a DIFFERENT file → not engaged → FAIL")
    void behavioralOracle_pathScope_wrongFile_fails() {
        EvalScenario scenario = behavioralScenario(
                "{\"tool\":\"Edit\",\"errorSignature\":\"old_string not found\","
                + "\"passWhen\":\"no_match\",\"filePath\":\"src/Target.java\"}");
        ScenarioRunResult run = runWith(List.of(editSuccess("/tmp/eval/run/src/Other.java")));

        EvalJudgeOutput out = judgeTool.judge(scenario, run);

        assertThat(out.getOutcomeScore()).isEqualTo(0.0);
        assertThat(out.isPass()).isFalse();
    }

    @Test
    @DisplayName("path-scope: successful Edit on the target file (sandbox-rebased) → engaged → PASS")
    void behavioralOracle_pathScope_targetFile_passes() {
        EvalScenario scenario = behavioralScenario(
                "{\"tool\":\"Edit\",\"errorSignature\":\"old_string not found\","
                + "\"passWhen\":\"no_match\",\"filePath\":\"src/Target.java\"}");
        // Runtime file_path = sandboxRoot + "/" + relative fixture path.
        ScenarioRunResult run = runWith(List.of(editSuccess("/tmp/eval/run-abc/src/Target.java")));

        EvalJudgeOutput out = judgeTool.judge(scenario, run);

        assertThat(out.getOutcomeScore()).isEqualTo(100.0);
        assertThat(out.isPass()).isTrue();
    }
}
