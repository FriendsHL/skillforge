package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.AgentLoopEngine;
import com.skillforge.core.engine.CancellationRegistry;
import com.skillforge.core.engine.confirm.PendingConfirmationRegistry;
import com.skillforge.core.engine.confirm.SessionConfirmCache;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.observability.api.LlmTraceStore;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.exception.RetryBusyException;
import com.skillforge.server.memory.SessionDigestExtractor;
import com.skillforge.server.repository.ModelUsageRepository;
import com.skillforge.server.subagent.SubAgentRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceLocalTeardownAdmissionTest {

    @Test
    void idleDatabaseStateCannotAdmitWhilePreviousLocalLoopIsTearingDown() {
        AgentService agentService = mock(AgentService.class);
        SessionService sessionService = mock(SessionService.class);
        CompactionService compactionService = mock(CompactionService.class);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                0, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>(1));
        ChatService service = new ChatService(
                agentService,
                sessionService,
                mock(SkillRegistry.class),
                mock(AgentLoopEngine.class),
                mock(ModelUsageRepository.class),
                null,
                executor,
                mock(SessionTitleService.class),
                mock(SubAgentRegistry.class),
                mock(CancellationRegistry.class),
                compactionService,
                null,
                null,
                new ObjectMapper(),
                mock(SessionDigestExtractor.class),
                new com.skillforge.server.hook.NoopLifecycleHookDispatcher(),
                new SessionConfirmCache(),
                new PendingConfirmationRegistry(),
                sessionId -> sessionId,
                mock(LlmTraceStore.class),
                mock(org.springframework.context.ApplicationEventPublisher.class),
                null);
        try {
            SessionEntity session = new SessionEntity();
            session.setId("teardown-session");
            session.setAgentId(42L);
            session.setRuntimeStatus("idle");
            when(sessionService.getSession("teardown-session")).thenReturn(session);
            when(agentService.getAgent(42L)).thenReturn(new AgentEntity());
            when(compactionService.lockFor("teardown-session")).thenReturn(new Object());
            service.markLoopTaskStarted("teardown-session");

            assertThatThrownBy(() -> service.chatAsync(
                    "teardown-session", "new turn", 7L))
                    .isInstanceOf(RetryBusyException.class);

            verify(sessionService, never()).getFullHistory(anyString());
            verify(sessionService, never()).appendNormalMessages(
                    anyString(), org.mockito.ArgumentMatchers.anyList(), anyString());
        } finally {
            service.markLoopTaskFinished("teardown-session");
            executor.shutdownNow();
        }
    }
}
