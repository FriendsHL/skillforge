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
import com.skillforge.server.repository.EvalDatasetVersionRepository;
import com.skillforge.server.repository.PromptAbRunRepository;
import com.skillforge.server.repository.PromptVersionRepository;
import com.skillforge.server.service.AgentService;
import com.skillforge.server.service.EvalDatasetService;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AUTOEVOLVE-AGENT-LEVEL-BUNDLE Phase 1 — AC-1 numeric parity (§7 B1).
 *
 * <p>Asserts that running the SAME prompt-only candidate through the NEW
 * {@link AbEvalPipeline#runWithDefs} agent channel (explain=true) yields the
 * IDENTICAL candidate pass-rate as the legacy prompt path
 * ({@code runWithScenarios}, driven via {@code pipeline.run(..datasetVersionId..)})
 * on the same scenarios. Both judge with {@code judge(scenario, run, true)}; the
 * prompt path counts {@code judge.isPass()} and the agent channel aggregates via
 * the shared {@link AbEvalPipeline#isPass(AbScenarioResult.RunResult)} predicate —
 * the test pins that these two counts agree.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AbEvalPipeline.runWithDefs ↔ runWithScenarios numeric parity (AC-1)")
class AbEvalPipelineRunWithDefsParityTest {

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

        lenient().when(agentService.toAgentDefinition(any())).thenAnswer(inv -> new AgentDefinition());
        lenient().when(sandboxFactory.buildSandboxRegistry(anyString(), anyString())).thenReturn(sandboxRegistry);
        lenient().when(sandboxFactory.getSandboxRoot(anyString(), anyString()))
                .thenReturn(Path.of("/tmp/sf-agent-parity-test"));
        lenient().when(evalEngineFactory.buildEvalEngine(any())).thenReturn(engine);
        lenient().when(engine.run(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            LoopResult r = new LoopResult();
            r.setFinalResponse("done");
            r.setLoopCount(1);
            return r;
        });
        lenient().when(promptAbRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(promptVersionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // s1, s2 pass (composite 80 ≥ 40); s3 fails (composite 10 < 40). pass boolean
        // and compositeScore are kept consistent so isPass() ⟺ (oracleScore ≥ 40).
        lenient().when(evalJudgeTool.judge(any(), any(), anyBoolean())).thenAnswer(inv -> {
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
    @DisplayName("prompt-only candidate: agent channel candidate pass-rate == prompt path candidate pass-rate")
    void runWithDefs_candidatePassRate_matchesPromptPath() {
        List<EvalScenarioEntity> scenarios = List.of(scenario("s1"), scenario("s2"), scenario("s3"));

        // --- Prompt path (legacy): drives runWithScenarios via the dataset overload.
        PromptAbRunEntity abRun = new PromptAbRunEntity();
        abRun.setId("ab-prompt");
        abRun.setAgentId("7");
        AgentEntity agent = new AgentEntity();
        agent.setId(7L);
        when(evalDatasetService.getScenariosForVersion("dv-1")).thenReturn(scenarios);
        when(evalDatasetVersionRepository.findById("dv-1")).thenReturn(Optional.empty());

        pipeline.run(abRun, version("cand", "candidate prompt"), version("base", "baseline prompt"),
                agent, "dv-1");
        double promptPathCandidateRate = abRun.getCandidatePassRate();

        // --- Agent channel: runWithDefs (explain=true, no cached baseline).
        List<AbScenarioResult> perScenario = pipeline.runWithDefs(
                "ab-agent", scenarios, new AgentDefinition(), new AgentDefinition(), null, true);
        long candidatePassed = perScenario.stream()
                .filter(r -> AbEvalPipeline.isPass(r.candidate()))
                .count();
        double agentChannelCandidateRate = (double) candidatePassed / perScenario.size() * 100.0;

        // Numeric parity (AC-1): both count 2 of 3 → 66.67%.
        assertThat(agentChannelCandidateRate)
                .isCloseTo(promptPathCandidateRate, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(agentChannelCandidateRate).isCloseTo(66.666, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    @DisplayName("runWithDefs with cachedBaselineRate fills baseline side with CACHED sentinel (candidate-only)")
    void runWithDefs_cachedBaseline_skipsBaselineSide() {
        List<EvalScenarioEntity> scenarios = List.of(scenario("s1"), scenario("s2"), scenario("s3"));

        List<AbScenarioResult> perScenario = pipeline.runWithDefs(
                "ab-skip", scenarios, new AgentDefinition(), new AgentDefinition(), 60.0, true);

        assertThat(perScenario).hasSize(3);
        // Baseline side is the cached sentinel — never measured.
        assertThat(perScenario).allSatisfy(r ->
                assertThat(r.baseline().status()).isEqualTo("CACHED"));
        // Candidate side still measured: s1/s2 pass, s3 fails.
        long candidatePassed = perScenario.stream()
                .filter(r -> AbEvalPipeline.isPass(r.candidate()))
                .count();
        assertThat(candidatePassed).isEqualTo(2);
        // The CACHED sentinel is NOT counted as a baseline pass.
        assertThat(perScenario).noneSatisfy(r ->
                assertThat(AbEvalPipeline.isPass(r.baseline())).isTrue());
    }

    @Test
    @DisplayName("runWithDefs extraSkills: candidate side (:c) injects skills; baseline side (:b) plain (§10 #5)")
    void runWithDefs_extraSkills_injectedOnCandidateSideOnly() throws Exception {
        List<EvalScenarioEntity> scenarios = List.of(scenario("s1"));
        lenient().when(sandboxFactory.buildSandboxRegistryWithSkills(anyString(), anyString(), anyList()))
                .thenReturn(sandboxRegistry);

        com.skillforge.core.model.SkillDefinition skill = new com.skillforge.core.model.SkillDefinition();
        skill.setName("MySkill");

        pipeline.runWithDefs("ab-skills", scenarios, new AgentDefinition(), new AgentDefinition(),
                null, true, List.of(skill));

        // Candidate side (:c) builds the WITH-skills sandbox; baseline side (:b) does NOT.
        verify(sandboxFactory).buildSandboxRegistryWithSkills(contains(":c"), eq("s1"), anyList());
        verify(sandboxFactory).buildSandboxRegistry(contains(":b"), eq("s1"));
        verify(sandboxFactory, never()).buildSandboxRegistryWithSkills(contains(":b"), anyString(), anyList());
    }

    @Test
    @DisplayName("runWithDefs empty extraSkills: never calls the WITH-skills sandbox (6-arg parity)")
    void runWithDefs_emptyExtraSkills_plainSandboxBothSides() throws Exception {
        List<EvalScenarioEntity> scenarios = List.of(scenario("s1"));

        pipeline.runWithDefs("ab-plain", scenarios, new AgentDefinition(), new AgentDefinition(),
                null, true, List.of());

        verify(sandboxFactory, never()).buildSandboxRegistryWithSkills(anyString(), anyString(), anyList());
    }
}
