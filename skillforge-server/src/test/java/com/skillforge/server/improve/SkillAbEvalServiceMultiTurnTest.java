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
        service = new SkillAbEvalService(
                skillRepository, skillAbRunRepository, evalRunRepository,
                skillEvalHistoryRepository, agentService, scenarioLoader,
                sandboxFactory, evalEngineFactory, evalJudgeTool, skillPackageLoader,
                new ObjectMapper(), broadcaster, coordinatorExecutor, loopExecutor,
                skillRegistry, abCompletedEventPublisher);

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

        // RED for SKILL-AB-MULTITURN-FIX Phase 1.0:
        // Current code at SkillAbEvalService.java:387-393 warns and falls through to
        // runSingleScenario at :398; after the fix this must use the multi-turn judge.
        ReflectionTestUtils.invokeMethod(service, "runAbTestAsync", "ab-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SkillDefinition>> overrideCaptor = ArgumentCaptor.forClass(List.class);
        verify(sandboxFactory).buildSandboxRegistryWithSkills(eq("ab-1"), eq("multi-1"), overrideCaptor.capture());
        assertThat(overrideCaptor.getValue())
                .extracting(SkillDefinition::getName)
                .containsExactly("candidate-skill");
        verify(evalJudgeTool).judgeMultiTurnConversation(eq(scenario), any(), any(MultiTurnTranscript.class));
        verify(evalJudgeTool, never()).judge(eq(scenario), any());
        verify(engine).run(any(), eq("first user turn"), any(), anyString(), any(), any());
        verify(engine).run(any(), eq("second user turn"), any(), anyString(), any(), any());
        verify(engine, never()).run(any(), eq("single-turn fallback task"), any(), anyString(), any(), any());

        ArgumentCaptor<SkillAbRunEntity> savedCaptor = ArgumentCaptor.forClass(SkillAbRunEntity.class);
        verify(skillAbRunRepository, org.mockito.Mockito.atLeastOnce()).save(savedCaptor.capture());
        SkillAbRunEntity finalSave = savedCaptor.getAllValues().get(savedCaptor.getAllValues().size() - 1);
        assertThat(finalSave.getStatus()).isEqualTo("COMPLETED");
        assertThat(finalSave.getCandidatePassRate()).isEqualTo(100.0);
        assertThat(finalSave.getAbScenarioResultsJson()).contains("\"oracleScore\":87.0");
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

        verify(evalJudgeTool).judge(eq(scenario), any());
        verify(evalJudgeTool, never()).judgeMultiTurnConversation(eq(scenario), any(), any());
        verify(engine).run(any(), eq("single-turn task"), any(), anyString(), any(), any());
    }

    @SuppressWarnings("unchecked")
    private static Callable<LoopResult> anyCallable() {
        return any(Callable.class);
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
