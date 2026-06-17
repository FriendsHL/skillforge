package com.skillforge.server.improve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.skill.SkillPackageLoader;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.entity.SkillAbRunEntity;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.eval.EvalEngineFactory;
import com.skillforge.server.eval.EvalJudgeTool;
import com.skillforge.server.eval.sandbox.SandboxSkillRegistryFactory;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * EVAL-DATASET-LAYER V1 r2 mandatory fix: pins the silent-failure-guard
 * invariant — {@link SkillAbEvalService#createAndTrigger(Long, Long, String,
 * String, Long, String)} must persist {@code datasetVersionId} on the
 * {@link SkillAbRunEntity} so the FE selection doesn't get dropped.
 *
 * <p>Mirrors {@link AbEvalPipelineDatasetVersionTest} scope: real
 * async-execution wiring is exercised by the SpringBoot IT layer; this unit
 * test only locks the persistence contract.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SkillAbEvalService.createAndTrigger dataset_version_id persistence")
class SkillAbEvalServiceDatasetVersionTest {

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
    @Mock private ObjectMapper objectMapper;
    @Mock private ChatEventBroadcaster broadcaster;
    @Mock private ExecutorService coordinatorExecutor;
    @Mock private ExecutorService loopExecutor;
    @Mock private SkillRegistry skillRegistry;
    @Mock private com.skillforge.server.event.SkillAbCompletedEventPublisher abCompletedEventPublisher;

    private SkillAbEvalService service;

    @BeforeEach
    void setUp() {
        service = new SkillAbEvalService(
                skillRepository, skillAbRunRepository, evalRunRepository,
                skillEvalHistoryRepository, agentService, scenarioLoader,
                sandboxFactory, evalEngineFactory, evalJudgeTool, skillPackageLoader,
                objectMapper, broadcaster, coordinatorExecutor, loopExecutor,
                skillRegistry, abCompletedEventPublisher,
                org.mockito.Mockito.mock(com.skillforge.server.improve.surface.SkillSurface.class),
                org.mockito.Mockito.mock(SkillEvalService.class),
                120_000L,
                new com.skillforge.server.config.EvolveThresholdProperties());
    }

    @Test
    @DisplayName("6-arg overload writes datasetVersionId onto the persisted SkillAbRunEntity")
    void writesDatasetVersionIdOnNewRun() {
        // Arrange — parent skill + candidate (fork of parent) exist, no in-progress run.
        SkillEntity parent = new SkillEntity();
        parent.setId(1L);
        SkillEntity candidate = new SkillEntity();
        candidate.setId(2L);
        candidate.setParentSkillId(1L);
        candidate.setEnabled(false);
        when(skillRepository.findById(1L)).thenReturn(Optional.of(parent));
        when(skillRepository.findById(2L)).thenReturn(Optional.of(candidate));
        when(skillAbRunRepository.findByCandidateSkillIdOrderByStartedAtDesc(2L))
                .thenReturn(List.of());
        when(skillAbRunRepository.save(any(SkillAbRunEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // Act — supply datasetVersionId via the new 6-arg overload.
        SkillAbRunEntity saved = service.createAndTrigger(
                1L, 2L, "agent-7", "baseline-eval-run", 99L, "ds-v-abc");

        // Assert — the value lands on the entity (silent-failure guard).
        assertThat(saved.getDatasetVersionId()).isEqualTo("ds-v-abc");
    }

    @Test
    @DisplayName("legacy 5-arg overload still works (datasetVersionId stays null)")
    void legacyOverloadStillWorks() {
        SkillEntity parent = new SkillEntity();
        parent.setId(1L);
        SkillEntity candidate = new SkillEntity();
        candidate.setId(2L);
        candidate.setParentSkillId(1L);
        candidate.setEnabled(false);
        when(skillRepository.findById(1L)).thenReturn(Optional.of(parent));
        when(skillRepository.findById(2L)).thenReturn(Optional.of(candidate));
        when(skillAbRunRepository.findByCandidateSkillIdOrderByStartedAtDesc(2L))
                .thenReturn(List.of());
        when(skillAbRunRepository.save(any(SkillAbRunEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SkillAbRunEntity saved = service.createAndTrigger(
                1L, 2L, "agent-7", "baseline-eval-run", 99L);

        assertThat(saved.getDatasetVersionId()).isNull();
    }

    @Test
    @DisplayName("blank datasetVersionId is normalised to null (FE-empty-string safety)")
    void blankDatasetVersionIdNormalised() {
        SkillEntity parent = new SkillEntity();
        parent.setId(1L);
        SkillEntity candidate = new SkillEntity();
        candidate.setId(2L);
        candidate.setParentSkillId(1L);
        candidate.setEnabled(false);
        when(skillRepository.findById(1L)).thenReturn(Optional.of(parent));
        when(skillRepository.findById(2L)).thenReturn(Optional.of(candidate));
        when(skillAbRunRepository.findByCandidateSkillIdOrderByStartedAtDesc(2L))
                .thenReturn(List.of());
        when(skillAbRunRepository.save(any(SkillAbRunEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SkillAbRunEntity saved = service.createAndTrigger(
                1L, 2L, "agent-7", "baseline-eval-run", 99L, "   ");

        assertThat(saved.getDatasetVersionId()).isNull();
    }
}
