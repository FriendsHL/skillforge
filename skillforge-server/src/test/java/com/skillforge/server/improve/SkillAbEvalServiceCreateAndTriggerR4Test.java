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
import com.skillforge.server.improve.surface.SkillSurface;
import com.skillforge.server.repository.EvalTaskRepository;
import com.skillforge.server.repository.SkillAbRunRepository;
import com.skillforge.server.repository.SkillEvalHistoryRepository;
import com.skillforge.server.repository.SkillRepository;
import com.skillforge.server.service.AgentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FLYWHEEL-LOOP-CLOSURE Phase 1.6 dogfood R4 fix (2026-05-17) — focused unit
 * test for {@link SkillAbEvalService#createAndTrigger}'s deferred async
 * submit behaviour. Pre-R4 the {@code coordinatorExecutor.submit(...)} fired
 * immediately, racing the outer {@code @Transactional} commit — the async
 * thread's {@code findById(abRunId)} returned empty (1ms gap, 100% reproed
 * via dogfood event 19 abRun {@code bbe6e191}) → abRun stuck PENDING forever.
 *
 * <p>R4 fix uses {@link TransactionSynchronizationManager#registerSynchronization}
 * so the submit is deferred until {@code afterCommit}. This test simulates
 * an active tx synchronization context and verifies (a) immediate submit is
 * skipped, (b) firing the registered {@code afterCommit} callbacks then
 * dispatches the executor task.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SkillAbEvalService.createAndTrigger — R4 deferred-submit fix")
class SkillAbEvalServiceCreateAndTriggerR4Test {

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
                org.mockito.Mockito.mock(SkillSurface.class),
                org.mockito.Mockito.mock(SkillEvalService.class));
    }

    @AfterEach
    void tearDown() {
        // Ensure no test bleeds tx synchronization state into the next.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private SkillEntity skill(long id, Long parentId, boolean enabled) {
        SkillEntity s = new SkillEntity();
        s.setId(id);
        s.setName("Skill" + id);
        s.setParentSkillId(parentId);
        s.setEnabled(enabled);
        return s;
    }

    @Test
    @DisplayName("R4: caller inside @Transactional context → submit deferred until afterCommit "
            + "(no immediate executor.submit; one fired post-commit)")
    void createAndTrigger_inActiveTxSync_defersSubmitUntilAfterCommit() {
        // Arrange: fork pair so createAndTrigger's validation passes.
        when(skillRepository.findById(1L)).thenReturn(Optional.of(skill(1L, null, true)));
        when(skillRepository.findById(2L)).thenReturn(Optional.of(skill(2L, 1L, false)));
        when(skillAbRunRepository.findByCandidateSkillIdOrderByStartedAtDesc(2L))
                .thenReturn(java.util.Collections.emptyList());
        when(skillAbRunRepository.save(any(SkillAbRunEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // Simulate an active outer tx synchronization (what
        // @Transactional caller sets up via TransactionInterceptor + TxAspect).
        TransactionSynchronizationManager.initSynchronization();

        SkillAbRunEntity returned = service.createAndTrigger(
                /*parentSkillId*/ 1L, /*candidateSkillId*/ 2L,
                /*agentId*/ "7", /*baselineEvalRunId*/ null,
                /*triggeredByUserId*/ null);
        assertThat(returned).isNotNull();
        assertThat(returned.getId()).isNotBlank();

        // R4 invariant 1: NO immediate submit (pre-R4 would have fired here).
        verify(coordinatorExecutor, never()).submit(any(Runnable.class));
        // R4 invariant 2: exactly one synchronization registered (the deferred submit).
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);

        // Trigger afterCommit (simulates outer tx commit).
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCommit());

        // R4 invariant 3: submit fires AFTER commit (when DB row is visible).
        verify(coordinatorExecutor).submit(any(Runnable.class));
    }

    @Test
    @DisplayName("R4 backward-compat: no active tx → submit fires immediately "
            + "(legacy / direct-call code path unchanged)")
    void createAndTrigger_noActiveTxSync_submitsImmediately() {
        when(skillRepository.findById(1L)).thenReturn(Optional.of(skill(1L, null, true)));
        when(skillRepository.findById(2L)).thenReturn(Optional.of(skill(2L, 1L, false)));
        when(skillAbRunRepository.findByCandidateSkillIdOrderByStartedAtDesc(2L))
                .thenReturn(java.util.Collections.emptyList());
        when(skillAbRunRepository.save(any(SkillAbRunEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // No initSynchronization call — i.e. caller didn't set up an outer tx.
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();

        SkillAbRunEntity returned = service.createAndTrigger(
                1L, 2L, "7", null, null);

        assertThat(returned).isNotNull();
        // Backward-compat: immediate submit fires (no afterCommit waiting room).
        verify(coordinatorExecutor).submit(any(Runnable.class));
    }
}
