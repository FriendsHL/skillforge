package com.skillforge.server.improve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.AgentLoopEngine;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.engine.LoopResult;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.EvalScenarioEntity;
import com.skillforge.server.entity.PromptAbRunEntity;
import com.skillforge.server.entity.PromptVersionEntity;
import com.skillforge.server.eval.EvalEngineFactory;
import com.skillforge.server.eval.EvalJudgeOutput;
import com.skillforge.server.eval.EvalJudgeTool;
import com.skillforge.server.eval.scenario.EvalScenario;
import com.skillforge.server.eval.sandbox.SandboxSkillRegistryFactory;
import com.skillforge.server.eval.scenario.ScenarioLoader;
import com.skillforge.server.repository.PromptAbRunRepository;
import com.skillforge.server.repository.PromptVersionRepository;
import com.skillforge.server.service.AgentService;
import com.skillforge.server.service.EvalDatasetService;
import com.skillforge.server.repository.EvalDatasetVersionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AUTOEVOLVE-AGENT-FLYWHEEL BUG-1 (winner-carry-forward) — pins the load-bearing
 * arithmetic in {@link AbEvalPipeline#runWithScenarios} when a {@code cachedBaselineRate}
 * is supplied (the hill-climb candidate-only path).
 *
 * <p>Unlike the other AbEvalPipeline unit tests (which only exercise the empty-scenario
 * early-exit and defer real per-scenario execution to IT), this test mocks the
 * sandbox + engine + judge collaborators and runs on a real executor so the
 * fan-out aggregate math actually runs. It is deliberately scoped to the
 * skipBaseline branch — the exact computation the fix lives in.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AbEvalPipeline skipBaseline (BUG-1 winner-carry-forward)")
class AbEvalPipelineSkipBaselineTest {

    @Mock private ScenarioLoader scenarioLoader;
    @Mock private SandboxSkillRegistryFactory sandboxFactory;
    @Mock private EvalEngineFactory evalEngineFactory;
    @Mock private EvalJudgeTool evalJudgeTool;
    @Mock private PromptAbRunRepository promptAbRunRepository;
    @Mock private PromptVersionRepository promptVersionRepository;
    @Mock private AgentService agentService;
    @Mock private ChatEventBroadcaster broadcaster;
    @Mock private EvalDatasetService evalDatasetService;
    @Mock private EvalDatasetVersionRepository evalDatasetVersionRepository;
    @Mock private AgentLoopEngine engine;
    @Mock private SkillRegistry sandboxRegistry;

    private ExecutorService realExecutor;
    private AbEvalPipeline pipeline;

    @BeforeEach
    void setUp() throws Exception {
        realExecutor = Executors.newCachedThreadPool();
        pipeline = new AbEvalPipeline(
                scenarioLoader, sandboxFactory, evalEngineFactory,
                evalJudgeTool, promptAbRunRepository, promptVersionRepository,
                agentService, new ObjectMapper(), broadcaster, realExecutor,
                120_000L, evalDatasetService, evalDatasetVersionRepository, 3, 0);

        // Collaborators shared by both the baseline and candidate sides. lenient()
        // because the skipBaseline test never exercises the baseline side.
        lenient().when(agentService.toAgentDefinition(any())).thenAnswer(inv -> new AgentDefinition());
        lenient().when(sandboxFactory.buildSandboxRegistry(anyString(), anyString())).thenReturn(sandboxRegistry);
        lenient().when(sandboxFactory.getSandboxRoot(anyString(), anyString()))
                .thenReturn(Path.of("/tmp/sf-skipbaseline-test"));  // never created — scenarios have no setup files
        lenient().when(evalEngineFactory.buildEvalEngine(any())).thenReturn(engine);
        lenient().when(engine.run(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            LoopResult r = new LoopResult();
            r.setFinalResponse("done");
            r.setLoopCount(1);
            return r;
        });
        when(promptAbRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(promptVersionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // s1, s2 pass; s3 fails → 2 of 3 → 66.67%. Applied to whichever side calls judge.
        // EVAL-429-ROBUSTNESS: runWithScenarios now counts passes via the shared
        // AbEvalPipeline.isPass(RunResult) predicate (composite >= 40), consistent with
        // computeDeltas / passRateOver. Use realistic composite scores (80 pass / 10 fail)
        // that straddle the 40 threshold — the 2/3 = 66.67% contract is unchanged.
        when(evalJudgeTool.judge(any(), any(), anyBoolean())).thenAnswer(inv -> {
            EvalScenario sc = inv.getArgument(0);
            boolean pass = !"s3".equals(sc.getId());
            EvalJudgeOutput o = new EvalJudgeOutput();
            o.setPass(pass);
            o.setCompositeScore(pass ? 80.0 : 10.0);
            return o;
        });
    }

    @AfterEach
    void tearDown() {
        realExecutor.shutdownNow();
    }

    private EvalScenarioEntity scenario(String id) {
        EvalScenarioEntity e = new EvalScenarioEntity();
        e.setId(id);
        e.setName("scenario " + id);
        e.setTask("do task " + id);
        return e;
    }

    private PromptVersionEntity version(String id, String content) {
        PromptVersionEntity v = new PromptVersionEntity();
        v.setId(id);
        v.setContent(content);
        return v;
    }

    @Test
    @DisplayName("cachedBaselineRate set → baseline side NOT measured, baselinePassRate=cached, delta=candidate-cached, no dataset back-write")
    void skipBaseline_reusesCachedRate_baselineNotMeasured_noBackWrite() {
        PromptAbRunEntity abRun = new PromptAbRunEntity();
        abRun.setId("ab-skip");
        abRun.setAgentId("7");
        AgentEntity agent = new AgentEntity();
        agent.setId(7L);
        when(evalDatasetService.getScenariosForVersion("dv-1"))
                .thenReturn(List.of(scenario("s1"), scenario("s2"), scenario("s3")));

        // cachedBaselineRate = 60.0 → candidate-only winner-carry-forward.
        pipeline.run(abRun, version("cand", "candidate prompt"), version("base", "best prompt"),
                agent, "dv-1", 60.0);

        // Baseline reused, not re-measured.
        assertThat(abRun.getBaselinePassRate()).isEqualTo(60.0);
        // Candidate measured: 2/3 pass = 66.67%.
        assertThat(abRun.getCandidatePassRate()).isCloseTo(66.666, org.assertj.core.data.Offset.offset(0.01));
        assertThat(abRun.getDeltaPassRate()).isCloseTo(6.666, org.assertj.core.data.Offset.offset(0.01));
        assertThat(abRun.getStatus()).isEqualTo("COMPLETED");

        // Judge invoked exactly ONCE per scenario (candidate only) — NOT twice (baseline skipped).
        verify(evalJudgeTool, times(3)).judge(any(), any(), anyBoolean());
        // Engine built once per scenario (candidate only), not 6×.
        verify(evalEngineFactory, times(3)).buildEvalEngine(any());
        // Back-write guarded off when skipBaseline: the carried winner score must not
        // pollute the dataset's actualBaselinePassRate moving average.
        verify(evalDatasetVersionRepository, never()).findById(any());
    }

    @Test
    @DisplayName("no cachedBaselineRate → baseline IS measured (judge runs both sides), proving the skip is conditional")
    void noCachedRate_measuresBaseline_judgeRunsBothSides() {
        PromptAbRunEntity abRun = new PromptAbRunEntity();
        abRun.setId("ab-measure");
        abRun.setAgentId("7");
        AgentEntity agent = new AgentEntity();
        agent.setId(7L);
        when(evalDatasetService.getScenariosForVersion("dv-1"))
                .thenReturn(List.of(scenario("s1"), scenario("s2"), scenario("s3")));
        // Legacy path reaches the dataset back-write (not skipped) — stub findById.
        when(evalDatasetVersionRepository.findById("dv-1")).thenReturn(Optional.empty());

        // 5-arg dataset overload → cachedBaselineRate defaults to null (measure both).
        pipeline.run(abRun, version("cand", "candidate prompt"), version("base", "baseline prompt"),
                agent, "dv-1");

        // Baseline measured fresh: same 2/3 stub → 66.67%, NOT a cached constant.
        assertThat(abRun.getBaselinePassRate()).isCloseTo(66.666, org.assertj.core.data.Offset.offset(0.01));
        // Judge invoked twice per scenario (baseline + candidate) = 6.
        verify(evalJudgeTool, times(6)).judge(any(), any(), anyBoolean());
        verify(evalEngineFactory, times(6)).buildEvalEngine(any());
        // Back-write attempted when not skipping.
        verify(evalDatasetVersionRepository).findById("dv-1");
    }

    // ---- EVAL-429-ROBUSTNESS: pipeline-level infra-exclude (W1) ----------------------
    //
    // Make scenario s2's agent run throw (simulated infra failure: network/rate-limit) →
    // runSingleScenario returns status=ERROR → the post-process measured-filter must drop
    // it from the denominator (D2/D3). 2 scenarios, 1 ERROR → rate computed over 1, NOT 2.

    @Test
    @DisplayName("EVAL-429 (fresh): a scenario whose agent run ERRORs is excluded from the denominator (1 PASS + 1 ERROR → 100%, not 50%)")
    void fresh_infraScenarioExcludedFromDenominator() {
        PromptAbRunEntity abRun = new PromptAbRunEntity();
        abRun.setId("ab-infra-fresh");
        abRun.setAgentId("7");
        AgentEntity agent = new AgentEntity();
        agent.setId(7L);
        when(evalDatasetService.getScenariosForVersion("dv-1"))
                .thenReturn(List.of(scenario("s1"), scenario("s2")));
        when(evalDatasetVersionRepository.findById("dv-1")).thenReturn(Optional.empty());
        // s2's run throws (infra) → ScenarioRunResult.error → status=ERROR for both sides.
        // doAnswer (not when().thenAnswer) to safely OVERRIDE the setUp engine.run stub.
        doAnswer(inv -> {
            String task = inv.getArgument(1);
            if (task.contains("s2")) {
                throw new RuntimeException("simulated infra error (network/rate-limit) for s2");
            }
            LoopResult r = new LoopResult();
            r.setFinalResponse("done");
            r.setLoopCount(1);
            return r;
        }).when(engine).run(any(), any(), any(), any(), any(), any());

        pipeline.run(abRun, version("cand", "candidate prompt"), version("base", "baseline prompt"),
                agent, "dv-1");

        // s1 measured + passes (composite 80 ≥ 40); s2 ERROR excluded → denominator=1.
        // Correct: 1/1 = 100%. A denominator bug would yield 1/2 = 50% (ERROR row scores
        // 0/false under isPass), so 100% proves s2 was dropped, not counted.
        assertThat(abRun.getCandidatePassRate()).isCloseTo(100.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(abRun.getBaselinePassRate()).isCloseTo(100.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(abRun.getDeltaPassRate()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(abRun.getStatus()).isEqualTo("COMPLETED");
        // The ERROR scenario is still serialized (encountered) — proving it was present
        // yet excluded from the rate.
        assertThat(abRun.getAbScenarioResultsJson()).contains("\"status\":\"ERROR\"");
    }

    @Test
    @DisplayName("EVAL-429 (skip): an ERROR candidate scenario is excluded; candidate rate over shrunk denominator, cached baseline kept")
    void skip_infraScenarioExcludedFromDenominator() {
        PromptAbRunEntity abRun = new PromptAbRunEntity();
        abRun.setId("ab-infra-skip");
        abRun.setAgentId("7");
        AgentEntity agent = new AgentEntity();
        agent.setId(7L);
        when(evalDatasetService.getScenariosForVersion("dv-1"))
                .thenReturn(List.of(scenario("s1"), scenario("s2")));
        // Only the candidate side runs on the skip path; s2 errors.
        doAnswer(inv -> {
            String task = inv.getArgument(1);
            if (task.contains("s2")) {
                throw new RuntimeException("simulated infra error for s2");
            }
            LoopResult r = new LoopResult();
            r.setFinalResponse("done");
            r.setLoopCount(1);
            return r;
        }).when(engine).run(any(), any(), any(), any(), any(), any());

        pipeline.run(abRun, version("cand", "candidate prompt"), version("base", "best prompt"),
                agent, "dv-1", 60.0);

        // candidate: s1 measured+pass, s2 ERROR excluded → 1/1 = 100% (not 1/2 = 50%).
        assertThat(abRun.getCandidatePassRate()).isCloseTo(100.0, org.assertj.core.data.Offset.offset(0.01));
        // Baseline still the cached constant (skip path never measures it).
        assertThat(abRun.getBaselinePassRate()).isEqualTo(60.0);
        assertThat(abRun.getDeltaPassRate()).isCloseTo(40.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(abRun.getStatus()).isEqualTo("COMPLETED");
        // Skip path must NOT back-write the dataset baseline.
        verify(evalDatasetVersionRepository, never()).findById(any());
    }
}
