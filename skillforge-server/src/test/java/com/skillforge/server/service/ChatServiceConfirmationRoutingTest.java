package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.AgentLoopEngine;
import com.skillforge.core.engine.CancellationRegistry;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.engine.confirm.Decision;
import com.skillforge.core.engine.confirm.PendingConfirmation;
import com.skillforge.core.engine.confirm.PendingConfirmationRegistry;
import com.skillforge.core.engine.confirm.SessionConfirmCache;
import com.skillforge.core.engine.hook.LifecycleHookDispatcher;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.Message;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.observability.api.LlmTraceStore;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionMessageEntity;
import com.skillforge.server.repository.ModelUsageRepository;
import com.skillforge.server.subagent.SubAgentRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ACP-EXTERNAL-AGENT P1c-2 (Seam 2): {@link ChatService#answerConfirmation} is the
 * single confirmation door, discriminated internally by whether a persisted CONTROL
 * row exists for {@code (sessionId, CONFIRMATION, confirmationId)}.
 *
 * <ul>
 *   <li><b>control row present → ENGINE path</b>: unchanged — markControlAnswered +
 *       registry.complete + {@code completeConfirmedTool} + engine resume.</li>
 *   <li><b>no control row + registry pending bound to this session → ACP path</b>:
 *       registry.complete only; NO {@code completeConfirmedTool} / engine resume.</li>
 *   <li><b>no control row + confirmation bound to a DIFFERENT session</b> → reject
 *       (binding gate, P1b BLOCKER stays closed).</li>
 *   <li><b>neither control row nor registry pending</b> → unknown confirmation.</li>
 * </ul>
 *
 * <p>The session-ownership gate is enforced by the controller and is covered by the
 * ChatController-level tests; this test isolates the routing logic.
 */
@DisplayName("ChatService — unified confirmation routing (P1c-2 Seam 2)")
class ChatServiceConfirmationRoutingTest {

    private static final String SESSION_ID = "sub-1";
    private static final String CONF_ID = "conf-1";
    private static final Long USER_ID = 7L;

    private AgentService agentService;
    private SessionService sessionService;
    private AgentLoopEngine agentLoopEngine;
    private CompactionService compactionService;
    private PendingConfirmationRegistry registry;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        agentService = mock(AgentService.class);
        sessionService = mock(SessionService.class);
        SkillRegistry skillRegistry = mock(SkillRegistry.class);
        agentLoopEngine = mock(AgentLoopEngine.class);
        ModelUsageRepository modelUsageRepository = mock(ModelUsageRepository.class);
        ChatEventBroadcaster broadcaster = mock(ChatEventBroadcaster.class);
        SessionTitleService sessionTitleService = mock(SessionTitleService.class);
        SubAgentRegistry subAgentRegistry = mock(SubAgentRegistry.class);
        CancellationRegistry cancellationRegistry = mock(CancellationRegistry.class);
        compactionService = mock(CompactionService.class);
        registry = mock(PendingConfirmationRegistry.class);

        // Synchronous executor — engine-resume runLoop runs on the test thread.
        ThreadPoolExecutor sync = new ThreadPoolExecutor(
                0, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>(16)) {
            @Override
            public void execute(Runnable command) {
                command.run();
            }
        };
        lenient().when(compactionService.lockFor(anyString())).thenAnswer(inv -> new Object());

        chatService = new ChatService(agentService, sessionService, skillRegistry,
                agentLoopEngine, modelUsageRepository, broadcaster, sync,
                sessionTitleService, subAgentRegistry, cancellationRegistry, compactionService,
                null, null, new ObjectMapper(), null,
                new NoopDispatcher(),
                new SessionConfirmCache(), registry,
                sid -> sid, mock(LlmTraceStore.class),
                mock(org.springframework.context.ApplicationEventPublisher.class),
                null);
    }

    @Test
    @DisplayName("(a) control row present → ENGINE path: completeConfirmedTool invoked + registry completed")
    void controlRowPresent_routesEngine() {
        SessionMessageEntity control = new SessionMessageEntity();
        control.setMetadataJson(
                "{\"toolUseId\":\"tu-1\",\"toolName\":\"InstallSkill\",\"confirmationKind\":\"install\"}");
        when(sessionService.findControlMessage(SESSION_ID, SessionService.MESSAGE_TYPE_CONFIRMATION, CONF_ID))
                .thenReturn(Optional.of(control));

        SessionEntity session = new SessionEntity();
        session.setId(SESSION_ID);
        session.setUserId(USER_ID);
        session.setAgentId(100L);
        when(sessionService.getSession(SESSION_ID)).thenReturn(session);
        AgentEntity agent = new AgentEntity();
        agent.setId(100L);
        agent.setModelId("claude:claude-sonnet-4-20250514");
        when(agentService.getAgent(100L)).thenReturn(agent);
        AgentDefinition def = new AgentDefinition();
        def.setId("100");
        def.setModelId("claude:claude-sonnet-4-20250514");
        when(agentService.toAgentDefinition(agent)).thenReturn(def);
        when(sessionService.getContextMessages(SESSION_ID)).thenReturn(new ArrayList<>());
        when(sessionService.getActiveRootTraceId(SESSION_ID)).thenReturn("root-1");
        when(agentLoopEngine.completeConfirmedTool(any(AgentDefinition.class), eq(SESSION_ID), eq(USER_ID),
                eq("tu-1"), eq("InstallSkill"), any(), anyString(), any(), any(), eq(Decision.APPROVED)))
                .thenReturn(Message.user("tool result"));
        // The resume loop calls the 7-arg engine.run; return a minimal LoopResult so runLoop's tail completes.
        com.skillforge.core.engine.LoopResult loopResult = new com.skillforge.core.engine.LoopResult();
        loopResult.setMessages(new ArrayList<>());
        loopResult.setToolCalls(new ArrayList<>());
        lenient().when(agentLoopEngine.run(any(AgentDefinition.class), any(),
                any(Message.class), any(), anyString(), anyLong(),
                any(com.skillforge.core.engine.LoopContext.class)))
                .thenReturn(loopResult);

        chatService.answerConfirmation(SESSION_ID, CONF_ID, Decision.APPROVED, USER_ID);

        verify(agentLoopEngine).completeConfirmedTool(any(AgentDefinition.class), eq(SESSION_ID), eq(USER_ID),
                eq("tu-1"), eq("InstallSkill"), any(), anyString(), any(), any(), eq(Decision.APPROVED));
        verify(registry).complete(CONF_ID, Decision.APPROVED, null);
    }

    @Test
    @DisplayName("(b) no control row + registry pending bound to session → ACP path: registry.complete, NO engine resume")
    void noControlRow_registryBound_routesAcp() {
        when(sessionService.findControlMessage(SESSION_ID, SessionService.MESSAGE_TYPE_CONFIRMATION, CONF_ID))
                .thenReturn(Optional.empty());
        when(registry.peek(CONF_ID)).thenReturn(pendingFor(SESSION_ID));
        when(registry.complete(eq(CONF_ID), eq(Decision.APPROVED), any())).thenReturn(true);

        chatService.answerConfirmation(SESSION_ID, CONF_ID, Decision.APPROVED, USER_ID);

        verify(registry).complete(CONF_ID, Decision.APPROVED, null);
        // ACP path is a RECORD: the engine must NOT be resumed.
        verify(agentLoopEngine, never()).completeConfirmedTool(any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("(d) no control row + confirmation bound to a DIFFERENT session → unknown (binding gate)")
    void noControlRow_wrongSessionBinding_rejected() {
        when(sessionService.findControlMessage(SESSION_ID, SessionService.MESSAGE_TYPE_CONFIRMATION, CONF_ID))
                .thenReturn(Optional.empty());
        when(registry.peek(CONF_ID)).thenReturn(pendingFor("some-other-session"));

        assertThatThrownBy(() ->
                chatService.answerConfirmation(SESSION_ID, CONF_ID, Decision.APPROVED, USER_ID))
                .isInstanceOf(IllegalArgumentException.class);

        verify(registry, never()).complete(any(), any(), any());
    }

    @Test
    @DisplayName("(f) neither control row nor registry pending → unknown confirmation")
    void neither_unknown() {
        when(sessionService.findControlMessage(SESSION_ID, SessionService.MESSAGE_TYPE_CONFIRMATION, CONF_ID))
                .thenReturn(Optional.empty());
        when(registry.peek(CONF_ID)).thenReturn(null);

        assertThatThrownBy(() ->
                chatService.answerConfirmation(SESSION_ID, CONF_ID, Decision.APPROVED, USER_ID))
                .isInstanceOf(IllegalArgumentException.class);

        verify(registry, never()).complete(any(), any(), any());
    }

    private PendingConfirmation pendingFor(String sessionId) {
        return new PendingConfirmation(CONF_ID, sessionId, "tc-1", "edit", "x", "", null, 300);
    }

    /** Minimal hook dispatcher so the engine-resume path doesn't fire real hooks. */
    private static class NoopDispatcher implements LifecycleHookDispatcher {
        @Override
        public boolean dispatch(com.skillforge.core.engine.hook.HookEvent event,
                                Map<String, Object> input,
                                AgentDefinition agentDef,
                                String sessionId, Long userId) { return true; }

        @Override public boolean fireSessionStart(AgentDefinition d, String s, Long u) { return true; }
        @Override
        public boolean fireUserPromptSubmit(AgentDefinition d, String s, Long u, String m, int c) {
            return true;
        }
        @Override
        public void firePostToolUse(AgentDefinition d, String s, Long u, String name,
                                    Map<String, Object> in,
                                    com.skillforge.core.skill.SkillResult r, long ms) {}
        @Override
        public void fireStop(AgentDefinition d, String s, Long u, int loops,
                             long it, long ot, String response) {}
        @Override
        public void fireSessionEnd(AgentDefinition d, String s, Long u, int mc, String reason) {}
    }
}
