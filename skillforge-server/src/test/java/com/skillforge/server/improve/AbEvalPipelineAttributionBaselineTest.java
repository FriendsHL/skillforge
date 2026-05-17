package com.skillforge.server.improve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.EvalScenarioEntity;
import com.skillforge.server.entity.PromptAbRunEntity;
import com.skillforge.server.entity.PromptVersionEntity;
import com.skillforge.server.eval.EvalEngineFactory;
import com.skillforge.server.eval.EvalJudgeTool;
import com.skillforge.server.eval.sandbox.SandboxSkillRegistryFactory;
import com.skillforge.server.eval.scenario.ScenarioLoader;
import com.skillforge.server.repository.PromptAbRunRepository;
import com.skillforge.server.repository.PromptVersionRepository;
import com.skillforge.server.service.AgentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * FLYWHEEL-LOOP-CLOSURE Phase 1.4c sub-task 1 (Ratify #7-A) — focused unit
 * test for the new attribution-baseline overload
 * {@link AbEvalPipeline#run(PromptAbRunEntity, PromptVersionEntity, PromptVersionEntity, AgentEntity, java.util.List)}.
 *
 * <p>Real per-scenario LLM execution requires a sandbox + agent loop + judge
 * pipeline that's only meaningful in a {@code @SpringBootTest} IT. This unit
 * test exercises the <b>empty-scenarios early-exit path</b> — proves the
 * overload exists, signature matches Ratify #7-A, and the abRun status
 * machine writes back FAILED with a clear failureReason (so the listener
 * catch path can mirror to {@code ab_failed} downstream). Full eval execution
 * coverage is deferred to Phase 1.4d / Phase 1.6 dogfood.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AbEvalPipeline attribution-baseline overload (Phase 1.4c sub-task 1)")
class AbEvalPipelineAttributionBaselineTest {

    @Mock private ScenarioLoader scenarioLoader;
    @Mock private SandboxSkillRegistryFactory sandboxFactory;
    @Mock private EvalEngineFactory evalEngineFactory;
    @Mock private EvalJudgeTool evalJudgeTool;
    @Mock private PromptAbRunRepository promptAbRunRepository;
    @Mock private PromptVersionRepository promptVersionRepository;
    @Mock private AgentService agentService;
    @Mock private ChatEventBroadcaster broadcaster;
    @Mock private ExecutorService loopExecutor;

    private AbEvalPipeline pipeline;

    @BeforeEach
    void setUp() {
        pipeline = new AbEvalPipeline(
                scenarioLoader, sandboxFactory, evalEngineFactory,
                evalJudgeTool, promptAbRunRepository, promptVersionRepository,
                agentService, new ObjectMapper(), broadcaster, loopExecutor);
    }

    @Test
    @DisplayName("attribution overload with empty scenarios → abRun status FAILED + clear "
            + "failureReason (listener catch path mirrors to ab_failed)")
    void run_emptyScenarios_marksAbRunFailed() {
        PromptAbRunEntity abRun = new PromptAbRunEntity();
        abRun.setId("ab-run-attribution-empty-scenarios");
        abRun.setAgentId("7");
        when(promptAbRunRepository.save(any(PromptAbRunEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        PromptVersionEntity candidate = new PromptVersionEntity();
        candidate.setId("candidate-v1");
        candidate.setContent("Improved system prompt");
        PromptVersionEntity baseline = new PromptVersionEntity();
        baseline.setId("baseline-v1");
        baseline.setContent("Old system prompt");
        AgentEntity agent = new AgentEntity();
        agent.setId(7L);

        // Empty scenarios → overload's early-exit branch.
        pipeline.run(abRun, candidate, baseline, agent, List.<EvalScenarioEntity>of());

        assertThat(abRun.getStatus()).isEqualTo("FAILED");
        assertThat(abRun.getFailureReason()).contains("No scenarios");
        assertThat(abRun.getCompletedAt()).isNotNull();
    }
}
