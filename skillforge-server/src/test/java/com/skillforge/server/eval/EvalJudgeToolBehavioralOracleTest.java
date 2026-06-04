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
 * BC-M2b: the generalized {@code tool_error_absence} behavioral oracle is
 * deterministic and makes NO LLM call. A run PASSES (outcome=100) iff it BOTH
 * made at least one tool call (did real work, not a no-op / refusal) AND did not
 * reproduce the failure signature on the harvested {@code filePath}; anything
 * else scores 0 (FAIL).
 *
 * <p>The earlier BC-M2a engagement gate ("the target tool succeeded on filePath")
 * is intentionally gone — it was unsatisfiable for tools whose correct behavior
 * never touches the harvested file. {@code filePath} now only scopes the error
 * match. The "do nothing" hole is covered by running the benchmark subset in the
 * same A/B plus this generic ≥1-tool-call backstop.
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

    private static ToolCallRecord editFailure(String filePath, String output) {
        return new ToolCallRecord("Edit", Map.of("file_path", filePath), output, false, 5, 0);
    }

    private static ToolCallRecord readSuccess(String filePath) {
        return new ToolCallRecord("Read", Map.of("file_path", filePath),
                "file contents", true, 5, 0);
    }

    @Test
    @DisplayName("recurrence (failed Edit with signature) → outcome 0 / FAIL, no LLM call")
    void behavioralOracle_signaturePresent_failsDeterministically() {
        ScenarioRunResult run = runWith(List.of(editFailure("/tmp/eval/x/a.txt", "old_string not found in file")));

        EvalJudgeOutput out = judgeTool.judge(behavioralScenario(), run);

        assertThat(out.getOutcomeScore()).isEqualTo(0.0);
        assertThat(out.isPass()).isFalse();
        assertThat(run.getStatus()).isEqualTo("FAIL");
        verifyNoInteractions(llmProviderFactory);
    }

    @Test
    @DisplayName("≥1 tool call + no signature → outcome 100 / PASS, no LLM call")
    void behavioralOracle_engagedNoSignature_passesDeterministically() {
        ScenarioRunResult run = runWith(List.of(editSuccess("/tmp/eval/x/a.txt")));

        EvalJudgeOutput out = judgeTool.judge(behavioralScenario(), run);

        assertThat(out.getOutcomeScore()).isEqualTo(100.0);
        assertThat(out.isPass()).isTrue();
        assertThat(run.getStatus()).isEqualTo("PASS");
        verifyNoInteractions(llmProviderFactory);
    }

    @Test
    @DisplayName("BC-M2b soundness: no tool calls at all → no engagement → outcome 0 / FAIL")
    void behavioralOracle_noToolCalls_fails() {
        ScenarioRunResult run = runWith(List.of());

        EvalJudgeOutput out = judgeTool.judge(behavioralScenario(), run);

        assertThat(out.getOutcomeScore()).isEqualTo(0.0);
        assertThat(out.isPass()).isFalse();
        assertThat(run.getStatus()).isEqualTo("FAIL");
        verifyNoInteractions(llmProviderFactory);
    }

    @Test
    @DisplayName("BC-M2b: ≥1 tool call (target tool NOT invoked) + no recurrence → PASS")
    void behavioralOracle_targetToolNotCalled_butEngaged_passes() {
        // The correct fix for some tools never touches the harvested file — only a
        // Read happens here, no Edit at all. The run still did real work (≥1 call)
        // and did not reproduce the signature → PASS.
        ScenarioRunResult run = runWith(List.of(readSuccess("/tmp/eval/x/a.txt")));

        EvalJudgeOutput out = judgeTool.judge(behavioralScenario(), run);

        assertThat(out.getOutcomeScore()).isEqualTo(100.0);
        assertThat(out.isPass()).isTrue();
    }

    @Test
    @DisplayName("a different tool failing with the same text is not recurrence → engaged → PASS")
    void behavioralOracle_otherToolFailure_doesNotRecur() {
        // A Write failure carrying the same error text must NOT count as Edit
        // recurrence; the run still made a tool call → PASS.
        ScenarioRunResult run = runWith(List.of(
                new ToolCallRecord("Write", Map.of("file_path", "/tmp/eval/x/a.txt"),
                        "old_string not found in file", false, 5, 0)));

        EvalJudgeOutput out = judgeTool.judge(behavioralScenario(), run);

        assertThat(out.getOutcomeScore()).isEqualTo(100.0);
        assertThat(out.isPass()).isTrue();
    }

    @Test
    @DisplayName("path-scope: signature recurs on the harvested file → FAIL")
    void behavioralOracle_pathScope_recurOnTarget_fails() {
        EvalScenario scenario = behavioralScenario(
                "{\"tool\":\"Edit\",\"errorSignature\":\"old_string not found\","
                + "\"passWhen\":\"no_match\",\"filePath\":\"src/Target.java\"}");
        // Runtime file_path = sandboxRoot + "/" + relative fixture path.
        ScenarioRunResult run = runWith(List.of(
                editFailure("/tmp/eval/run-abc/src/Target.java", "old_string not found in file")));

        EvalJudgeOutput out = judgeTool.judge(scenario, run);

        assertThat(out.getOutcomeScore()).isEqualTo(0.0);
        assertThat(out.isPass()).isFalse();
    }

    @Test
    @DisplayName("path-scope: same signature on a DIFFERENT file → not counted as recurrence → PASS")
    void behavioralOracle_pathScope_recurOnOtherFile_passes() {
        EvalScenario scenario = behavioralScenario(
                "{\"tool\":\"Edit\",\"errorSignature\":\"old_string not found\","
                + "\"passWhen\":\"no_match\",\"filePath\":\"src/Target.java\"}");
        // The failure reproduced on a different file — not the harvested target —
        // so it does not count as a recurrence of THIS failure. The run still made
        // a tool call → PASS.
        ScenarioRunResult run = runWith(List.of(
                editFailure("/tmp/eval/run-abc/src/Other.java", "old_string not found in file")));

        EvalJudgeOutput out = judgeTool.judge(scenario, run);

        assertThat(out.getOutcomeScore()).isEqualTo(100.0);
        assertThat(out.isPass()).isTrue();
    }
}
