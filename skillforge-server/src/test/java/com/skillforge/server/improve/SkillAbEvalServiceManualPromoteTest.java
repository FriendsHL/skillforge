package com.skillforge.server.improve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.skill.SkillPackageLoader;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.entity.SkillAbRunEntity;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.event.SkillAbCompletedEventPublisher;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SKILL-DASHBOARD-POLISH D — verify {@link SkillAbEvalService#manualPromote}.
 * Covers:
 * <ul>
 *   <li>Happy path: COMPLETED + not promoted + candidate disabled → reuses
 *       {@code promoteCandidate} (V64-safe order), stamps promoted=true, never
 *       publishes {@link SkillAbCompletedEventPublisher} (INV-9 — auto only).</li>
 *   <li>Status not COMPLETED (e.g. RUNNING) → throws.</li>
 *   <li>Already promoted → throws (idempotency boundary).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SkillAbEvalService.manualPromote")
class SkillAbEvalServiceManualPromoteTest {

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
    @Mock private SkillAbCompletedEventPublisher abCompletedEventPublisher;

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
        // manualPromote does NOT invoke AbstractAbEvalRunner.run() (direct
        // V64-safe promote path), so no SkillEvalService.run setup needed.
    }

    private SkillAbRunEntity newAbRun(String status, boolean promoted,
                                      Long parentSkillId, Long candidateSkillId) {
        SkillAbRunEntity r = new SkillAbRunEntity();
        r.setId("ab-1");
        r.setStatus(status);
        r.setPromoted(promoted);
        r.setParentSkillId(parentSkillId);
        r.setCandidateSkillId(candidateSkillId);
        r.setAgentId("99");
        r.setBaselinePassRate(50.0);
        r.setCandidatePassRate(58.0);
        r.setDeltaPassRate(8.0);
        return r;
    }

    private SkillEntity newSkill(Long id, Long parentSkillId, boolean enabled) {
        SkillEntity s = new SkillEntity();
        s.setId(id);
        s.setName("MySkill");
        s.setParentSkillId(parentSkillId);
        s.setEnabled(enabled);
        s.setSemver(parentSkillId == null ? "v1" : "v2");
        return s;
    }

    @Test
    @DisplayName("happy path: COMPLETED + not promoted → promotes, never publishes event")
    void happyPath_promotesAndNoEvent() {
        SkillAbRunEntity abRun = newAbRun("COMPLETED", false, 10L, 20L);
        SkillEntity parent = newSkill(10L, null, true);
        SkillEntity candidate = newSkill(20L, 10L, false);
        when(skillAbRunRepository.findById("ab-1")).thenReturn(Optional.of(abRun));
        when(skillRepository.findById(20L)).thenReturn(Optional.of(candidate));
        // promoteCandidate's internal lookup of the parent.
        when(skillRepository.findById(10L)).thenReturn(Optional.of(parent));
        when(skillAbRunRepository.save(any(SkillAbRunEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        SkillAbRunEntity result = service.manualPromote("ab-1", 7L);

        // V64-safe order: parent disabled+flushed FIRST, then candidate enabled.
        ArgumentCaptor<SkillEntity> saveAndFlushCap = ArgumentCaptor.forClass(SkillEntity.class);
        verify(skillRepository).saveAndFlush(saveAndFlushCap.capture());
        assertThat(saveAndFlushCap.getValue().getId()).isEqualTo(10L);
        assertThat(saveAndFlushCap.getValue().isEnabled()).isFalse();

        // Candidate enabled after the flush.
        assertThat(candidate.isEnabled()).isTrue();
        // promoted flag stamped.
        assertThat(result.isPromoted()).isTrue();
        assertThat(result.getSkipReason()).contains("manual override").contains("userId=7");

        // INV-9: manual promote does NOT publish SkillAbCompletedEvent.
        verify(abCompletedEventPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("status not COMPLETED → throws, no promote")
    void statusNotCompleted_throws() {
        SkillAbRunEntity abRun = newAbRun("RUNNING", false, 10L, 20L);
        when(skillAbRunRepository.findById("ab-1")).thenReturn(Optional.of(abRun));

        assertThatThrownBy(() -> service.manualPromote("ab-1", 7L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("COMPLETED");

        verify(skillRepository, never()).saveAndFlush(any());
        // abRun not saved with promoted=true since the precondition failed.
        verify(skillAbRunRepository, never()).save(any());
        verify(abCompletedEventPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("already promoted → throws")
    void alreadyPromoted_throws() {
        SkillAbRunEntity abRun = newAbRun("COMPLETED", true, 10L, 20L);
        when(skillAbRunRepository.findById("ab-1")).thenReturn(Optional.of(abRun));

        assertThatThrownBy(() -> service.manualPromote("ab-1", 7L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("already promoted");

        verify(skillRepository, never()).saveAndFlush(any());
        verify(skillAbRunRepository, never()).save(any());
    }

    @Test
    @DisplayName("abRun id not found → throws")
    void abRunNotFound_throws() {
        when(skillAbRunRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.manualPromote("missing", 7L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not found");
    }
}
