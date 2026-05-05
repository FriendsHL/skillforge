package com.skillforge.server.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.LlmRequest;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.server.eval.attribution.AttributionEngine;
import com.skillforge.server.eval.attribution.EvalSignals;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * EVAL-V2 M2: tests for {@link EvalJudgeTool#judgeMultiTurnConversation}.
 *
 * <p>Locks happy path (composite computed from per-turn + overall with default
 * 0.7 weight), error short-circuit, and JSON parse fallback.
 */
@ExtendWith(MockitoExtension.class)
class EvalJudgeToolMultiTurnTest {

    @Mock private LlmProviderFactory llmProviderFactory;
    @Mock private LlmProvider llmProvider;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AttributionEngine attributionEngine = new AttributionEngine();
    private EvalJudgeTool tool;

    @BeforeEach
    void setUp() {
        LlmProperties props = new LlmProperties();
        props.setDefaultProvider("claude");
        // Default multi-turn weight 0.7 — matches @Value default in production.
        tool = new EvalJudgeTool(llmProviderFactory, attributionEngine, objectMapper, props, 0.7);
        lenient().when(llmProviderFactory.getProvider(anyString())).thenReturn(llmProvider);
    }

    private EvalScenario scenario(String id, String task, String expected) {
        EvalScenario s = new EvalScenario();
        s.setId(id);
        s.setName(id);
        s.setTask(task);
        s.setMaxLoops(10);
        EvalScenario.ScenarioOracle oracle = new EvalScenario.ScenarioOracle();
        oracle.setType("llm_judge");
        oracle.setExpected(expected);
        s.setOracle(oracle);
        s.setConversationTurns(List.of(
                new EvalScenario.ConversationTurn("user", "first"),
                new EvalScenario.ConversationTurn("assistant", EvalScenario.ASSISTANT_PLACEHOLDER),
                new EvalScenario.ConversationTurn("user", "second")
        ));
        return s;
    }

    private static MultiTurnTranscript transcript() {
        MultiTurnTranscript t = new MultiTurnTranscript();
        t.add("user", "first");
        t.add("assistant", "ok, what's wrong?");
        t.add("user", "second");
        t.add("assistant", "here's a fix");
        return t;
    }

    private static ScenarioRunResult runResult(String status) {
        ScenarioRunResult r = new ScenarioRunResult();
        r.setScenarioId("sc-mt");
        r.setStatus(status);
        r.setLoopCount(4);
        r.setExecutionTimeMs(1234L);
        return r;
    }

    @Test
    @DisplayName("judgeMultiTurnConversation: parses JSON output and computes weighted composite")
    void judge_happyPath() {
        LlmResponse resp = new LlmResponse();
        // overallScore=80, perTurnScores avg=60 → composite = 0.7*80 + 0.3*60 = 74
        resp.setContent("""
                {
                  "perTurnScores": [
                    {"turnIndex": 0, "score": 50, "comment": "ok"},
                    {"turnIndex": 1, "score": 70, "comment": "good"}
                  ],
                  "overallScore": 80,
                  "attribution": "NONE",
                  "rationale": "Conversation resolved the user's question."
                }
                """);
        when(llmProvider.chat(any(LlmRequest.class))).thenReturn(resp);

        EvalScenario sc = scenario("sc-mt", "task summary", "expected behavior");
        ScenarioRunResult run = runResult("PENDING_JUDGE");

        EvalJudgeMultiTurnOutput out = tool.judgeMultiTurnConversation(sc, run, transcript());

        assertThat(out.getOverallScore()).isEqualTo(80.0);
        assertThat(out.getPerTurnScores()).hasSize(2);
        assertThat(out.getCompositeScore()).isEqualTo(0.7 * 80 + 0.3 * ((50 + 70) / 2.0));
        assertThat(out.getAttribution()).isEqualTo(FailureAttribution.NONE);
        assertThat(out.isPass()).isTrue();
        assertThat(run.getStatus()).isEqualTo("PASS");
        assertThat(run.getOracleScore()).isEqualTo(out.getCompositeScore());
    }

    @Test
    @DisplayName("judgeMultiTurnConversation: ERROR run short-circuits to 0 + attribution from signals")
    void judge_errorShortCircuit() {
        EvalScenario sc = scenario("sc-mt", "task", "expected");
        ScenarioRunResult run = runResult("ERROR");
        run.setEngineThrewException(true);

        EvalJudgeMultiTurnOutput out = tool.judgeMultiTurnConversation(sc, run, transcript());

        assertThat(out.getCompositeScore()).isEqualTo(0.0);
        assertThat(out.isPass()).isFalse();
        // ERROR + engineThrewException → VETO_EXCEPTION via attribution engine
        assertThat(out.getAttribution()).isNotNull();
    }

    @Test
    @DisplayName("judgeMultiTurnConversation: malformed JSON → 0 score + FAIL status, no crash")
    void judge_malformedJson_degrades() {
        LlmResponse resp = new LlmResponse();
        resp.setContent("not even json");
        when(llmProvider.chat(any(LlmRequest.class))).thenReturn(resp);

        EvalScenario sc = scenario("sc-mt", "task", "expected");
        ScenarioRunResult run = runResult("PENDING_JUDGE");

        EvalJudgeMultiTurnOutput out = tool.judgeMultiTurnConversation(sc, run, transcript());

        assertThat(out.getCompositeScore()).isEqualTo(0.0);
        assertThat(out.isPass()).isFalse();
        assertThat(run.getStatus()).isEqualTo("FAIL");
        assertThat(out.getRationale()).contains("not valid JSON");
    }

    @Test
    @DisplayName("judgeMultiTurnConversation: composite stays in [0, 100]")
    void judge_clampedScores() {
        LlmResponse resp = new LlmResponse();
        // Out-of-range scores should be clamped, not propagate to composite.
        resp.setContent("""
                {"perTurnScores": [{"turnIndex": 0, "score": 200}], "overallScore": 999, "attribution": "NONE", "rationale": ""}
                """);
        when(llmProvider.chat(any(LlmRequest.class))).thenReturn(resp);

        EvalJudgeMultiTurnOutput out = tool.judgeMultiTurnConversation(
                scenario("sc-mt", "task", "expected"), runResult("PENDING_JUDGE"), transcript());

        assertThat(out.getCompositeScore()).isLessThanOrEqualTo(100.0);
        assertThat(out.getOverallScore()).isLessThanOrEqualTo(100.0);
        assertThat(out.getCompositeScore()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    @DisplayName("judgeMultiTurnConversation: TOOL_FAILURE alias maps to SKILL_EXECUTION_FAILURE")
    void judge_attributionAlias() {
        LlmResponse resp = new LlmResponse();
        resp.setContent("""
                {"perTurnScores": [{"turnIndex": 0, "score": 30}], "overallScore": 25, "attribution": "TOOL_FAILURE", "rationale": ""}
                """);
        when(llmProvider.chat(any(LlmRequest.class))).thenReturn(resp);

        EvalJudgeMultiTurnOutput out = tool.judgeMultiTurnConversation(
                scenario("sc-mt", "task", "expected"), runResult("PENDING_JUDGE"), transcript());

        assertThat(out.getAttribution()).isEqualTo(FailureAttribution.SKILL_EXECUTION_FAILURE);
    }
}
