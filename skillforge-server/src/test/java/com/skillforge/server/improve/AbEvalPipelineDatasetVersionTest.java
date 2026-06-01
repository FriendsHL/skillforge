package com.skillforge.server.improve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.EvalDatasetVersionEntity;
import com.skillforge.server.entity.EvalScenarioEntity;
import com.skillforge.server.entity.PromptAbRunEntity;
import com.skillforge.server.entity.PromptVersionEntity;
import com.skillforge.server.eval.EvalEngineFactory;
import com.skillforge.server.eval.EvalJudgeTool;
import com.skillforge.server.eval.sandbox.SandboxSkillRegistryFactory;
import com.skillforge.server.eval.scenario.ScenarioLoader;
import com.skillforge.server.repository.EvalDatasetVersionRepository;
import com.skillforge.server.repository.PromptAbRunRepository;
import com.skillforge.server.repository.PromptVersionRepository;
import com.skillforge.server.service.AgentService;
import com.skillforge.server.service.EvalDatasetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * EVAL-DATASET-LAYER V1: focused unit tests for the new dataset-version
 * overload {@link AbEvalPipeline#run(PromptAbRunEntity, PromptVersionEntity,
 * PromptVersionEntity, AgentEntity, String)}.
 *
 * <p>Mirrors {@link AbEvalPipelineAttributionBaselineTest}'s scope: the real
 * per-scenario LLM execution requires {@code @SpringBootTest} IT, but the
 * empty-scenarios early-exit + abRun field write-back can be exercised
 * unit-level.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AbEvalPipeline dataset-version overload (EVAL-DATASET-LAYER V1)")
class AbEvalPipelineDatasetVersionTest {

    @Mock private ScenarioLoader scenarioLoader;
    @Mock private SandboxSkillRegistryFactory sandboxFactory;
    @Mock private EvalEngineFactory evalEngineFactory;
    @Mock private EvalJudgeTool evalJudgeTool;
    @Mock private PromptAbRunRepository promptAbRunRepository;
    @Mock private PromptVersionRepository promptVersionRepository;
    @Mock private AgentService agentService;
    @Mock private ChatEventBroadcaster broadcaster;
    @Mock private ExecutorService loopExecutor;
    @Mock private EvalDatasetService evalDatasetService;
    @Mock private EvalDatasetVersionRepository evalDatasetVersionRepository;

    private AbEvalPipeline pipeline;

    @BeforeEach
    void setUp() {
        pipeline = new AbEvalPipeline(
                scenarioLoader, sandboxFactory, evalEngineFactory,
                evalJudgeTool, promptAbRunRepository, promptVersionRepository,
                agentService, new ObjectMapper(), broadcaster, loopExecutor,
                120_000L, evalDatasetService, evalDatasetVersionRepository, 3, 0);
    }

    @Test
    @DisplayName("null datasetVersionId → IllegalArgumentException")
    void nullDatasetVersionIdRejected() {
        assertThatThrownBy(() -> pipeline.run(
                new PromptAbRunEntity(), new PromptVersionEntity(),
                new PromptVersionEntity(), new AgentEntity(), (String) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("datasetVersionId");
    }

    @Test
    @DisplayName("blank datasetVersionId → IllegalArgumentException")
    void blankDatasetVersionIdRejected() {
        assertThatThrownBy(() -> pipeline.run(
                new PromptAbRunEntity(), new PromptVersionEntity(),
                new PromptVersionEntity(), new AgentEntity(), "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("dataset-version path: empty scenarios → abRun FAILED + datasetVersionId still set")
    void emptyScenariosAbRunFailedButDatasetVersionIdPinned() {
        PromptAbRunEntity abRun = new PromptAbRunEntity();
        abRun.setId("ab-run-ds-empty");
        abRun.setAgentId("7");

        when(promptAbRunRepository.save(any(PromptAbRunEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(evalDatasetService.getScenariosForVersion("ds-v-empty"))
                .thenReturn(List.of());

        PromptVersionEntity candidate = new PromptVersionEntity();
        candidate.setId("candidate-v1");
        candidate.setContent("c");
        PromptVersionEntity baseline = new PromptVersionEntity();
        baseline.setId("baseline-v1");
        baseline.setContent("b");
        AgentEntity agent = new AgentEntity();
        agent.setId(7L);

        pipeline.run(abRun, candidate, baseline, agent, "ds-v-empty");

        // abRun is marked FAILED but datasetVersionId is still pinned for audit/forensics.
        assertThat(abRun.getStatus()).isEqualTo("FAILED");
        assertThat(abRun.getDatasetVersionId()).isEqualTo("ds-v-empty");
        assertThat(abRun.getFailureReason()).contains("No scenarios");
        assertThat(abRun.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("dataset-version path without EvalDatasetService wired → IllegalStateException")
    void datasetPathRequiresServiceWired() {
        // Re-build pipeline with the legacy ctor (null collaborators).
        AbEvalPipeline narrow = new AbEvalPipeline(
                scenarioLoader, sandboxFactory, evalEngineFactory,
                evalJudgeTool, promptAbRunRepository, promptVersionRepository,
                agentService, new ObjectMapper(), broadcaster, loopExecutor,
                120_000L);
        PromptAbRunEntity abRun = new PromptAbRunEntity();
        abRun.setId("ab-run-no-svc");
        abRun.setAgentId("7");

        assertThatThrownBy(() -> narrow.run(abRun, new PromptVersionEntity(),
                new PromptVersionEntity(), new AgentEntity(), "ds-v-any"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EvalDatasetService");
    }

    @Test
    @DisplayName("legacy List overload still works (deprecation does not break behaviour)")
    @SuppressWarnings("deprecation")
    void legacyOverloadStillCallable() {
        PromptAbRunEntity abRun = new PromptAbRunEntity();
        abRun.setId("ab-run-legacy");
        abRun.setAgentId("7");
        when(promptAbRunRepository.save(any(PromptAbRunEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        pipeline.run(abRun, new PromptVersionEntity(), new PromptVersionEntity(),
                new AgentEntity(), List.<EvalScenarioEntity>of());

        assertThat(abRun.getStatus()).isEqualTo("FAILED");
        assertThat(abRun.getDatasetVersionId()).isNull(); // legacy path leaves it null
    }
}
