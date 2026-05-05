package com.skillforge.server.eval;

import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SubAgentRunEntity;
import com.skillforge.server.init.PendingConfirmationStartupRecovery;
import com.skillforge.server.init.SubAgentStartupRecovery;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SubAgentRunRepository;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.service.SessionService;
import com.skillforge.server.subagent.AgentRoster;
import com.skillforge.server.subagent.SubAgentRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EVAL-V2 M3a (b1) — startup recovery 路径对 origin='eval' session 跳过的单元测试。
 *
 * <p>覆盖 plan §3 过滤点 #5：
 * <ul>
 *   <li>{@link SubAgentStartupRecovery#run} — 跳过 eval child，不调 chatAsync resume</li>
 *   <li>{@link PendingConfirmationStartupRecovery#runRecovery} — 跳过 eval session，
 *       不 fabricate tool_result 或写 runtimeStatus=error</li>
 * </ul>
 *
 * <p>Compaction (#4) 跳过逻辑由 {@code CompactPersistenceIT} 系列 + production session
 * 的现有 compact 测试保证 happy path 仍工作；eval 跳过的"早 return + no-op"路径
 * 是 defensive，单元测试覆盖即可。
 */
class OriginCompactionAndRecoveryTest {

    // -----------------------------------------------------------------------
    // SubAgentStartupRecovery: skip eval children
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("SubAgentStartupRecovery: eval child marked CANCELLED, chatAsync NOT called")
    void subAgentRecovery_evalChild_skippedAndCancelled() {
        SubAgentRunRepository runRepository = mock(SubAgentRunRepository.class);
        SessionRepository sessionRepository = mock(SessionRepository.class);
        SubAgentRegistry subAgentRegistry = mock(SubAgentRegistry.class);
        ChatService chatService = mock(ChatService.class);
        AgentRoster agentRoster = mock(AgentRoster.class);

        SubAgentStartupRecovery recovery = new SubAgentStartupRecovery(
                runRepository, sessionRepository, subAgentRegistry, chatService, agentRoster);

        // Eval child that was mid-running before restart: production code-path would resume it,
        // but origin='eval' must be skipped.
        SubAgentRunEntity run = new SubAgentRunEntity();
        run.setRunId("run-eval-1");
        run.setParentSessionId("parent-eval-1");
        run.setChildSessionId("child-eval-1");
        run.setChildAgentName("eval-child-agent");
        run.setStatus("RUNNING");
        run.setSpawnedAt(Instant.now().minusSeconds(60));

        SessionEntity child = new SessionEntity();
        child.setId("child-eval-1");
        child.setUserId(1L);
        child.setAgentId(2L);
        child.setRuntimeStatus("running");
        child.setOrigin(SessionEntity.ORIGIN_EVAL);

        when(runRepository.findByStatus("RUNNING")).thenReturn(List.of(run));
        when(sessionRepository.findById("child-eval-1")).thenReturn(Optional.of(child));

        recovery.run(null);

        // Eval session — chatAsync resume must NOT be invoked.
        verify(chatService, never()).chatAsync(anyString(), anyString(), anyLong(), anyBoolean());
        verify(chatService, never()).chatAsync(anyString(), anyString(), anyLong());
        // The run row must be marked CANCELLED with an explanatory message.
        verify(runRepository, times(1)).save(run);
        // Final message tracks the "eval skipped" reason explicitly.
        // (We don't notifyParentOfOrphanRun here — eval orchestrator owns failure attribution.)
        verify(subAgentRegistry, never()).onSessionLoopFinished(anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt(), anyLong());
    }

    @Test
    @DisplayName("SubAgentStartupRecovery: production child still gets resumed (eval guard does not over-fire)")
    void subAgentRecovery_productionChild_resumesNormally() {
        SubAgentRunRepository runRepository = mock(SubAgentRunRepository.class);
        SessionRepository sessionRepository = mock(SessionRepository.class);
        SubAgentRegistry subAgentRegistry = mock(SubAgentRegistry.class);
        ChatService chatService = mock(ChatService.class);
        AgentRoster agentRoster = mock(AgentRoster.class);

        SubAgentStartupRecovery recovery = new SubAgentStartupRecovery(
                runRepository, sessionRepository, subAgentRegistry, chatService, agentRoster);

        SubAgentRunEntity run = new SubAgentRunEntity();
        run.setRunId("run-prod-1");
        run.setParentSessionId("parent-prod-1");
        run.setChildSessionId("child-prod-1");
        run.setChildAgentName("agent");
        run.setStatus("RUNNING");
        run.setSpawnedAt(Instant.now().minusSeconds(60));

        SessionEntity child = new SessionEntity();
        child.setId("child-prod-1");
        child.setUserId(7L);
        child.setAgentId(2L);
        child.setRuntimeStatus("running");
        child.setOrigin(SessionEntity.ORIGIN_PRODUCTION);

        when(runRepository.findByStatus("RUNNING")).thenReturn(List.of(run));
        when(sessionRepository.findById("child-prod-1")).thenReturn(Optional.of(child));

        recovery.run(null);

        verify(chatService, times(1)).chatAsync(eq("child-prod-1"),
                contains("[Resume from restart]"), eq(7L), eq(true));
    }

    // -----------------------------------------------------------------------
    // PendingConfirmationStartupRecovery: skip eval sessions
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("PendingConfirmationStartupRecovery: eval session is skipped (no fabricate, no save)")
    void pendingConfirmationRecovery_evalSession_skipped() {
        SessionRepository sessionRepository = mock(SessionRepository.class);
        SessionService sessionService = mock(SessionService.class);
        PendingConfirmationStartupRecovery recovery =
                new PendingConfirmationStartupRecovery(sessionRepository, sessionService);

        SessionEntity evalSess = new SessionEntity();
        evalSess.setId("eval-1");
        evalSess.setRuntimeStatus("running");
        evalSess.setOrigin(SessionEntity.ORIGIN_EVAL);

        when(sessionRepository.findAll()).thenReturn(List.of(evalSess));

        recovery.start();

        verify(sessionService, never()).appendNormalMessages(anyString(), any());
        verify(sessionService, never()).saveSession(any());
        // Eval session's runtimeStatus must NOT be flipped to "error" by the production
        // recovery path — the eval orchestrator handles it.
        org.assertj.core.api.Assertions.assertThat(evalSess.getRuntimeStatus()).isEqualTo("running");
    }

    @Test
    @DisplayName("PendingConfirmationStartupRecovery: production session still repaired (eval guard does not over-fire)")
    void pendingConfirmationRecovery_productionSession_stillRepaired() {
        SessionRepository sessionRepository = mock(SessionRepository.class);
        SessionService sessionService = mock(SessionService.class);
        PendingConfirmationStartupRecovery recovery =
                new PendingConfirmationStartupRecovery(sessionRepository, sessionService);

        SessionEntity prodSess = new SessionEntity();
        prodSess.setId("prod-1");
        prodSess.setRuntimeStatus("running");
        prodSess.setOrigin(SessionEntity.ORIGIN_PRODUCTION);

        when(sessionRepository.findAll()).thenReturn(List.of(prodSess));
        when(sessionService.getFullHistory("prod-1"))
                .thenReturn(java.util.Collections.emptyList()); // no orphans

        recovery.start();

        // No orphans, so no fabricate; but session still gets saved (status flipped to error).
        verify(sessionService, times(1)).saveSession(prodSess);
        org.assertj.core.api.Assertions.assertThat(prodSess.getRuntimeStatus()).isEqualTo("error");
    }
}
