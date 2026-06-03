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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * BC-M1: the {@code tool_error_absence} behavioral oracle is deterministic and
 * makes NO LLM call. A run whose tool calls contain the failure signature scores
 * outcome=0 (FAIL); a clean run (no signature) scores outcome=100 (PASS). The
 * PASS branch is exercised with a synthetic no-error run — no fix candidate.
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
        EvalScenario scenario = new EvalScenario();
        scenario.setId("badcase-1");
        scenario.setName("badcase-1");
        scenario.setTask("edit the file");
        scenario.setMaxLoops(10);
        scenario.setPerformanceThresholdMs(30000);
        EvalScenario.ScenarioOracle oracle = new EvalScenario.ScenarioOracle();
        oracle.setType("tool_error_absence");
        oracle.setExpected("{\"tool\":\"Edit\",\"errorSignature\":\"old_string not found\",\"passWhen\":\"no_match\"}");
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

    @Test
    @DisplayName("signature recurs → outcome 0 / FAIL, no LLM call")
    void behavioralOracle_signaturePresent_failsDeterministically() {
        ScenarioRunResult run = runWith(List.of(
                new ToolCallRecord("Edit", null, "old_string not found in file", false, 5, 0)));

        EvalJudgeOutput out = judgeTool.judge(behavioralScenario(), run);

        assertThat(out.getOutcomeScore()).isEqualTo(0.0);
        assertThat(out.isPass()).isFalse();
        assertThat(run.getStatus()).isEqualTo("FAIL");
        verifyNoInteractions(llmProviderFactory);
    }

    @Test
    @DisplayName("clean run (no signature) → outcome 100 / PASS, no LLM call (synthetic no-error run)")
    void behavioralOracle_signatureAbsent_passesDeterministically() {
        ScenarioRunResult run = runWith(List.of(
                new ToolCallRecord("Edit", null, "Replaced 1 occurrence in /tmp/eval/a.txt", true, 5, 0)));

        EvalJudgeOutput out = judgeTool.judge(behavioralScenario(), run);

        assertThat(out.getOutcomeScore()).isEqualTo(100.0);
        assertThat(out.isPass()).isTrue();
        assertThat(run.getStatus()).isEqualTo("PASS");
        verifyNoInteractions(llmProviderFactory);
    }

    @Test
    @DisplayName("no tool calls at all → treated as no recurrence → PASS")
    void behavioralOracle_noToolCalls_passes() {
        ScenarioRunResult run = runWith(List.of());

        EvalJudgeOutput out = judgeTool.judge(behavioralScenario(), run);

        assertThat(out.getOutcomeScore()).isEqualTo(100.0);
        assertThat(out.isPass()).isTrue();
        verifyNoInteractions(llmProviderFactory);
    }

    @Test
    @DisplayName("a different tool failing with the same text does not count as recurrence")
    void behavioralOracle_otherToolFailure_doesNotRecur() {
        ScenarioRunResult run = runWith(List.of(
                new ToolCallRecord("Write", null, "old_string not found in file", false, 5, 0)));

        EvalJudgeOutput out = judgeTool.judge(behavioralScenario(), run);

        assertThat(out.getOutcomeScore()).isEqualTo(100.0);
        assertThat(out.isPass()).isTrue();
    }
}
