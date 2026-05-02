package com.skillforge.server.compact;

import com.skillforge.core.engine.AgentLoopEngine;
import com.skillforge.core.engine.CancellationRegistry;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.model.Message;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.ModelUsageRepository;
import com.skillforge.server.service.AgentService;
import com.skillforge.server.service.ChatService;
import com.skillforge.server.service.CompactionService;
import com.skillforge.server.service.SessionService;
import com.skillforge.server.service.SessionTitleService;
import com.skillforge.server.subagent.SubAgentRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the ordering invariant in {@link ChatService#chatAsync}: B3 engine-gap compact
 * must be called BEFORE {@link SessionService#saveSessionMessages} persists the new user
 * message. Otherwise the freshly added user message would be in the messages list when
 * compact runs, distorting the history that B3 is meant to compact.
 */
class ChatServiceB3OrderingTest {

    private AgentService agentService;
    private SessionService sessionService;
    private SkillRegistry skillRegistry;
    private AgentLoopEngine agentLoopEngine;
    private ModelUsageRepository modelUsageRepository;
    private ChatEventBroadcaster broadcaster;
    private SessionTitleService sessionTitleService;
    private SubAgentRegistry subAgentRegistry;
    private CancellationRegistry cancellationRegistry;
    private CompactionService compactionService;
    private ThreadPoolExecutor chatLoopExecutor;

    private ChatService chatService;

    @BeforeEach
    void setUp() {
        agentService = mock(AgentService.class);
        sessionService = mock(SessionService.class);
        skillRegistry = mock(SkillRegistry.class);
        agentLoopEngine = mock(AgentLoopEngine.class);
        modelUsageRepository = mock(ModelUsageRepository.class);
        broadcaster = mock(ChatEventBroadcaster.class);
        sessionTitleService = mock(SessionTitleService.class);
        subAgentRegistry = mock(SubAgentRegistry.class);
        cancellationRegistry = mock(CancellationRegistry.class);
        compactionService = mock(CompactionService.class);
        // a real executor with 0 core threads so runLoop is never actually invoked during the
        // unit test (we submit but the test doesn't wait for it). Alternatively, use a stub
        // executor that swallows submissions.
        chatLoopExecutor = new ThreadPoolExecutor(
                0, 1, 1L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(16)) {
            @Override
            public void execute(Runnable command) {
                // swallow — we only care about the synchronous ordering BEFORE execute().
            }
        };

        // compactionService must expose a real lock Object otherwise chatAsync will NPE
        when(compactionService.lockFor(anyString())).thenAnswer(inv -> new Object());

        chatService = new ChatService(agentService, sessionService, skillRegistry,
                agentLoopEngine, modelUsageRepository, broadcaster, chatLoopExecutor,
                sessionTitleService, subAgentRegistry, cancellationRegistry, compactionService,
                null, null, new com.fasterxml.jackson.databind.ObjectMapper(), null,
                new com.skillforge.server.hook.NoopLifecycleHookDispatcher(),
                new com.skillforge.core.engine.confirm.SessionConfirmCache(),
                new com.skillforge.core.engine.confirm.PendingConfirmationRegistry(),
                sid -> sid,
                org.mockito.Mockito.mock(com.skillforge.observability.api.LlmTraceStore.class));
    }

    private SessionEntity sessionWithGap(String id, long gapHours, int msgCount) {
        SessionEntity s = new SessionEntity();
        s.setId(id);
        s.setUserId(7L);
        s.setAgentId(1L);
        s.setMessageCount(msgCount);
        s.setLastUserMessageAt(Instant.now().minusSeconds(gapHours * 3600));
        s.setRuntimeStatus("idle");
        return s;
    }

    @Test
    void b3_compact_runs_before_saveSessionMessages_appends_new_user_message() {
        SessionEntity session = sessionWithGap("sid", 15, 20);  // 15h gap, 20 messages
        AgentEntity agent = new AgentEntity();
        agent.setId(1L);
        agent.setExecutionMode("auto");

        when(sessionService.getSession("sid")).thenReturn(session);
        when(agentService.getAgent(1L)).thenReturn(agent);
        when(sessionService.getSessionMessages("sid")).thenReturn(new ArrayList<>());
        when(sessionService.getContextMessages("sid")).thenReturn(new ArrayList<>());
        when(sessionService.getFullHistory("sid")).thenReturn(new ArrayList<>());

        chatService.chatAsync("sid", "hello again", 7L);

        // InOrder: compactionService.compact(...,"engine-gap",...) comes strictly before
        // sessionService.appendNormalMessages(...)
        InOrder io = inOrder(compactionService, sessionService);
        io.verify(compactionService).compact(eq("sid"), eq("light"), eq("engine-gap"), anyString());
        // OBS-2 M1: chatAsync now calls the 3-arg overload that carries traceId.
        io.verify(sessionService).appendNormalMessages(eq("sid"), any(), any());
    }

    @Test
    void b3_skipped_when_gap_below_threshold() {
        SessionEntity session = sessionWithGap("sid", 2, 20);  // 2h gap, below 12h threshold
        AgentEntity agent = new AgentEntity();
        agent.setId(1L);
        agent.setExecutionMode("auto");

        when(sessionService.getSession("sid")).thenReturn(session);
        when(agentService.getAgent(1L)).thenReturn(agent);
        when(sessionService.getSessionMessages("sid")).thenReturn(new ArrayList<>());
        when(sessionService.getContextMessages("sid")).thenReturn(new ArrayList<>());
        when(sessionService.getFullHistory("sid")).thenReturn(new ArrayList<>());

        chatService.chatAsync("sid", "hi", 7L);

        // compact() must NOT have been called
        verify(compactionService, org.mockito.Mockito.never())
                .compact(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void b3_skipped_for_child_session() {
        SessionEntity session = sessionWithGap("sid", 20, 20);  // 20h gap, but child
        session.setParentSessionId("parent-sid");
        AgentEntity agent = new AgentEntity();
        agent.setId(1L);
        agent.setExecutionMode("auto");

        when(sessionService.getSession("sid")).thenReturn(session);
        when(agentService.getAgent(1L)).thenReturn(agent);
        when(sessionService.getSessionMessages("sid")).thenReturn(new ArrayList<>());
        when(sessionService.getContextMessages("sid")).thenReturn(new ArrayList<>());
        when(sessionService.getFullHistory("sid")).thenReturn(new ArrayList<>());

        chatService.chatAsync("sid", "child msg", 7L);

        verify(compactionService, org.mockito.Mockito.never())
                .compact(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void b3_skipped_when_message_count_low() {
        SessionEntity session = sessionWithGap("sid", 20, 5);  // 20h gap but only 5 msgs
        AgentEntity agent = new AgentEntity();
        agent.setId(1L);
        agent.setExecutionMode("auto");

        when(sessionService.getSession("sid")).thenReturn(session);
        when(agentService.getAgent(1L)).thenReturn(agent);
        when(sessionService.getSessionMessages("sid")).thenReturn(new ArrayList<>());
        when(sessionService.getContextMessages("sid")).thenReturn(new ArrayList<>());
        when(sessionService.getFullHistory("sid")).thenReturn(new ArrayList<>());

        chatService.chatAsync("sid", "hi", 7L);

        verify(compactionService, org.mockito.Mockito.never())
                .compact(anyString(), anyString(), anyString(), anyString());
    }
}
