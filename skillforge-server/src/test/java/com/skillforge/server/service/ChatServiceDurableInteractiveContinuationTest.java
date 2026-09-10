package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.AgentLoopEngine;
import com.skillforge.core.engine.CancellationRegistry;
import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.engine.confirm.Decision;
import com.skillforge.core.engine.confirm.PendingConfirmationRegistry;
import com.skillforge.core.engine.confirm.SessionConfirmCache;
import com.skillforge.core.engine.durability.ArchivePreparationCommand;
import com.skillforge.core.engine.durability.DurableFrontier;
import com.skillforge.core.engine.durability.DurableToolAttemptState;
import com.skillforge.core.engine.durability.ExecutionClaimAck;
import com.skillforge.core.engine.durability.FrozenJson;
import com.skillforge.core.engine.durability.IntentCommitAck;
import com.skillforge.core.engine.durability.InteractiveStepPlanner;
import com.skillforge.core.engine.durability.LoopDurabilityScope;
import com.skillforge.core.engine.durability.MessageSnapshot;
import com.skillforge.core.engine.durability.PersistedBlockOccurrence;
import com.skillforge.core.engine.durability.PersistedMessageOccurrence;
import com.skillforge.core.engine.durability.ReplaySafety;
import com.skillforge.core.engine.durability.ToolCallIntent;
import com.skillforge.core.engine.durability.ToolCallManifest;
import com.skillforge.core.engine.durability.ToolResultCommitAck;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.Message;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.observability.api.LlmTraceStore;
import com.skillforge.server.config.SessionHistoryProperties;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionMessageEntity;
import com.skillforge.server.exception.RetryBusyException;
import com.skillforge.server.memory.SessionDigestExtractor;
import com.skillforge.server.repository.ModelUsageRepository;
import com.skillforge.server.session.DurableSessionRecoveryCoordinator;
import com.skillforge.server.session.DurableRecoveryFailureException;
import com.skillforge.server.session.OccurrenceArchivePreparation;
import com.skillforge.server.session.SessionDurableCompletionReconciler;
import com.skillforge.server.session.SessionInteractiveControlTransactionService;
import com.skillforge.server.session.SessionLoopAdmissionService;
import com.skillforge.server.session.SessionLoopLeaseHeartbeat;
import com.skillforge.server.subagent.SubAgentRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Focused orchestration tests for durable ask/confirmation continuations. */
class ChatServiceDurableInteractiveContinuationTest {

    @Test
    void terminalAssistantWithNoOpenAttemptResumesToDrainDurableQueuedInput() {
        Fixture fixture = new Fixture();
        SessionEntity running = fixture.session("running");
        when(fixture.sessionService.getSession(fixture.sessionId)).thenReturn(running);
        SessionLoopAdmissionService.RecoveryAdmissionAck admission =
                new SessionLoopAdmissionService.RecoveryAdmissionAck(
                        fixture.scope,
                        null,
                        null,
                        null,
                        null,
                        false,
                        Instant.now().plusSeconds(120));
        when(fixture.recoveryCoordinator.recover(fixture.sessionId, fixture.userId))
                .thenReturn(new DurableSessionRecoveryCoordinator.RecoveryPlan(
                        DurableSessionRecoveryCoordinator.RecoveryDisposition.PROVIDER_CONTINUE,
                        admission,
                        new DurableFrontier(90L, 12L),
                        null,
                        null,
                        null,
                        null));
        when(fixture.sessionService.getContextMessages(fixture.sessionId))
                .thenReturn(List.of(Message.assistant("terminal-before-queued-user")));
        when(fixture.sessionService.getActiveRootTraceId(fixture.sessionId))
                .thenReturn("root-trace");

        fixture.service.resumeInterruptedTurnAsync(fixture.sessionId);

        verify(fixture.executor).execute(any(Runnable.class));
        verify(fixture.admissionService, never()).parkRecoveryFailure(any(), any());
    }

    @Test
    void firstAskAnswerCommitsAndArchivesBeforeVisibility_thenResumesFromCommittedResult() {
        Fixture fixture = new Fixture();
        SessionInteractiveControlTransactionService.InteractiveAnswerClaimAck claim =
                fixture.claim(InteractiveStepPlanner.CallKind.ASK_USER,
                        DurableToolAttemptState.EXECUTING, true);
        SessionInteractiveControlTransactionService.InteractiveResultAck result =
                fixture.result(InteractiveStepPlanner.CallKind.ASK_USER,
                        "answer-1", "answered", "User answered: answer-1");

        when(fixture.interactiveTransactions.claimAnswerForDispatch(any(), anyString()))
                .thenReturn(claim);
        when(fixture.interactiveTransactions.commitAnswerResults(any()))
                .thenAnswer(invocation -> {
                    verifyNoInteractions(fixture.broadcaster);
                    return result;
                });
        when(fixture.archivePreparation.ensurePrepared(any()))
                .thenAnswer(invocation -> {
                    verifyNoInteractions(fixture.broadcaster);
                    return null;
                });
        fixture.publishVisibilityCallback();
        fixture.stubProviderContinuation(result);

        fixture.service.answerAsk(fixture.sessionId, fixture.controlId, "answer-1",
                fixture.userId);

        ArgumentCaptor<SessionInteractiveControlTransactionService.InteractiveResultCommand>
                resultCommand = ArgumentCaptor.forClass(
                        SessionInteractiveControlTransactionService.InteractiveResultCommand.class);
        verify(fixture.interactiveTransactions).commitAnswerResults(resultCommand.capture());
        assertThat(resultCommand.getValue().resolutionKind())
                .isEqualTo(SessionInteractiveControlTransactionService.ResolutionKind.ANSWERED);
        assertThat(resultCommand.getValue().answer()).isEqualTo("answer-1");
        assertThat(resultCommand.getValue().selectedResult())
                .isEqualTo(MessageSnapshot.capture(Message.toolResult(
                        fixture.toolUseId, "User answered: answer-1", false)));

        InOrder order = inOrder(
                fixture.admissionService,
                fixture.heartbeat,
                fixture.heartbeatHandle,
                fixture.interactiveTransactions,
                fixture.archivePreparation,
                fixture.broadcaster,
                fixture.executor,
                fixture.recoveryCoordinator);
        order.verify(fixture.admissionService).claimManualContinuation(
                fixture.sessionId, fixture.userId, fixture.historyEpoch, fixture.stableLoopId());
        order.verify(fixture.heartbeat).start(fixture.scope);
        order.verify(fixture.interactiveTransactions).claimAnswerForDispatch(
                any(), org.mockito.ArgumentMatchers.eq(fixture.controlId));
        order.verify(fixture.interactiveTransactions).commitAnswerResults(any());
        order.verify(fixture.archivePreparation).ensurePrepared(any());
        order.verify(fixture.heartbeatHandle).assertAuthoritative();
        order.verify(fixture.archivePreparation).withResultVisibilityAuthority(any(), any());
        order.verify(fixture.broadcaster).messageAppended(
                org.mockito.ArgumentMatchers.eq(fixture.sessionId),
                org.mockito.ArgumentMatchers.eq(fixture.resultTraceId),
                any(Message.class));
        order.verify(fixture.heartbeatHandle).close();
        order.verify(fixture.executor).execute(any(Runnable.class));
        order.verify(fixture.recoveryCoordinator).recover(fixture.sessionId, fixture.userId);
        verify(fixture.agentLoopEngine, never()).completeConfirmedToolAfterDurableClaim(
                any(), anyString(), anyLong(), anyString(), anyString(), any(), any(), any(), any(),
                any());
    }

    @Test
    void closedConfirmationAckRetryReadsCommittedResultsAndNeverDispatchesToolAgain() {
        Fixture fixture = new Fixture();
        fixture.persistedConfirmationControlExists();
        SessionInteractiveControlTransactionService.InteractiveAnswerClaimAck claim =
                fixture.claim(InteractiveStepPlanner.CallKind.CONFIRMATION,
                        DurableToolAttemptState.RESULTS_COMMITTED, false);
        SessionInteractiveControlTransactionService.InteractiveResultAck result =
                fixture.result(InteractiveStepPlanner.CallKind.CONFIRMATION,
                        "approved", "approved", "already committed");
        when(fixture.interactiveTransactions.claimAnswerForDispatch(any(), anyString()))
                .thenReturn(claim);
        when(fixture.interactiveTransactions.loadCommittedAnswerResults(
                claim.execution(), fixture.controlId)).thenReturn(result);
        fixture.publishVisibilityCallback();
        fixture.stubProviderContinuation(result);

        fixture.service.answerConfirmation(
                fixture.sessionId, fixture.controlId, Decision.APPROVED, fixture.userId);

        verify(fixture.interactiveTransactions).loadCommittedAnswerResults(
                claim.execution(), fixture.controlId);
        verify(fixture.interactiveTransactions, never()).commitAnswerResults(any());
        verify(fixture.agentLoopEngine, never()).completeConfirmedToolAfterDurableClaim(
                any(), anyString(), anyLong(), anyString(), anyString(), any(), any(), any(), any(),
                any());
        InOrder closedRetryOrder = inOrder(
                fixture.interactiveTransactions, fixture.archivePreparation,
                fixture.broadcaster, fixture.recoveryCoordinator);
        closedRetryOrder.verify(fixture.interactiveTransactions)
                .loadCommittedAnswerResults(claim.execution(), fixture.controlId);
        closedRetryOrder.verify(fixture.archivePreparation).ensurePrepared(any());
        closedRetryOrder.verify(fixture.archivePreparation)
                .withResultVisibilityAuthority(any(), any());
        closedRetryOrder.verify(fixture.broadcaster).messageAppended(
                org.mockito.ArgumentMatchers.eq(fixture.sessionId),
                org.mockito.ArgumentMatchers.eq(fixture.resultTraceId),
                any(Message.class));
        closedRetryOrder.verify(fixture.recoveryCoordinator)
                .recover(fixture.sessionId, fixture.userId);
    }

    @Test
    void approvedConfirmationExecutesSelectedToolExactlyOnceWhenDispatchIsGranted() {
        Fixture fixture = new Fixture();
        fixture.persistedConfirmationControlExists();
        SessionInteractiveControlTransactionService.InteractiveAnswerClaimAck claim =
                fixture.claim(InteractiveStepPlanner.CallKind.CONFIRMATION,
                        DurableToolAttemptState.EXECUTING, true);
        Message selectedResult = Message.toolResult(
                fixture.toolUseId, "approved tool result", false);
        SessionInteractiveControlTransactionService.InteractiveResultAck result =
                fixture.result(InteractiveStepPlanner.CallKind.CONFIRMATION,
                        "approved", "approved", "approved tool result");
        when(fixture.interactiveTransactions.claimAnswerForDispatch(any(), anyString()))
                .thenReturn(claim);
        when(fixture.agentLoopEngine.completeConfirmedToolAfterDurableClaim(
                any(), anyString(), anyLong(), anyString(), anyString(), any(), any(), any(), any(),
                any())).thenReturn(selectedResult);
        when(fixture.interactiveTransactions.commitAnswerResults(any())).thenReturn(result);
        fixture.publishVisibilityCallback();
        fixture.stubProviderContinuation(result);

        fixture.service.answerConfirmation(
                fixture.sessionId, fixture.controlId, Decision.APPROVED, fixture.userId);

        verify(fixture.agentLoopEngine).completeConfirmedToolAfterDurableClaim(
                any(AgentDefinition.class),
                org.mockito.ArgumentMatchers.eq(fixture.sessionId),
                org.mockito.ArgumentMatchers.eq(fixture.userId),
                org.mockito.ArgumentMatchers.eq(fixture.toolUseId),
                org.mockito.ArgumentMatchers.eq(fixture.toolName),
                org.mockito.ArgumentMatchers.eq(fixture.toolInput),
                org.mockito.ArgumentMatchers.eq("dangerous"),
                org.mockito.ArgumentMatchers.eq("plugin"),
                org.mockito.ArgumentMatchers.eq("target"),
                org.mockito.ArgumentMatchers.eq(Decision.APPROVED));
        verify(fixture.interactiveTransactions).commitAnswerResults(any());
    }

    @Test
    void executingConfirmationWithoutDispatchGrantReturnsBusyAndPerformsNoEffect() {
        Fixture fixture = new Fixture();
        fixture.persistedConfirmationControlExists();
        when(fixture.interactiveTransactions.claimAnswerForDispatch(any(), anyString()))
                .thenReturn(fixture.claim(InteractiveStepPlanner.CallKind.CONFIRMATION,
                        DurableToolAttemptState.EXECUTING, false));

        assertThatThrownBy(() -> fixture.service.answerConfirmation(
                fixture.sessionId, fixture.controlId, Decision.APPROVED, fixture.userId))
                .isInstanceOf(RetryBusyException.class);

        verify(fixture.agentLoopEngine, never()).completeConfirmedToolAfterDurableClaim(
                any(), anyString(), anyLong(), anyString(), anyString(), any(), any(), any(), any(),
                any());
        verify(fixture.interactiveTransactions, never()).commitAnswerResults(any());
        verifyNoInteractions(fixture.archivePreparation, fixture.broadcaster,
                fixture.recoveryCoordinator, fixture.executor);
        verify(fixture.heartbeatHandle).close();
    }

    @Test
    void retryWithDifferentAnswerIsRejectedBeforeArchiveVisibilityOrContinuation() {
        Fixture fixture = new Fixture();
        SessionInteractiveControlTransactionService.InteractiveAnswerClaimAck claim =
                fixture.claim(InteractiveStepPlanner.CallKind.ASK_USER,
                        DurableToolAttemptState.RESULTS_COMMITTED, false);
        SessionInteractiveControlTransactionService.InteractiveResultAck committed =
                fixture.result(InteractiveStepPlanner.CallKind.ASK_USER,
                        "first answer", "answered", "User answered: first answer");
        when(fixture.interactiveTransactions.claimAnswerForDispatch(any(), anyString()))
                .thenReturn(claim);
        when(fixture.interactiveTransactions.loadCommittedAnswerResults(
                claim.execution(), fixture.controlId)).thenReturn(committed);

        assertThatThrownBy(() -> fixture.service.answerAsk(
                fixture.sessionId, fixture.controlId, "different answer", fixture.userId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Interactive control was already resolved with a different answer");

        verify(fixture.interactiveTransactions, never()).commitAnswerResults(any());
        verifyNoInteractions(fixture.archivePreparation, fixture.broadcaster,
                fixture.recoveryCoordinator, fixture.executor);
        verify(fixture.heartbeatHandle).close();
    }

    @Test
    void parkedWaitingControlRepublishEmitsSameControlWithoutClaimToolProviderOrExecutor() {
        Fixture fixture = new Fixture();
        var waiting = fixture.recoveredAskControl();
        when(fixture.interactiveTransactions.loadParkedWaitingControl(
                fixture.sessionId, fixture.userId, fixture.historyEpoch))
                .thenReturn(waiting);

        fixture.service.republishWaitingInteractiveControl(
                fixture.sessionId, fixture.userId, fixture.historyEpoch);

        ArgumentCaptor<ChatEventBroadcaster.AskUserEvent> event =
                ArgumentCaptor.forClass(ChatEventBroadcaster.AskUserEvent.class);
        verify(fixture.broadcaster).askUser(
                org.mockito.ArgumentMatchers.eq(fixture.sessionId), event.capture());
        assertThat(event.getValue().askId).isEqualTo(fixture.controlId);
        assertThat(event.getValue().question).isEqualTo("Choose one option");
        assertThat(event.getValue().context).isEqualTo("Pick the safest option");
        assertThat(event.getValue().allowOther).isFalse();
        assertThat(event.getValue().options).hasSize(1);
        assertThat(event.getValue().options.get(0).label).isEqualTo("option-a");
        assertThat(event.getValue().options.get(0).description).isEqualTo("First");
        verify(fixture.broadcaster).sessionStatus(
                fixture.sessionId, "waiting_user", "waiting_control", null);
        verify(fixture.admissionService, never()).claimManualContinuation(
                anyString(), anyLong(), anyLong(), anyString());
        verify(fixture.recoveryCoordinator, never()).recover(anyString(), anyLong());
        verify(fixture.agentLoopEngine, never()).run(
                any(), any(), any(), any(), anyString(), anyLong(), any());
        verify(fixture.executor, never()).execute(any(Runnable.class));
    }

    @Test
    void parkedConfirmationRepublishEmitsSameConfirmationPayloadWithoutDispatch() {
        Fixture fixture = new Fixture();
        var waiting = fixture.recoveredConfirmationControl();
        when(fixture.interactiveTransactions.loadParkedWaitingControl(
                fixture.sessionId, fixture.userId, fixture.historyEpoch))
                .thenReturn(waiting);

        fixture.service.republishWaitingInteractiveControl(
                fixture.sessionId, fixture.userId, fixture.historyEpoch);

        ArgumentCaptor<com.skillforge.core.engine.confirm.ConfirmationPromptPayload> event =
                ArgumentCaptor.forClass(
                        com.skillforge.core.engine.confirm.ConfirmationPromptPayload.class);
        verify(fixture.broadcaster).confirmationRequired(
                org.mockito.ArgumentMatchers.eq(fixture.sessionId), event.capture());
        assertThat(event.getValue().confirmationId()).isEqualTo(fixture.controlId);
        assertThat(event.getValue().sessionId()).isEqualTo(fixture.sessionId);
        assertThat(event.getValue().installTool()).isEqualTo("plugin");
        assertThat(event.getValue().installTarget()).isEqualTo("safe-target");
        assertThat(event.getValue().commandPreview()).isEqualTo("install safe-target");
        assertThat(event.getValue().title()).isEqualTo("Approve install");
        assertThat(event.getValue().description()).isEqualTo("Review command");
        assertThat(event.getValue().choices()).hasSize(2);
        assertThat(event.getValue().expiresAt())
                .isEqualTo(Instant.parse("2030-01-02T03:04:05Z"));
        verify(fixture.agentLoopEngine, never()).completeConfirmedToolAfterDurableClaim(
                any(), anyString(), anyLong(), anyString(), anyString(), any(), any(), any(), any(),
                any());
        verify(fixture.admissionService, never()).claimManualContinuation(
                anyString(), anyLong(), anyLong(), anyString());
        verify(fixture.executor, never()).execute(any(Runnable.class));
    }

    @Test
    void corruptParkedWaitingControlRecordsSafeFailureAndPublishesNothing() {
        Fixture fixture = new Fixture();
        when(fixture.interactiveTransactions.loadParkedWaitingControl(
                fixture.sessionId, fixture.userId, fixture.historyEpoch))
                .thenThrow(new IllegalStateException(
                        "Durable interactive control is partial or inconsistent"));

        assertThatThrownBy(() -> fixture.service.republishWaitingInteractiveControl(
                fixture.sessionId, fixture.userId, fixture.historyEpoch))
                .isInstanceOf(DurableRecoveryFailureException.class)
                .hasNoCause();

        verify(fixture.interactiveTransactions)
                .recordParkedWaitingControlRecoveryFailure(
                        fixture.sessionId, fixture.userId, fixture.historyEpoch);
        verifyNoInteractions(fixture.broadcaster, fixture.recoveryCoordinator, fixture.executor);
    }

    private static final class Fixture {
        private static final String HASH_A = "a".repeat(64);
        private static final String HASH_B = "b".repeat(64);

        private final String sessionId = "00000000-0000-0000-0000-000000000101";
        private final long userId = 2101L;
        private final long historyEpoch = 7L;
        private final String controlId = "control-1";
        private final String toolUseId = "tool-use-1";
        private final String toolName = "dangerous_tool";
        private final Map<String, Object> toolInput = Map.of("path", "safe-value");
        private final String resultTraceId = "result-trace";
        private final long attemptId = 31L;
        private final UUID stepId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        private final LoopDurabilityScope scope = new LoopDurabilityScope(
                sessionId, userId, historyEpoch, stableLoopId(), 9L, "owner-1");

        private final AgentService agentService = mock(AgentService.class);
        private final SessionService sessionService = mock(SessionService.class);
        private final AgentLoopEngine agentLoopEngine = mock(AgentLoopEngine.class);
        private final ChatEventBroadcaster broadcaster = mock(ChatEventBroadcaster.class);
        private final ThreadPoolExecutor executor = mock(ThreadPoolExecutor.class);
        private final CompactionService compactionService = mock(CompactionService.class);
        private final SessionLoopAdmissionService admissionService =
                mock(SessionLoopAdmissionService.class);
        private final SessionLoopLeaseHeartbeat heartbeat =
                mock(SessionLoopLeaseHeartbeat.class);
        private final SessionLoopLeaseHeartbeat.Handle heartbeatHandle =
                mock(SessionLoopLeaseHeartbeat.Handle.class);
        private final DurableSessionRecoveryCoordinator recoveryCoordinator =
                mock(DurableSessionRecoveryCoordinator.class);
        private final SessionInteractiveControlTransactionService interactiveTransactions =
                mock(SessionInteractiveControlTransactionService.class);
        private final OccurrenceArchivePreparation archivePreparation =
                mock(OccurrenceArchivePreparation.class);
        private final ChatService service;

        private Fixture() {
            service = new ChatService(
                    agentService,
                    sessionService,
                    mock(SkillRegistry.class),
                    agentLoopEngine,
                    mock(ModelUsageRepository.class),
                    broadcaster,
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
                    candidate -> candidate,
                    mock(LlmTraceStore.class),
                    mock(org.springframework.context.ApplicationEventPublisher.class),
                    null);
            SessionHistoryProperties properties = new SessionHistoryProperties();
            properties.setEnabled(true);
            service.configureSessionDurability(
                    properties,
                    admissionService,
                    mock(SessionDurableCompletionReconciler.class),
                    heartbeat,
                    recoveryCoordinator);
            service.configureDurableInteractiveContinuation(
                    interactiveTransactions, archivePreparation);

            when(compactionService.lockFor(sessionId)).thenReturn(new Object());
            SessionEntity waiting = session("waiting_user");
            SessionEntity running = session("running");
            when(sessionService.getSession(sessionId)).thenReturn(waiting, running);
            when(admissionService.claimManualContinuation(
                    sessionId, userId, historyEpoch, stableLoopId()))
                    .thenReturn(new SessionLoopAdmissionService.ManualContinuationClaimAck(
                            scope, attemptId, stepId, Instant.now().plusSeconds(120)));
            when(heartbeat.start(scope)).thenReturn(heartbeatHandle);

            AgentEntity agent = new AgentEntity();
            agent.setId(2201L);
            agent.setExecutionMode("auto");
            when(agentService.getAgent(2201L)).thenReturn(agent);
            AgentDefinition definition = new AgentDefinition();
            definition.setName("durable-interactive-agent");
            definition.setModelId("fake:model");
            definition.setSystemPrompt("system");
            when(agentService.toAgentDefinition(agent)).thenReturn(definition);
        }

        private SessionEntity session(String runtimeStatus) {
            SessionEntity session = new SessionEntity();
            session.setId(sessionId);
            session.setUserId(userId);
            session.setAgentId(2201L);
            session.setStatus("active");
            session.setRuntimeStatus(runtimeStatus);
            session.setHistoryEpoch(historyEpoch);
            return session;
        }

        private String stableLoopId() {
            return DurableSessionRecoveryCoordinator.stableRecoveryLoopId(sessionId);
        }

        private void persistedConfirmationControlExists() {
            when(sessionService.findControlMessage(
                    sessionId, SessionService.MESSAGE_TYPE_CONFIRMATION, controlId))
                    .thenReturn(Optional.of(new SessionMessageEntity()));
        }

        private SessionInteractiveControlTransactionService.InteractiveAnswerClaimAck claim(
                InteractiveStepPlanner.CallKind kind,
                DurableToolAttemptState state,
                boolean dispatchGranted) {
            ExecutionClaimAck execution = new ExecutionClaimAck(
                    attemptId,
                    stepId,
                    UUID.fromString("00000000-0000-0000-0000-000000000301"),
                    state,
                    scope,
                    1L,
                    Instant.now(),
                    Instant.now().plusSeconds(120));
            return new SessionInteractiveControlTransactionService.InteractiveAnswerClaimAck(
                    execution,
                    dispatchGranted,
                    control(kind, null, null),
                    selected(kind));
        }

        private SessionInteractiveControlTransactionService.InteractiveResultAck result(
                InteractiveStepPlanner.CallKind kind,
                String answer,
                String state,
                String content) {
            UUID resultBatchId = UUID.fromString("00000000-0000-0000-0000-000000000401");
            Message resultMessage = Message.toolResult(toolUseId, content, false);
            PersistedMessageOccurrence occurrence = new PersistedMessageOccurrence(
                    102L,
                    5L,
                    resultBatchId.toString(),
                    0,
                    MessageSnapshot.capture(resultMessage),
                    "NORMAL",
                    "normal",
                    null,
                    null,
                    Map.of(),
                    resultTraceId);
            PersistedBlockOccurrence resultBlock = new PersistedBlockOccurrence(
                    102L,
                    5L,
                    sessionId,
                    resultBatchId,
                    0,
                    0,
                    toolUseId,
                    content,
                    false,
                    null,
                    resultTraceId);
            ToolResultCommitAck committed = new ToolResultCommitAck(
                    attemptId,
                    stepId,
                    resultBatchId,
                    scope,
                    1L,
                    List.of(occurrence),
                    List.of(resultBlock),
                    new DurableFrontier(101L, 4L),
                    new DurableFrontier(102L, 5L),
                    HASH_A,
                    HASH_B);
            return new SessionInteractiveControlTransactionService.InteractiveResultAck(
                    committed,
                    control(kind, state, answer),
                    selected(kind));
        }

        private PersistedMessageOccurrence control(
                InteractiveStepPlanner.CallKind kind,
                String state,
                String answer) {
            Map<String, Object> metadata;
            if (state != null) {
                metadata = Map.of(
                        "state", state,
                        "answer", answer,
                        "answerMode", "card");
            } else if (kind == InteractiveStepPlanner.CallKind.CONFIRMATION) {
                metadata = Map.of("payload", Map.of("extra", Map.of(
                        "toolInput", toolInput,
                        "confirmationKind", "dangerous",
                        "installTool", "plugin",
                        "installTarget", "target")));
            } else {
                metadata = Map.of();
            }
            return new PersistedMessageOccurrence(
                    101L,
                    4L,
                    "control-batch",
                    0,
                    MessageSnapshot.capture(Message.assistant("control")),
                    "CONTROL",
                    kind == InteractiveStepPlanner.CallKind.ASK_USER
                            ? SessionService.MESSAGE_TYPE_ASK_USER
                            : SessionService.MESSAGE_TYPE_CONFIRMATION,
                    controlId,
                    state == null ? null : Instant.now(),
                    metadata,
                    "control-trace");
        }

        private InteractiveStepPlanner.SelectedControl selected(
                InteractiveStepPlanner.CallKind kind) {
            return new InteractiveStepPlanner.SelectedControl(
                    new ToolCallIntent(
                            0,
                            toolUseId,
                            toolName,
                            FrozenJson.capture(toolInput),
                            ReplaySafety.MUTATING),
                    kind);
        }

        private SessionInteractiveControlTransactionService.InteractiveIntentAck
                recoveredAskControl() {
            Map<String, Object> payload = Map.of(
                    "controlId", controlId,
                    "interactionKind", "ask_user",
                    "toolUseId", toolUseId,
                    "toolName", toolName,
                    "question", "Choose one option",
                    "context", "Pick the safest option",
                    "options", List.of(Map.of(
                            "label", "option-a", "description", "First")),
                    "allowOther", false,
                    "extra", Map.of());
            return recoveredControl(InteractiveStepPlanner.CallKind.ASK_USER, payload);
        }

        private SessionInteractiveControlTransactionService.InteractiveIntentAck
                recoveredConfirmationControl() {
            Map<String, Object> extra = new java.util.LinkedHashMap<>();
            extra.put("confirmationKind", "install");
            extra.put("sessionId", sessionId);
            extra.put("installTool", "plugin");
            extra.put("installTarget", "safe-target");
            extra.put("commandPreview", "install safe-target");
            extra.put("title", "Approve install");
            extra.put("description", "Review command");
            extra.put("expiresAt", "2030-01-02T03:04:05Z");
            extra.put("toolInput", toolInput);
            Map<String, Object> payload = Map.of(
                    "controlId", controlId,
                    "interactionKind", "confirmation",
                    "toolUseId", toolUseId,
                    "toolName", toolName,
                    "question", "Approve install",
                    "context", "Review command",
                    "options", List.of(
                            Map.of("value", "approved", "label", "Approve",
                                    "style", "primary"),
                            Map.of("value", "denied", "label", "Deny",
                                    "style", "danger")),
                    "allowOther", false,
                    "extra", extra);
            return recoveredControl(InteractiveStepPlanner.CallKind.CONFIRMATION, payload);
        }

        private SessionInteractiveControlTransactionService.InteractiveIntentAck recoveredControl(
                InteractiveStepPlanner.CallKind kind, Map<String, Object> payload) {
            InteractiveStepPlanner.SelectedControl selected = selected(kind);
            ToolCallManifest manifest = new ToolCallManifest(
                    List.of(selected.call()), selected.call().replaySafety());
            Message assistantMessage = new Message();
            assistantMessage.setRole(Message.Role.ASSISTANT);
            assistantMessage.setContent(List.of(com.skillforge.core.model.ContentBlock.toolUse(
                    toolUseId, toolName, toolInput)));
            PersistedMessageOccurrence assistant = new PersistedMessageOccurrence(
                    100L,
                    3L,
                    "assistant-batch",
                    0,
                    MessageSnapshot.capture(assistantMessage),
                    "NORMAL",
                    "normal",
                    null,
                    null,
                    Map.of(),
                    "control-trace");
            IntentCommitAck intent = new IntentCommitAck(
                    attemptId,
                    stepId,
                    assistant,
                    new DurableFrontier(99L, 2L),
                    manifest,
                    HASH_A,
                    HASH_B,
                    manifest.replaySafety());
            PersistedMessageOccurrence control = new PersistedMessageOccurrence(
                    101L,
                    4L,
                    "control-batch",
                    0,
                    MessageSnapshot.capture(Message.assistant("Choose one option")),
                    "SYSTEM_EVENT",
                    kind == InteractiveStepPlanner.CallKind.ASK_USER
                            ? SessionService.MESSAGE_TYPE_ASK_USER
                            : SessionService.MESSAGE_TYPE_CONFIRMATION,
                    controlId,
                    null,
                    Map.of("payload", payload),
                    "control-trace");
            return new SessionInteractiveControlTransactionService.InteractiveIntentAck(
                    intent, control, selected);
        }

        private void publishVisibilityCallback() {
            doAnswer(invocation -> {
                invocation.getArgument(1, Runnable.class).run();
                return null;
            }).when(archivePreparation).withResultVisibilityAuthority(
                    any(ArchivePreparationCommand.class), any(Runnable.class));
        }

        private void stubProviderContinuation(
                SessionInteractiveControlTransactionService.InteractiveResultAck result) {
            SessionLoopAdmissionService.RecoveryAdmissionAck admission =
                    new SessionLoopAdmissionService.RecoveryAdmissionAck(
                            scope,
                            attemptId,
                            stepId,
                            DurableToolAttemptState.RESULTS_COMMITTED,
                            1L,
                            true,
                            Instant.now().plusSeconds(120));
            when(recoveryCoordinator.recover(sessionId, userId)).thenReturn(
                    new DurableSessionRecoveryCoordinator.RecoveryPlan(
                            DurableSessionRecoveryCoordinator.RecoveryDisposition.PROVIDER_CONTINUE,
                            admission,
                            result.results().postResultFrontier(),
                            null,
                            null,
                            null));
            when(sessionService.getContextMessages(sessionId)).thenReturn(List.of(
                    result.results().results().get(0).message().toMessage()));
            when(sessionService.getActiveRootTraceId(sessionId)).thenReturn("root-trace");
        }
    }
}
