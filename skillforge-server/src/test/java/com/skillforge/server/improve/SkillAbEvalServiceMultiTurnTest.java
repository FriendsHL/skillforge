package com.skillforge.server.improve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.AgentLoopEngine;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.engine.LoopResult;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.Message;
import com.skillforge.core.model.SkillDefinition;
import com.skillforge.core.skill.SkillPackageLoader;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SkillAbRunEntity;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.event.SkillAbCompletedEventPublisher;
import com.skillforge.server.eval.EvalEngineFactory;
import com.skillforge.server.eval.EvalJudgeOutput;
import com.skillforge.server.eval.EvalJudgeMultiTurnOutput;
import com.skillforge.server.eval.EvalJudgeTool;
import com.skillforge.server.eval.MultiTurnTranscript;
import com.skillforge.server.eval.sandbox.SandboxSkillRegistryFactory;
import com.skillforge.server.eval.scenario.EvalScenario;
import com.skillforge.server.eval.scenario.ScenarioLoader;
import com.skillforge.server.repository.EvalTaskRepository;
import com.skillforge.server.repository.SkillAbRunRepository;
import com.skillforge.server.repository.SkillEvalHistoryRepository;
import com.skillforge.server.repository.SkillRepository;
import com.skillforge.server.service.AgentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SkillAbEvalService multi-turn A/B")
class SkillAbEvalServiceMultiTurnTest {

    @Mock private SkillRepository skillRepository;
    @Mock private SkillAbRunRepository skillAbRunRepository;
    @Mock private EvalTaskRepository evalRunRepository;
    @Mock private SkillEvalHistoryRepository skillEvalHistoryRepository;
    @Mock private AgentService agentService;
    @Mock private ScenarioLoader scenarioLoader;
    @Mock private SandboxSkillRegistryFactory sandboxFactory;
    @Mock private EvalEngineFactory evalEngineFactory;
    @Mock private EvalJudgeTool evalJudgeTool;
    @Mock private SkillPackageLoader skillPackageLoader;
    @Mock private ChatEventBroadcaster broadcaster;
    @Mock private ExecutorService coordinatorExecutor;
    @Mock private ExecutorService loopExecutor;
    @Mock private SkillRegistry skillRegistry;
    @Mock private SkillAbCompletedEventPublisher abCompletedEventPublisher;
    @Mock private AgentLoopEngine engine;

    private SkillAbEvalService service;

    @BeforeEach
    void setUp() throws Exception {
        SkillEvalService mockEvalService = org.mockito.Mockito.mock(SkillEvalService.class);
        service = new SkillAbEvalService(
                skillRepository, skillAbRunRepository, evalRunRepository,
                skillEvalHistoryRepository, agentService, scenarioLoader,
                sandboxFactory, evalEngineFactory, evalJudgeTool, skillPackageLoader,
                new ObjectMapper(), broadcaster, coordinatorExecutor, loopExecutor,
                skillRegistry, abCompletedEventPublisher,
                org.mockito.Mockito.mock(com.skillforge.server.improve.surface.SkillSurface.class),
                mockEvalService,
                120_000L);
        // Phase 1.2 reviewer-r1 fix: SkillEvalService is the EvalService<SkillEntity>
        // adapter. In production it @Lazy-delegates back to runEvalSetInternal;
        // here in tests we mock the adapter and rewire its run() to delegate
        // back to service.runEvalSetInternal — preserves the pre-refactor
        // sandboxFactory / evalJudgeTool assertion paths.
        when(mockEvalService.run(any(), any(SkillEntity.class)))
                .thenAnswer(inv -> service.runEvalSetInternal(
                        inv.getArgument(0), inv.getArgument(1)));

        when(loopExecutor.submit(anyCallable())).thenAnswer(inv -> {
            Callable<LoopResult> callable = inv.getArgument(0);
            return CompletableFuture.completedFuture(callable.call());
        });
    }

    @Test
    @DisplayName("multi-turn held-out scenario uses candidate registry and multi-turn judge")
    void runAbTestAsync_multiTurnScenario_usesCandidateRegistryAndMultiTurnJudge() throws Exception {
        SkillAbRunEntity abRun = abRun();
        SkillEntity candidate = candidateSkill();
        EvalScenario scenario = multiTurnScenario();
        AgentDefinition agentDef = agentDefinition();

        when(skillAbRunRepository.findById("ab-1")).thenReturn(Optional.of(abRun));
        when(skillAbRunRepository.save(any(SkillAbRunEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(scenarioLoader.loadAll()).thenReturn(List.of(scenario));
        when(skillRepository.findById(2L)).thenReturn(Optional.of(candidate));
        when(agentService.getAgent(10L)).thenReturn(new AgentEntity());
        when(agentService.toAgentDefinition(any(AgentEntity.class))).thenReturn(agentDef);
        when(sandboxFactory.buildSandboxRegistryWithSkills(eq("ab-1"), eq("multi-1"), any()))
                .thenReturn(skillRegistry);
        when(sandboxFactory.getSandboxRoot("ab-1", "multi-1")).thenReturn(Path.of("/tmp/eval-ab"));
        when(evalEngineFactory.buildEvalEngine(skillRegistry)).thenReturn(engine);
        when(engine.run(any(), anyString(), any(), anyString(), any(), any()))
                .thenAnswer(inv -> loopResult(inv.getArgument(1)));

        EvalJudgeMultiTurnOutput judgeOutput = new EvalJudgeMultiTurnOutput();
        judgeOutput.setCompositeScore(87.0);
        judgeOutput.setPass(true);
        when(evalJudgeTool.judgeMultiTurnConversation(eq(scenario), any(), any(MultiTurnTranscript.class)))
                .thenReturn(judgeOutput);

        ReflectionTestUtils.invokeMethod(service, "runAbTestAsync", "ab-1");

        // SKILL-AB-BASELINE-FIX: both arms now run live (no cached baselineEvalRunId).
        // The baseline arm runs FIRST with NO injected skill ("agent without the
        // candidate skill"); the candidate arm runs SECOND with the candidate skill.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SkillDefinition>> overrideCaptor = ArgumentCaptor.forClass(List.class);
        verify(sandboxFactory, times(2))
                .buildSandboxRegistryWithSkills(eq("ab-1"), eq("multi-1"), overrideCaptor.capture());
        List<List<SkillDefinition>> arms = overrideCaptor.getAllValues();
        assertThat(arms.get(0)).as("baseline arm injects NO candidate skill").isEmpty();
        assertThat(arms.get(1)).as("candidate arm injects the candidate skill")
                .extracting(SkillDefinition::getName)
                .containsExactly("candidate-skill");
        // Both arms judged (baseline + candidate).
        verify(evalJudgeTool, times(2)).judgeMultiTurnConversation(eq(scenario), any(), any(MultiTurnTranscript.class));
        verify(evalJudgeTool, never()).judge(eq(scenario), any());
        verify(engine, times(2)).run(any(), eq("first user turn"), any(), anyString(), any(), any());
        verify(engine, times(2)).run(any(), eq("second user turn"), any(), anyString(), any(), any());
        verify(engine, never()).run(any(), eq("single-turn fallback task"), any(), anyString(), any(), any());

        ArgumentCaptor<SkillAbRunEntity> savedCaptor = ArgumentCaptor.forClass(SkillAbRunEntity.class);
        verify(skillAbRunRepository, org.mockito.Mockito.atLeastOnce()).save(savedCaptor.capture());
        SkillAbRunEntity finalSave = savedCaptor.getAllValues().get(savedCaptor.getAllValues().size() - 1);
        assertThat(finalSave.getStatus()).isEqualTo("COMPLETED");
        assertThat(finalSave.getCandidatePassRate()).isEqualTo(100.0);
        // Baseline arm ran live → its pass rate is REAL (the same judge stub passes
        // both arms here, so baseline == candidate == 100 and delta == 0), proving
        // the baseline is no longer the phantom hard-coded 0.
        assertThat(finalSave.getBaselinePassRate()).isEqualTo(100.0);
        assertThat(finalSave.getDeltaPassRate()).isEqualTo(0.0);
        assertThat(finalSave.getAbScenarioResultsJson()).contains("\"oracleScore\":87.0");
    }

    @Test
    @DisplayName("multi-turn judge failure on BOTH arms isolates to per-scenario ERROR; AB run still COMPLETED")
    void runAbTestAsync_multiTurnScenario_perScenarioErrorIsolated() throws Exception {
        // SKILL-AB-MULTITURN-FIX 验收 #6 explicit coverage:
        // 让 multi-turn judge 抛 RuntimeException 时，SkillAbEvalService.runArmEvalSet
        // 的 per-scenario try/catch 必须把该 side status 钉成 "ERROR" / score = 0.0 /
        // 不抛上层，让外层 runAbTestAsync 仍能 setStatus("COMPLETED")（而非 catch 块
        // setStatus("FAILED")）。
        // 选 judge 抛而非 engine.run 抛：因为 runMultiTurnScenario 内部有自己的 try/catch
        // 会 swallow engine 异常转 ScenarioRunResult.error；只有 judge 异常会逃到
        // runArmEvalSet 的外层 catch — 测这块代码最直接的方式。
        // 注：此用例 judge 对 BOTH arms (baseline + candidate) 都抛（unconditional thenThrow）
        // → 两侧都 ERROR。candidate-arm-only 隔离不变量由下一个用例
        // runAbTestAsync_multiTurnScenario_candidateArmErrorIsolated_baselineSucceeds 守。
        // PRD 验收点："A/B run 失败场景有明确 per-scenario error result，run 状态和事件不回退"。
        SkillAbRunEntity abRun = abRun();
        SkillEntity candidate = candidateSkill();
        EvalScenario scenario = multiTurnScenario();
        AgentDefinition agentDef = agentDefinition();

        when(skillAbRunRepository.findById("ab-1")).thenReturn(Optional.of(abRun));
        when(skillAbRunRepository.save(any(SkillAbRunEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(scenarioLoader.loadAll()).thenReturn(List.of(scenario));
        when(skillRepository.findById(2L)).thenReturn(Optional.of(candidate));
        when(agentService.getAgent(10L)).thenReturn(new AgentEntity());
        when(agentService.toAgentDefinition(any(AgentEntity.class))).thenReturn(agentDef);
        when(sandboxFactory.buildSandboxRegistryWithSkills(eq("ab-1"), eq("multi-1"), any()))
                .thenReturn(skillRegistry);
        when(sandboxFactory.getSandboxRoot("ab-1", "multi-1")).thenReturn(Path.of("/tmp/eval-ab"));
        when(evalEngineFactory.buildEvalEngine(skillRegistry)).thenReturn(engine);
        when(engine.run(any(), anyString(), any(), anyString(), any(), any()))
                .thenAnswer(inv -> loopResult(inv.getArgument(1)));
        // Force per-scenario failure by making the multi-turn judge throw on every
        // call. The outer per-scenario catch in runArmEvalSet must absorb it.
        when(evalJudgeTool.judgeMultiTurnConversation(eq(scenario), any(), any(MultiTurnTranscript.class)))
                .thenThrow(new RuntimeException("simulated multi-turn judge failure"));

        // Must not throw — per-scenario error isolation invariant.
        ReflectionTestUtils.invokeMethod(service, "runAbTestAsync", "ab-1");

        // AB run reaches COMPLETED (per-scenario error did NOT escalate to outer
        // runAbTestAsync catch which would set status=FAILED at :504).
        ArgumentCaptor<SkillAbRunEntity> savedCaptor = ArgumentCaptor.forClass(SkillAbRunEntity.class);
        verify(skillAbRunRepository, org.mockito.Mockito.atLeastOnce()).save(savedCaptor.capture());
        SkillAbRunEntity finalSave = savedCaptor.getAllValues().get(savedCaptor.getAllValues().size() - 1);
        assertThat(finalSave.getStatus()).isEqualTo("COMPLETED");
        assertThat(finalSave.getFailureReason()).isNull();
        // Per-scenario ERROR + 0.0 score serialized into ab_scenario_results_json.
        assertThat(finalSave.getAbScenarioResultsJson()).contains("\"status\":\"ERROR\"");
        assertThat(finalSave.getAbScenarioResultsJson()).contains("\"oracleScore\":0.0");
        // With 0 / 1 passed, candidate pass rate must be 0.0 (does NOT trip promote).
        assertThat(finalSave.getCandidatePassRate()).isEqualTo(0.0);
        // Single-turn judge MUST NOT be called as a fallback.
        verify(evalJudgeTool, never()).judge(eq(scenario), any());
    }

    @Test
    @DisplayName("candidate-arm judge failure isolates while baseline arm succeeds; run still COMPLETED")
    void runAbTestAsync_multiTurnScenario_candidateArmErrorIsolated_baselineSucceeds() throws Exception {
        // SKILL-AB-BASELINE-FIX re-guard: now that BOTH arms run, the per-scenario
        // error-isolation invariant must hold when ONLY the candidate arm's judge
        // throws (a future regression that propagates a candidate-arm exception
        // would otherwise slip through — the both-throw case above trips the catch
        // on the baseline arm first and never exercises the candidate arm).
        // Arms run in order: baseline(multi-1) → candidate(multi-1). Stub the judge
        // to PASS on the 1st call (baseline) and THROW on the 2nd (candidate).
        SkillAbRunEntity abRun = abRun();
        SkillEntity candidate = candidateSkill();
        EvalScenario scenario = multiTurnScenario();
        AgentDefinition agentDef = agentDefinition();

        when(skillAbRunRepository.findById("ab-1")).thenReturn(Optional.of(abRun));
        when(skillAbRunRepository.save(any(SkillAbRunEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(scenarioLoader.loadAll()).thenReturn(List.of(scenario));
        when(skillRepository.findById(2L)).thenReturn(Optional.of(candidate));
        when(agentService.getAgent(10L)).thenReturn(new AgentEntity());
        when(agentService.toAgentDefinition(any(AgentEntity.class))).thenReturn(agentDef);
        when(sandboxFactory.buildSandboxRegistryWithSkills(eq("ab-1"), eq("multi-1"), any()))
                .thenReturn(skillRegistry);
        when(sandboxFactory.getSandboxRoot("ab-1", "multi-1")).thenReturn(Path.of("/tmp/eval-ab"));
        when(evalEngineFactory.buildEvalEngine(skillRegistry)).thenReturn(engine);
        when(engine.run(any(), anyString(), any(), anyString(), any(), any()))
                .thenAnswer(inv -> loopResult(inv.getArgument(1)));

        EvalJudgeMultiTurnOutput baselinePass = new EvalJudgeMultiTurnOutput();
        baselinePass.setCompositeScore(90.0);
        baselinePass.setPass(true);
        when(evalJudgeTool.judgeMultiTurnConversation(eq(scenario), any(), any(MultiTurnTranscript.class)))
                .thenReturn(baselinePass)  // baseline arm (1st call) succeeds
                .thenThrow(new RuntimeException("simulated candidate-arm judge failure")); // candidate arm (2nd call)

        // Must not throw — candidate-arm per-scenario error isolation.
        ReflectionTestUtils.invokeMethod(service, "runAbTestAsync", "ab-1");

        ArgumentCaptor<SkillAbRunEntity> savedCaptor = ArgumentCaptor.forClass(SkillAbRunEntity.class);
        verify(skillAbRunRepository, org.mockito.Mockito.atLeastOnce()).save(savedCaptor.capture());
        SkillAbRunEntity finalSave = savedCaptor.getAllValues().get(savedCaptor.getAllValues().size() - 1);
        assertThat(finalSave.getStatus()).isEqualTo("COMPLETED");
        assertThat(finalSave.getFailureReason()).isNull();
        // Baseline arm ran live and PASSED → real non-zero baseline.
        assertThat(finalSave.getBaselinePassRate()).isEqualTo(100.0);
        // Candidate arm judge threw → that side is ERROR/0.0 → candidate rate 0.
        assertThat(finalSave.getCandidatePassRate()).isEqualTo(0.0);
        assertThat(finalSave.getDeltaPassRate()).isEqualTo(-100.0);
        // Per-scenario row carries baseline PASS + candidate ERROR (one entry, both sides).
        String json = finalSave.getAbScenarioResultsJson();
        assertThat(json).contains("\"status\":\"ERROR\"");
        assertThat(json).contains("\"oracleScore\":90.0");  // baseline side preserved
    }

    @Test
    @DisplayName("single-turn held-out scenario keeps existing single-turn judge path")
    void runAbTestAsync_singleTurnScenario_usesSingleTurnJudge() throws Exception {
        SkillAbRunEntity abRun = abRun();
        SkillEntity candidate = candidateSkill();
        EvalScenario scenario = singleTurnScenario();
        AgentDefinition agentDef = agentDefinition();

        when(skillAbRunRepository.findById("ab-1")).thenReturn(Optional.of(abRun));
        when(skillAbRunRepository.save(any(SkillAbRunEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(scenarioLoader.loadAll()).thenReturn(List.of(scenario));
        when(skillRepository.findById(2L)).thenReturn(Optional.of(candidate));
        when(agentService.getAgent(10L)).thenReturn(new AgentEntity());
        when(agentService.toAgentDefinition(any(AgentEntity.class))).thenReturn(agentDef);
        when(sandboxFactory.buildSandboxRegistryWithSkills(eq("ab-1"), eq("single-1"), any()))
                .thenReturn(skillRegistry);
        when(sandboxFactory.getSandboxRoot("ab-1", "single-1")).thenReturn(Path.of("/tmp/eval-ab"));
        when(evalEngineFactory.buildEvalEngine(skillRegistry)).thenReturn(engine);
        when(engine.run(any(), anyString(), any(), anyString(), any(), any()))
                .thenAnswer(inv -> loopResult(inv.getArgument(1)));

        EvalJudgeOutput judgeOutput = new EvalJudgeOutput();
        judgeOutput.setCompositeScore(72.0);
        judgeOutput.setPass(true);
        when(evalJudgeTool.judge(eq(scenario), any())).thenReturn(judgeOutput);

        ReflectionTestUtils.invokeMethod(service, "runAbTestAsync", "ab-1");

        // SKILL-AB-BASELINE-FIX: both arms (baseline + candidate) run the single-turn
        // judge path now, so judge + engine.run are each invoked twice.
        verify(evalJudgeTool, times(2)).judge(eq(scenario), any());
        verify(evalJudgeTool, never()).judgeMultiTurnConversation(eq(scenario), any(), any());
        verify(engine, times(2)).run(any(), eq("single-turn task"), any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("SKILL-AB-BASELINE-FIX: baseline runs live (no skill) → non-zero baseline, delta = candidate − baseline")
    void runAbTestAsync_noCachedBaseline_runsLiveBaselineAndComputesRealDelta() throws Exception {
        // Two single-turn held-out scenarios, no baselineEvalRunId (draft path).
        // Baseline arm (agent WITHOUT the candidate skill) passes 1/2 = 50pp.
        // Candidate arm (agent WITH the candidate skill) passes 2/2 = 100pp.
        // → baseline MUST be 50 (not the old phantom 0) and delta MUST be 50.
        SkillAbRunEntity abRun = abRun();
        SkillEntity candidate = candidateSkill();
        EvalScenario scenarioX = singleTurnScenario("x", "X");
        EvalScenario scenarioY = singleTurnScenario("y", "Y");
        AgentDefinition agentDef = agentDefinition();

        when(skillAbRunRepository.findById("ab-1")).thenReturn(Optional.of(abRun));
        when(skillAbRunRepository.save(any(SkillAbRunEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(scenarioLoader.loadAll()).thenReturn(List.of(scenarioX, scenarioY));
        when(skillRepository.findById(2L)).thenReturn(Optional.of(candidate));
        when(agentService.getAgent(10L)).thenReturn(new AgentEntity());
        when(agentService.toAgentDefinition(any(AgentEntity.class))).thenReturn(agentDef);
        when(sandboxFactory.buildSandboxRegistryWithSkills(eq("ab-1"), anyString(), any()))
                .thenReturn(skillRegistry);
        when(sandboxFactory.getSandboxRoot(eq("ab-1"), anyString())).thenReturn(Path.of("/tmp/eval-ab"));
        when(evalEngineFactory.buildEvalEngine(skillRegistry)).thenReturn(engine);
        when(engine.run(any(), anyString(), any(), anyString(), any(), any()))
                .thenAnswer(inv -> loopResult(inv.getArgument(1)));

        // Arms run in order: baseline(X), baseline(Y), candidate(X), candidate(Y).
        // Scenario X: baseline PASS, candidate PASS. Scenario Y: baseline FAIL, candidate PASS.
        when(evalJudgeTool.judge(eq(scenarioX), any()))
                .thenReturn(judgeOutput(70.0, true))   // baseline X
                .thenReturn(judgeOutput(85.0, true));  // candidate X
        when(evalJudgeTool.judge(eq(scenarioY), any()))
                .thenReturn(judgeOutput(20.0, false))  // baseline Y
                .thenReturn(judgeOutput(80.0, true));  // candidate Y

        ReflectionTestUtils.invokeMethod(service, "runAbTestAsync", "ab-1");

        ArgumentCaptor<SkillAbRunEntity> savedCaptor = ArgumentCaptor.forClass(SkillAbRunEntity.class);
        verify(skillAbRunRepository, org.mockito.Mockito.atLeastOnce()).save(savedCaptor.capture());
        SkillAbRunEntity finalSave = savedCaptor.getAllValues().get(savedCaptor.getAllValues().size() - 1);
        assertThat(finalSave.getStatus()).isEqualTo("COMPLETED");
        assertThat(finalSave.getBaselinePassRate()).isEqualTo(50.0);
        assertThat(finalSave.getCandidatePassRate()).isEqualTo(100.0);
        assertThat(finalSave.getDeltaPassRate()).isEqualTo(50.0);
        // delta 50 ≥ 15 AND candidate 100 ≥ 40 → promotes against a REAL baseline.
        assertThat(finalSave.isPromoted()).isTrue();

        // Per-scenario merge: ONE entry per scenario carrying BOTH sides (not duplicates).
        String json = finalSave.getAbScenarioResultsJson();
        assertThat(json).isNotNull();
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> rows = new ObjectMapper().readValue(
                json, java.util.List.class);
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get("baseline")).isNotNull();
            assertThat(row.get("candidate")).isNotNull();
            // Neither side is the UNKNOWN placeholder — both arms filled their side.
            assertThat(((java.util.Map<?, ?>) row.get("baseline")).get("status")).isNotEqualTo("UNKNOWN");
            assertThat(((java.util.Map<?, ?>) row.get("candidate")).get("status")).isNotEqualTo("UNKNOWN");
        });
    }

    @SuppressWarnings("unchecked")
    private static Callable<LoopResult> anyCallable() {
        return any(Callable.class);
    }

    private static EvalJudgeOutput judgeOutput(double score, boolean pass) {
        EvalJudgeOutput out = new EvalJudgeOutput();
        out.setCompositeScore(score);
        out.setPass(pass);
        return out;
    }

    private static EvalScenario singleTurnScenario(String id, String name) {
        EvalScenario scenario = new EvalScenario();
        scenario.setId(id);
        scenario.setName(name);
        scenario.setSplit("held_out");
        scenario.setTask("task for " + id);
        scenario.setMaxLoops(3);
        return scenario;
    }

    private static SkillAbRunEntity abRun() {
        SkillAbRunEntity run = new SkillAbRunEntity();
        run.setId("ab-1");
        run.setParentSkillId(1L);
        run.setCandidateSkillId(2L);
        run.setAgentId("10");
        run.setStatus("PENDING");
        return run;
    }

    private static SkillEntity candidateSkill() {
        SkillEntity skill = new SkillEntity();
        skill.setId(2L);
        skill.setName("candidate-skill");
        skill.setDescription("candidate description");
        skill.setTriggers("candidate");
        skill.setRequiredTools("");
        return skill;
    }

    private static AgentDefinition agentDefinition() {
        AgentDefinition def = new AgentDefinition();
        def.setId("10");
        def.setName("Eval Agent");
        def.setSystemPrompt("Use the candidate skill.");
        return def;
    }

    private static EvalScenario multiTurnScenario() {
        EvalScenario scenario = new EvalScenario();
        scenario.setId("multi-1");
        scenario.setName("Multi turn held-out");
        scenario.setSplit("held_out");
        scenario.setTask("single-turn fallback task");
        scenario.setMaxLoops(3);
        scenario.setConversationTurns(List.of(
                new EvalScenario.ConversationTurn("user", "first user turn"),
                new EvalScenario.ConversationTurn("assistant", EvalScenario.ASSISTANT_PLACEHOLDER),
                new EvalScenario.ConversationTurn("user", "second user turn")
        ));
        return scenario;
    }

    private static EvalScenario singleTurnScenario() {
        EvalScenario scenario = new EvalScenario();
        scenario.setId("single-1");
        scenario.setName("Single turn held-out");
        scenario.setSplit("held_out");
        scenario.setTask("single-turn task");
        scenario.setMaxLoops(3);
        return scenario;
    }

    private static LoopResult loopResult(String userMessage) {
        LoopResult result = new LoopResult();
        result.setFinalResponse("assistant response to " + userMessage);
        result.setLoopCount(1);
        result.setTotalInputTokens(10);
        result.setTotalOutputTokens(5);
        result.setMessages(List.of(
                Message.user(userMessage),
                Message.assistant(result.getFinalResponse())
        ));
        return result;
    }
}
