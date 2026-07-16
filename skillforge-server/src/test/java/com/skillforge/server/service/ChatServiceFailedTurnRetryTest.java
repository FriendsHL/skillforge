package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.AgentLoopEngine;
import com.skillforge.core.engine.CancellationRegistry;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.engine.LoopContext;
import com.skillforge.core.engine.LoopResult;
import com.skillforge.core.engine.confirm.PendingConfirmationRegistry;
import com.skillforge.core.engine.confirm.SessionConfirmCache;
import com.skillforge.core.engine.hook.LifecycleHookDispatcher;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.observability.api.LlmTraceStore;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.memory.SessionDigestExtractor;
import com.skillforge.server.repository.ModelUsageRepository;
import com.skillforge.server.subagent.SubAgentRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceFailedTurnRetryTest {

    private static final String SESSION_ID = "session-1";
    private static final long USER_ID = 7L;

    private AgentService agentService;
    private SessionService sessionService;
    private ChatEventBroadcaster broadcaster;
    private CompactionService compactionService;
    private AgentLoopEngine agentLoopEngine;
    private CapturingExecutor executor;
    private ChatService chatService;
    private SessionEntity session;

    @BeforeEach
    void setUp() {
        agentService = mock(AgentService.class);
        sessionService = mock(SessionService.class);
        broadcaster = mock(ChatEventBroadcaster.class);
        compactionService = mock(CompactionService.class);
        agentLoopEngine = mock(AgentLoopEngine.class);
        executor = new CapturingExecutor();
        when(compactionService.lockFor(anyString())).thenAnswer(invocation -> new Object());

        session = new SessionEntity();
        session.setId(SESSION_ID);
        session.setUserId(USER_ID);
        session.setAgentId(10L);
        session.setRuntimeStatus("error");
        session.setRuntimeStep("retryable");
        session.setRuntimeError("provider unavailable");
        session.setMessageCount(1);
        when(sessionService.getSession(SESSION_ID)).thenReturn(session);

        AgentEntity agent = new AgentEntity();
        agent.setId(10L);
        when(agentService.getAgent(10L)).thenReturn(agent);
        AgentDefinition definition = new AgentDefinition();
        definition.setId("10");
        when(agentService.toAgentDefinition(agent)).thenReturn(definition);

        chatService = buildService(executor);
    }

    @Test
    void retryFailedTurn_errorWithUserTail_resumesWithoutAppendingUserMessage() {
        Message failedUserTurn = Message.user("try again");
        when(sessionService.getContextMessages(SESSION_ID)).thenReturn(List.of(failedUserTurn));
        when(sessionService.getActiveRootTraceId(SESSION_ID)).thenReturn("root-1");
        LoopResult loopResult = new LoopResult(
                "done",
                new ArrayList<>(List.of(failedUserTurn, Message.assistant("done"))),
                12, 3, 1, List.of());
        when(agentLoopEngine.run(
                any(AgentDefinition.class), eq("try again"), eq(failedUserTurn),
                eq(List.of()), eq(SESSION_ID), eq(USER_ID), any(LoopContext.class)))
                .thenReturn(loopResult);

        chatService.retryFailedTurnAsync(SESSION_ID);

        assertThat(executor.submitted).isNotNull();
        assertThat(session.getRuntimeStatus()).isEqualTo("running");
        assertThat(session.getRuntimeStep()).isEqualTo("Retrying");
        assertThat(session.getRuntimeError()).isNull();
        verify(sessionService, never()).appendNormalMessages(anyString(), any(), anyString());
        verify(broadcaster).sessionStatus(SESSION_ID, "running", "Retrying", null);
        verify(broadcaster).userEvent(eq(USER_ID), any());

        executor.submitted.run();
        verify(agentLoopEngine).run(
                any(AgentDefinition.class), eq("try again"), eq(failedUserTurn),
                eq(List.of()), eq(SESSION_ID), eq(USER_ID), any(LoopContext.class));
    }

    @Test
    void retryFailedTurn_secondRequestAfterReservation_isRejected() {
        when(sessionService.getContextMessages(SESSION_ID)).thenReturn(List.of(Message.user("try again")));
        when(sessionService.getActiveRootTraceId(SESSION_ID)).thenReturn("root-1");

        chatService.retryFailedTurnAsync(SESSION_ID);

        assertThatThrownBy(() -> chatService.retryFailedTurnAsync(SESSION_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not in error state");
    }

    @Test
    void retryFailedTurn_isRejectedUntilOldErrorEventHasFinishedPublishing() throws Exception {
        Message failedUserTurn = Message.user("try again");
        when(sessionService.getContextMessages(SESSION_ID)).thenReturn(List.of(failedUserTurn));
        when(sessionService.getActiveRootTraceId(SESSION_ID)).thenReturn("root-1");
        when(agentLoopEngine.run(
                any(AgentDefinition.class), eq("try again"), eq(failedUserTurn),
                eq(List.of()), eq(SESSION_ID), eq(USER_ID), any(LoopContext.class)))
                .thenThrow(new RuntimeException("provider unavailable"));

        CountDownLatch errorBroadcastEntered = new CountDownLatch(1);
        CountDownLatch allowErrorBroadcastToFinish = new CountDownLatch(1);
        doAnswer(invocation -> {
            if ("error".equals(invocation.getArgument(1))) {
                errorBroadcastEntered.countDown();
                allowErrorBroadcastToFinish.await(2, TimeUnit.SECONDS);
            }
            return null;
        }).when(broadcaster).sessionStatus(
                eq(SESSION_ID), anyString(), any(), any());

        chatService.retryFailedTurnAsync(SESSION_ID);
        Thread oldLoop = new Thread(executor.submitted);
        oldLoop.start();

        assertThat(errorBroadcastEntered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> chatService.retryFailedTurnAsync(SESSION_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("still finishing");

        allowErrorBroadcastToFinish.countDown();
        oldLoop.join(2_000);
        assertThat(oldLoop.isAlive()).isFalse();

        chatService.retryFailedTurnAsync(SESSION_ID);
        assertThat(session.getRuntimeStatus()).isEqualTo("running");
    }

    @Test
    void retryFailedTurn_nonErrorSession_isRejectedWithoutSubmitting() {
        session.setRuntimeStatus("idle");

        assertThatThrownBy(() -> chatService.retryFailedTurnAsync(SESSION_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not in error state");

        assertThat(executor.submitted).isNull();
        verify(sessionService, never()).saveSession(any());
    }

    @Test
    void retryFailedTurn_withoutUserTail_isRejectedWithoutSubmitting() {
        when(sessionService.getContextMessages(SESSION_ID))
                .thenReturn(List.of(Message.user("question"), Message.assistant("partial answer")));

        assertThatThrownBy(() -> chatService.retryFailedTurnAsync(SESSION_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no retryable failed user turn");

        assertThat(executor.submitted).isNull();
        verify(sessionService, never()).saveSession(any());
    }

    @Test
    void retryFailedTurn_policyAbort_isRejectedWithoutSubmitting() {
        session.setRuntimeError("Aborted by SessionStart hook");
        session.setRuntimeStep(null);
        when(sessionService.getContextMessages(SESSION_ID)).thenReturn(List.of(Message.user("try again")));

        assertThatThrownBy(() -> chatService.retryFailedTurnAsync(SESSION_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not retryable");

        assertThat(executor.submitted).isNull();
        verify(sessionService, never()).saveSession(any());
    }

    @Test
    void retryFailedTurn_rejectedExecutor_keepsErrorState() {
        ThreadPoolExecutor rejecting = mock(ThreadPoolExecutor.class);
        doThrow(new RejectedExecutionException("queue full"))
                .when(rejecting).execute(any(Runnable.class));
        chatService = buildService(rejecting);
        when(sessionService.getContextMessages(SESSION_ID)).thenReturn(List.of(Message.user("try again")));

        assertThatThrownBy(() -> chatService.retryFailedTurnAsync(SESSION_ID))
                .isInstanceOf(RejectedExecutionException.class);

        assertThat(session.getRuntimeStatus()).isEqualTo("error");
        assertThat(session.getRuntimeError()).isEqualTo("provider unavailable");
        verify(sessionService, never()).saveSession(any());
    }

    @Test
    void retryEligibility_rejectsAttemptAfterAnyToolExecution() {
        LoopContext loopContext = new LoopContext();
        loopContext.recordToolCall("CreateTask");

        assertThat(ChatService.isSafeToRetryFailure(
                loopContext, List.of(Message.user("create a task"))))
                .isFalse();
    }

    @Test
    void retryEligibility_rejectsAttemptAfterCompactContextExecution() {
        LoopContext loopContext = new LoopContext();
        loopContext.recordToolCall("compact_context");

        assertThat(ChatService.isSafeToRetryFailure(
                loopContext, List.of(Message.user("compact then continue"))))
                .isFalse();
    }

    @Test
    void retryEligibility_acceptsProviderFailureBeforeTools() {
        assertThat(ChatService.isSafeToRetryFailure(
                new LoopContext(), List.of(Message.user("explain this"))))
                .isTrue();
    }

    @Test
    void latestUserTurn_preservesMultimodalBlocksForRetryValidation() {
        Message user = new Message();
        user.setRole(Message.Role.USER);
        user.setContent(List.of(
                ContentBlock.text("describe this"),
                ContentBlock.imageRef("attachment-1", "image/png", "photo.png")));

        Message latestUser = ChatService.findLatestUserTurn(List.of(
                user,
                Message.toolResult("tool-1", "done", false)));

        assertThat(latestUser).isSameAs(user);
        assertThat(ChatService.messageHasMultimodalBlocks(latestUser)).isTrue();
    }

    @Test
    void retryUserText_excludesPersistedSystemReminderButKeepsUserQuery() {
        Message user = new Message();
        user.setRole(Message.Role.USER);
        user.setContent(List.of(
                ContentBlock.text("<system-reminder>Context 87% used</system-reminder>\n"),
                ContentBlock.text("summarize the attached report"),
                ContentBlock.imageRef("attachment-1", "image/png", "report.png")));

        assertThat(ChatService.extractRetryUserText(user))
                .isEqualTo("summarize the attached report");
    }

    private ChatService buildService(ThreadPoolExecutor loopExecutor) {
        return new ChatService(
                agentService,
                sessionService,
                mock(SkillRegistry.class),
                agentLoopEngine,
                mock(ModelUsageRepository.class),
                broadcaster,
                loopExecutor,
                mock(SessionTitleService.class),
                mock(SubAgentRegistry.class),
                mock(CancellationRegistry.class),
                compactionService,
                null,
                null,
                new ObjectMapper(),
                mock(SessionDigestExtractor.class),
                mock(LifecycleHookDispatcher.class),
                new SessionConfirmCache(),
                mock(PendingConfirmationRegistry.class),
                sessionId -> sessionId,
                mock(LlmTraceStore.class),
                mock(org.springframework.context.ApplicationEventPublisher.class),
                null);
    }

    private static final class CapturingExecutor extends ThreadPoolExecutor {
        private Runnable submitted;

        private CapturingExecutor() {
            super(0, 1, 1L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(1));
        }

        @Override
        public void execute(Runnable command) {
            submitted = command;
        }
    }
}
