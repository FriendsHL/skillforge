package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.AgentLoopEngine;
import com.skillforge.core.engine.CancellationRegistry;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.engine.hook.HookEvent;
import com.skillforge.core.engine.hook.LifecycleHookDispatcher;
import com.skillforge.core.engine.hook.LifecycleHooksConfig;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.ModelUsageRepository;
import com.skillforge.server.subagent.SubAgentRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Lightweight unit test covering ChatService → LifecycleHookDispatcher wiring.
 *
 * <p>Uses a hand-rolled dispatcher spy instead of Mockito because we want behavior assertions
 * (was fireSessionStart called? did it block the executor submit when ABORT?) rather than
 * mock verification noise.
 */
class ChatServiceLifecycleHookTest {

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
    private AtomicBoolean loopSubmitted;

    private RecordingDispatcher dispatcher;
    private ChatService chatService;

    private static class RecordingDispatcher implements LifecycleHookDispatcher {
        boolean sessionStartResult = true;
        final AtomicInteger sessionStartCalls = new AtomicInteger();
        final AtomicInteger sessionEndCalls = new AtomicInteger();

        @Override
        public boolean dispatch(HookEvent event, Map<String, Object> input,
                                AgentDefinition agentDef, String sessionId, Long userId) {
            return true;
        }

        @Override
        public boolean fireSessionStart(AgentDefinition agentDef, String sessionId, Long userId) {
            sessionStartCalls.incrementAndGet();
            return sessionStartResult;
        }

        @Override
        public boolean fireUserPromptSubmit(AgentDefinition agentDef, String sessionId, Long userId,
                                            String userMessage, int messageCount) { return true; }

        @Override
        public void firePostToolUse(AgentDefinition agentDef, String sessionId, Long userId,
                                    String skillName, Map<String, Object> skillInput, SkillResult result,
                                    long durationMs) {}

        @Override
        public void fireStop(AgentDefinition agentDef, String sessionId, Long userId, int loopCount,
                             long inputTokens, long outputTokens, String finalResponse) {}

        @Override
        public void fireSessionEnd(AgentDefinition agentDef, String sessionId, Long userId,
                                   int messageCount, String reason) {
            sessionEndCalls.incrementAndGet();
        }
    }

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
        loopSubmitted = new AtomicBoolean(false);
        chatLoopExecutor = new ThreadPoolExecutor(
                0, 1, 1L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(16)) {
            @Override
            public void execute(Runnable command) {
                loopSubmitted.set(true);
                // do not actually run — we only assert whether it was submitted
            }
        };
        when(compactionService.lockFor(anyString())).thenAnswer(inv -> new Object());
        dispatcher = new RecordingDispatcher();
        chatService = new ChatService(agentService, sessionService, skillRegistry,
                agentLoopEngine, modelUsageRepository, broadcaster, chatLoopExecutor,
                sessionTitleService, subAgentRegistry, cancellationRegistry, compactionService,
                null, null, new ObjectMapper(), null, dispatcher);
    }

    private SessionEntity freshSession(String id) {
        SessionEntity s = new SessionEntity();
        s.setId(id);
        s.setUserId(7L);
        s.setAgentId(1L);
        s.setMessageCount(0);
        s.setLastUserMessageAt(Instant.now());
        s.setRuntimeStatus("idle");
        s.setMessagesJson("[]");
        return s;
    }

    @Test
    @DisplayName("SessionStart hook is fired exactly once on the first user message of a session")
    void sessionStart_firedOnceOnFirstMessage() {
        SessionEntity session = freshSession("sess-1");
        when(sessionService.getSession("sess-1")).thenReturn(session);
        AgentEntity agent = new AgentEntity();
        agent.setId(1L);
        agent.setName("A");
        when(agentService.getAgent(1L)).thenReturn(agent);
        AgentDefinition def = new AgentDefinition();
        def.setId("1");
        def.setName("A");
        when(agentService.toAgentDefinition(agent)).thenReturn(def);
        when(sessionService.getSessionMessages("sess-1")).thenReturn(new java.util.ArrayList<>());

        chatService.chatAsync("sess-1", "hello", 7L);

        assertThat(dispatcher.sessionStartCalls.get()).isEqualTo(1);
        assertThat(loopSubmitted.get()).isTrue();
    }

    @Test
    @DisplayName("SessionStart ABORT prevents chat loop from running")
    void sessionStart_abort_preventsLoopSubmission() {
        dispatcher.sessionStartResult = false; // force ABORT
        SessionEntity session = freshSession("sess-2");
        when(sessionService.getSession("sess-2")).thenReturn(session);
        AgentEntity agent = new AgentEntity();
        agent.setId(1L);
        agent.setName("A");
        when(agentService.getAgent(1L)).thenReturn(agent);
        AgentDefinition def = new AgentDefinition();
        def.setId("1");
        def.setName("A");
        def.setLifecycleHooks(new LifecycleHooksConfig());
        when(agentService.toAgentDefinition(agent)).thenReturn(def);
        when(sessionService.getSessionMessages("sess-2")).thenReturn(new java.util.ArrayList<>());

        chatService.chatAsync("sess-2", "forbidden", 7L);

        assertThat(dispatcher.sessionStartCalls.get()).isEqualTo(1);
        assertThat(loopSubmitted.get()).as("loop must not be submitted after SessionStart ABORT").isFalse();
        verify(broadcaster, atLeastOnce()).sessionStatus(eq("sess-2"), eq("error"), any(), anyString());
    }

    @Test
    @DisplayName("SessionStart is NOT fired when history is non-empty (mid-session turn)")
    void sessionStart_notFiredMidSession() {
        SessionEntity session = freshSession("sess-3");
        when(sessionService.getSession("sess-3")).thenReturn(session);
        AgentEntity agent = new AgentEntity();
        agent.setId(1L);
        agent.setName("A");
        when(agentService.getAgent(1L)).thenReturn(agent);
        // Simulate history with 1 previous message:
        var prior = new java.util.ArrayList<com.skillforge.core.model.Message>();
        prior.add(com.skillforge.core.model.Message.user("earlier"));
        when(sessionService.getSessionMessages("sess-3")).thenReturn(prior);

        chatService.chatAsync("sess-3", "next turn", 7L);

        assertThat(dispatcher.sessionStartCalls.get()).isEqualTo(0);
        assertThat(loopSubmitted.get()).isTrue();
    }
}
