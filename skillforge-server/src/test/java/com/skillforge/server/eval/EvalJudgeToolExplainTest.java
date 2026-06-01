package com.skillforge.server.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.LlmRequest;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.server.eval.attribution.AttributionEngine;
import com.skillforge.server.eval.scenario.EvalScenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AUTOEVOLVE reflection (option B): tests for the {@code explain} overload of
 * {@link EvalJudgeTool#judge(EvalScenario, ScenarioRunResult, boolean)}.
 *
 * <p>Load-bearing properties:
 * <ul>
 *   <li><b>Additive</b>: explain=true sets a natural-language rationale but the
 *       composite score is byte-identical to the 2-arg / explain=false path —
 *       deterministic oracles and the hill-climb's cached-baseline comparison are
 *       never perturbed.</li>
 *   <li><b>explain=false</b>: no rationale, no extra LLM call (the non-evolve
 *       callers keep the cheap path).</li>
 *   <li><b>Failure never hurts the score</b>: a null provider (or a failed reason
 *       call) leaves the rationale null and the score intact.</li>
 *   <li><b>ERROR/TIMEOUT</b>: cheap templated rationale, no LLM call.</li>
 * </ul>
 *
 * <p>All scenarios use a deterministic {@code contains} oracle (full match →
 * outcomeScore=100 → composite &gt;55, outside the [30,55] meta-judge fuzzy zone),
 * so the ONLY LLM call in play is the option-B rationale call.
 */
@ExtendWith(MockitoExtension.class)
class EvalJudgeToolExplainTest {

    @Mock private LlmProviderFactory llmProviderFactory;
    @Mock private LlmProvider llmProvider;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AttributionEngine attributionEngine = new AttributionEngine();
    private EvalJudgeTool tool;

    @BeforeEach
    void setUp() {
        LlmProperties props = new LlmProperties();
        props.setDefaultProvider("test");
        tool = new EvalJudgeTool(llmProviderFactory, attributionEngine, objectMapper, props, 0.7);
    }

    private EvalScenario scenario() {
        EvalScenario s = new EvalScenario();
        s.setId("sc");
        s.setName("sc");
        s.setTask("find the keyword");
        s.setMaxLoops(10);
        s.setPerformanceThresholdMs(10_000);
        EvalScenario.ScenarioOracle oracle = new EvalScenario.ScenarioOracle();
        oracle.setType("contains");
        oracle.setExpected("NEEDLE");
        s.setOracle(oracle);
        return s;
    }

    /** Deterministic full-match run → outcomeScore=100, composite >55 (non-fuzzy). */
    private ScenarioRunResult passingRun() {
        ScenarioRunResult r = new ScenarioRunResult();
        r.setScenarioId("sc");
        r.setStatus("PENDING_JUDGE");
        r.setAgentFinalOutput("here is the NEEDLE in the output");
        r.setLoopCount(2);
        r.setExecutionTimeMs(100L);
        return r;
    }

    private void stubReason(String text) {
        LlmResponse resp = new LlmResponse();
        resp.setContent(text);
        when(llmProviderFactory.getProvider(anyString())).thenReturn(llmProvider);
        when(llmProvider.chat(any(LlmRequest.class))).thenReturn(resp);
    }

    @Test
    @DisplayName("explain=true: sets rationale from the LLM; composite score is byte-identical to explain=false (additive)")
    void explainTrue_setsRationale_scoreIdenticalToPlain() {
        stubReason("Output contained the required keyword, matching the expected criteria.");

        EvalJudgeOutput explained = tool.judge(scenario(), passingRun(), true);
        EvalJudgeOutput plain = tool.judge(scenario(), passingRun(), false);

        assertThat(explained.getMetaJudgeRationale()).contains("required keyword");
        assertThat(explained.getCompositeScore()).isGreaterThan(55.0);   // non-fuzzy
        // ADDITIVE: the rationale call did not perturb the score.
        assertThat(explained.getCompositeScore()).isEqualTo(plain.getCompositeScore());
        assertThat(explained.isPass()).isEqualTo(plain.isPass());
        // exactly one LLM call (the rationale) — deterministic oracle, non-fuzzy → no scoring/meta LLM.
        verify(llmProvider, times(1)).chat(any(LlmRequest.class));
    }

    @Test
    @DisplayName("explain=false (2-arg path callers): no rationale, no LLM call")
    void explainFalse_noRationale_noLlmCall() {
        EvalJudgeOutput out = tool.judge(scenario(), passingRun(), false);

        assertThat(out.getMetaJudgeRationale()).isNull();
        assertThat(out.getCompositeScore()).isGreaterThan(55.0);
        verify(llmProvider, never()).chat(any(LlmRequest.class));
    }

    @Test
    @DisplayName("explain=true but provider null: rationale null, score intact (failure never hurts the score)")
    void explainTrue_providerNull_rationaleNull_scoreIntact() {
        when(llmProviderFactory.getProvider(anyString())).thenReturn(null);

        EvalJudgeOutput out = tool.judge(scenario(), passingRun(), true);
        EvalJudgeOutput plain = tool.judge(scenario(), passingRun(), false);

        assertThat(out.getMetaJudgeRationale()).isNull();
        assertThat(out.getCompositeScore()).isEqualTo(plain.getCompositeScore());
    }

    @Test
    @DisplayName("explain=true but provider.chat throws: rationale null, score intact (failure barrier)")
    void explainTrue_chatThrows_rationaleNull_scoreIntact() {
        when(llmProviderFactory.getProvider(anyString())).thenReturn(llmProvider);
        when(llmProvider.chat(any(LlmRequest.class))).thenThrow(new RuntimeException("provider boom"));

        EvalJudgeOutput out = tool.judge(scenario(), passingRun(), true);
        EvalJudgeOutput plain = tool.judge(scenario(), passingRun(), false);

        assertThat(out.getMetaJudgeRationale()).isNull();
        assertThat(out.getCompositeScore()).isEqualTo(plain.getCompositeScore());
        assertThat(out.isPass()).isEqualTo(plain.isPass());
    }

    @Test
    @DisplayName("ERROR run + explain=true: cheap templated rationale, no LLM call")
    void errorRun_explainTrue_templatedRationale_noLlm() {
        ScenarioRunResult run = passingRun();
        run.setStatus("ERROR");
        run.setEngineThrewException(true);

        EvalJudgeOutput out = tool.judge(scenario(), run, true);

        assertThat(out.getCompositeScore()).isEqualTo(0.0);
        assertThat(out.getMetaJudgeRationale()).contains("errored");
        verify(llmProvider, never()).chat(any(LlmRequest.class));
    }
}
