package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.AgentLoopEngine;
import com.skillforge.core.engine.CancellationRegistry;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.engine.confirm.Decision;
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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceResumeRejectionTest {

    private static final String SESSION_ID = "session-1";
    private static final Long USER_ID = 7L;

    private AgentService agentService;
    private SessionService sessionService;
    private AgentLoopEngine agentLoopEngine;
    private CompactionService compactionService;
    private PendingConfirmationRegistry confirmationRegistry;
    private ChatEventBroadcaster broadcaster;
    private LifecycleHookDispatcher lifecycleHookDispatcher;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        agentService = mock(AgentService.class);
        sessionService = mock(SessionService.class);
        agentLoopEngine = mock(AgentLoopEngine.class);
        compactionService = mock(CompactionService.class);
        confirmationRegistry = mock(PendingConfirmationRegistry.class);
        lifecycleHookDispatcher = mock(LifecycleHookDispatcher.class);
        ThreadPoolExecutor rejectingExecutor = mock(ThreadPoolExecutor.class);
        doThrow(new RejectedExecutionException("queue full"))
                .when(rejectingExecutor).execute(any(Runnable.class));
        when(compactionService.lockFor(anyString())).thenAnswer(invocation -> new Object());

        broadcaster = mock(ChatEventBroadcaster.class);
        chatService = new ChatService(
                agentService,
                sessionService,
                mock(SkillRegistry.class),
                agentLoopEngine,
                mock(ModelUsageRepository.class),
                broadcaster,
                rejectingExecutor,
                mock(SessionTitleService.class),
                mock(SubAgentRegistry.class),
                mock(CancellationRegistry.class),
                compactionService,
                null,
                null,
                new ObjectMapper(),
                null,
                lifecycleHookDispatcher,
                new SessionConfirmCache(),
                confirmationRegistry,
                sessionId -> sessionId,
                mock(LlmTraceStore.class),
                mock(org.springframework.context.ApplicationEventPublisher.class),
                null);
    }

    @Test
    void initialChat_rejectedExecutor_persistsRetryableStructuredFailure() {
        SessionEntity session = stubSessionAndAgent();
        session.setRuntimeStatus("idle");
        Message prior = Message.user("earlier");
        when(sessionService.getFullHistory(SESSION_ID)).thenReturn(List.of(prior));
        when(sessionService.getContextMessages(SESSION_ID)).thenReturn(List.of(prior));
        when(sessionService.appendNormalMessages(anyString(), any(), anyString())).thenReturn(2L);

        assertThatThrownBy(() -> chatService.chatAsync(SESSION_ID, "new turn", USER_ID))
                .isInstanceOf(RejectedExecutionException.class);

        assertThat(session.getRuntimeStatus()).isEqualTo("error");
        assertThat(session.getRuntimeStep()).isEqualTo("retryable");
        assertThat(session.getRuntimeError()).isEqualTo("The agent runtime is busy. Please retry.");
        assertThat(session.getRuntimeFailureSource()).isEqualTo("harness");
        assertThat(session.getRuntimeFailureCode()).isEqualTo("EXECUTOR_BUSY");
        assertThat(session.isRuntimeRetryable()).isTrue();
        assertThat(session.getRuntimeSideEffects()).isEqualTo("none");
        verify(broadcaster).sessionStatus(
                SESSION_ID, "error", "retryable", "The agent runtime is busy. Please retry.",
                "harness", "EXECUTOR_BUSY", true, "none");
    }

    @Test
    void firstTurn_rejectedAfterSessionStartHook_isFailClosed() {
        SessionEntity session = stubSessionAndAgent();
        session.setRuntimeStatus("idle");
        when(sessionService.getFullHistory(SESSION_ID)).thenReturn(List.of());
        when(sessionService.getContextMessages(SESSION_ID)).thenReturn(List.of());
        when(sessionService.appendNormalMessages(anyString(), any(), anyString())).thenReturn(1L);
        when(lifecycleHookDispatcher.fireSessionStart(any(), eq(SESSION_ID), eq(USER_ID)))
                .thenReturn(true);

        assertThatThrownBy(() -> chatService.chatAsync(SESSION_ID, "first turn", USER_ID))
                .isInstanceOf(RejectedExecutionException.class);

        assertThat(session.getRuntimeStatus()).isEqualTo("error");
        assertThat(session.getRuntimeFailureSource()).isEqualTo("harness");
        assertThat(session.getRuntimeFailureCode()).isEqualTo("EXECUTOR_BUSY");
        assertThat(session.isRuntimeRetryable()).isFalse();
        assertThat(session.getRuntimeSideEffects()).isEqualTo("possible");
        assertThat(session.getRuntimeError()).isEqualTo("The agent runtime is busy.");
    }

    @Test
    void answerAsk_rejectedExecutor_doesNotConsumeControl() {
        SessionMessageEntity control = new SessionMessageEntity();
        control.setMetadataJson("{\"toolUseId\":\"tool-1\"}");
        when(sessionService.getControlMessage(
                SESSION_ID, SessionService.MESSAGE_TYPE_ASK_USER, "ask-1"))
                .thenReturn(control);
        stubSessionAndAgent();
        when(sessionService.getContextMessages(SESSION_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> chatService.answerAsk(SESSION_ID, "ask-1", "yes", USER_ID))
                .isInstanceOf(RejectedExecutionException.class);

        verify(sessionService, never()).markControlAnswered(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString());
        verify(sessionService, never()).appendNormalMessages(anyString(), any(), anyString());
        verify(sessionService, never()).saveSession(any());
    }

    @Test
    void answerConfirmation_rejectedExecutor_doesNotConsumeOrExecuteControl() {
        SessionMessageEntity control = new SessionMessageEntity();
        control.setMetadataJson("{\"toolUseId\":\"tool-1\",\"toolName\":\"Write\","
                + "\"confirmationKind\":\"tool\",\"toolInput\":{}}");
        when(sessionService.findControlMessage(
                SESSION_ID, SessionService.MESSAGE_TYPE_CONFIRMATION, "confirmation-1"))
                .thenReturn(Optional.of(control));
        stubSessionAndAgent();

        assertThatThrownBy(() -> chatService.answerConfirmation(
                SESSION_ID, "confirmation-1", Decision.APPROVED, USER_ID))
                .isInstanceOf(RejectedExecutionException.class);

        verify(sessionService, never()).markControlAnswered(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString());
        verify(confirmationRegistry, never()).complete(anyString(), any(), any());
        verify(agentLoopEngine, never()).completeConfirmedTool(any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any());
        verify(sessionService, never()).appendNormalMessages(anyString(), any(), anyString());
        verify(sessionService, never()).saveSession(any());
    }

    private SessionEntity stubSessionAndAgent() {
        SessionEntity session = new SessionEntity();
        session.setId(SESSION_ID);
        session.setUserId(USER_ID);
        session.setAgentId(10L);
        AgentEntity agent = new AgentEntity();
        agent.setId(10L);
        AgentDefinition definition = new AgentDefinition();
        definition.setId("10");
        when(sessionService.getSession(SESSION_ID)).thenReturn(session);
        when(agentService.getAgent(10L)).thenReturn(agent);
        when(agentService.toAgentDefinition(agent)).thenReturn(definition);
        when(sessionService.getActiveRootTraceId(SESSION_ID)).thenReturn("root-1");
        when(sessionService.getContextMessages(SESSION_ID)).thenReturn(List.of());
        when(agentLoopEngine.completeConfirmedTool(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any())).thenReturn(Message.toolResult("tool-1", "ok", false));
        return session;
    }
}
