package com.skillforge.server.improve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.skill.SkillPackageLoader;
import com.skillforge.core.skill.SkillRegistry;
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

import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Surgical unit test for {@link SkillAbEvalService#runBaselineOnly} input
 * validation. The full happy-path is exercised by the BE-2 self-improve loop
 * test (which uses an end-to-end mocked AgentLoopEngine) and via the controller
 * MockMvc tests; this file pins the BE-1 contract surface that BE-2 will call.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SkillAbEvalService.runBaselineOnly")
class SkillAbEvalServiceRunBaselineOnlyTest {

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
                org.mockito.Mockito.mock(SkillEvalService.class));
        // runBaselineOnly does NOT invoke AbstractAbEvalRunner.run(), so the
        // mock SkillEvalService's run() is never called — no setup needed.
    }

    @Test
    @DisplayName("rejects null triggeredBy")
    void rejects_nullTriggeredBy() {
        assertThatThrownBy(() -> service.runBaselineOnly(1L, "10", 7L, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("triggeredBy");
        verify(skillRepository, never()).findById(1L);
    }

    @Test
    @DisplayName("rejects unrecognized triggeredBy")
    void rejects_unknownTriggeredBy() {
        assertThatThrownBy(() -> service.runBaselineOnly(1L, "10", 7L, null, "BOGUS"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(skillRepository, never()).findById(1L);
    }
}
