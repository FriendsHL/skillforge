package com.skillforge.server.session;

import com.skillforge.core.engine.durability.ArchivePreparationCommand;
import com.skillforge.core.engine.durability.DurableFrontier;
import com.skillforge.core.engine.durability.DurableToolAttemptState;
import com.skillforge.core.engine.durability.ExecutionClaimAck;
import com.skillforge.core.engine.durability.ExecutionClaimCommand;
import com.skillforge.core.engine.durability.IntentCommitAck;
import com.skillforge.core.engine.durability.LoopDurabilityScope;
import com.skillforge.core.engine.durability.MessageSnapshot;
import com.skillforge.core.engine.durability.ToolResultCommitAck;
import com.skillforge.core.engine.durability.ToolResultCommitCommand;
import com.skillforge.core.model.Message;
import com.skillforge.server.entity.SessionCancellationReceiptEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionToolAttemptEntity;
import com.skillforge.server.repository.SessionCancellationReceiptRepository;
import com.skillforge.server.repository.SessionMessageRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SessionToolAttemptRepository;
import com.skillforge.server.runtime.RuntimeFailureFact;
import com.skillforge.server.runtime.RuntimeFailureState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Synchronous durable boundary for user cancellation.
 *
 * <p>The Session row is always locked first. A committed receipt is the only success oracle:
 * the in-memory cancellation signal may be sent after this method returns, but never before it.
 * A Tool that may already be running is fenced into unknown outcome. A persisted Tool intent that
 * has not been claimed is closed with a complete provider-ordered cancellation result vector.
 */
@Service
public class SessionDurableCancellationService {

    private static final List<String> ATTEMPT_STATES = List.of(
            "INTENT_COMMITTED", "EXECUTING", "WAITING_USER", "RESULTS_COMMITTED",
            "UNCERTAIN_PENDING_RESOLUTION", "RESOLVED_UNKNOWN");
    private static final Set<String> OPEN_ATTEMPT_STATES = Set.of(
            "INTENT_COMMITTED", "EXECUTING", "WAITING_USER",
            "UNCERTAIN_PENDING_RESOLUTION");
    private static final Set<String> ARCHIVE_READY_STATES = Set.of(
            "PREPARED", "RAW_FALLBACK");
    private static final String CANCELLED_RESULT =
            "Cancelled by user before Tool execution.";
    private static final String CANCELLED_INTERACTIVE_RESULT =
            "Cancelled by user before interactive action.";
    private static final String CANCELLED_ERROR_TYPE = "USER_CANCELLED";
    private static final String CANCEL_TRACE_ID = "durable-cancellation";

    private final SessionRepository sessionRepository;
    private final SessionToolAttemptRepository attemptRepository;
    private final SessionMessageRepository messageRepository;
    private final SessionCancellationReceiptRepository receiptRepository;
    private final SessionLoopAdmissionService loopAdmissionService;
    private final SessionToolAttemptTransactionService attemptTransactions;
    private final SessionInteractiveControlTransactionService interactiveTransactions;
    private final OccurrenceArchivePreparation archivePreparation;
    private final TransactionTemplate transactionTemplate;

    public SessionDurableCancellationService(
            SessionRepository sessionRepository,
            SessionToolAttemptRepository attemptRepository,
            SessionMessageRepository messageRepository,
            SessionCancellationReceiptRepository receiptRepository,
            SessionLoopAdmissionService loopAdmissionService,
            SessionToolAttemptTransactionService attemptTransactions,
            SessionInteractiveControlTransactionService interactiveTransactions,
            OccurrenceArchivePreparation archivePreparation,
            PlatformTransactionManager transactionManager) {
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository");
        this.attemptRepository = Objects.requireNonNull(attemptRepository, "attemptRepository");
        this.messageRepository = Objects.requireNonNull(messageRepository, "messageRepository");
        this.receiptRepository = Objects.requireNonNull(receiptRepository, "receiptRepository");
        this.loopAdmissionService = Objects.requireNonNull(
                loopAdmissionService, "loopAdmissionService");
        this.attemptTransactions = Objects.requireNonNull(
                attemptTransactions, "attemptTransactions");
        this.interactiveTransactions = Objects.requireNonNull(
                interactiveTransactions, "interactiveTransactions");
        this.archivePreparation = Objects.requireNonNull(
                archivePreparation, "archivePreparation");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    public CancellationAck cancel(CancellationCommand command) {
        try {
            CancellationAck acknowledgement = transactionTemplate.execute(
                    ignored -> cancelLocked(command));
            return Objects.requireNonNull(acknowledgement, "cancellation acknowledgement");
        } catch (DurableCancellationRejectedException
                | DurableCancellationRetryableException safeFailure) {
            throw safeFailure;
        } catch (RuntimeException persistenceOrProtocolFailure) {
            if (DurableRecoveryRetryableException.isInfrastructureTransient(
                    persistenceOrProtocolFailure)) {
                throw new DurableCancellationRetryableException();
            }
            // Never attach SQL diagnostics: request/session identifiers can be present there.
            throw new IllegalStateException("Durable cancellation failed");
        }
    }

    /**
     * Reconciles a stale in-memory loop with the exact committed cancellation that fenced it.
     * A caller must never infer cancellation from a missing active loop alone.
     */
    public Optional<CancellationAck> findCommittedForTarget(LoopDurabilityScope target) {
        Objects.requireNonNull(target, "target");
        try {
            return transactionTemplate.execute(ignored -> {
                SessionEntity session = sessionRepository.findByIdForUpdate(target.sessionId())
                        .orElseThrow(SessionDurableCancellationService::rejected);
                if (!Objects.equals(session.getUserId(), target.userId())
                        || session.getHistoryEpoch() != target.historyEpoch()
                        || session.getLoopFence() != target.loopFence()
                        || session.getActiveLoopId() != null
                        || session.getLoopOwnerInstanceId() != null
                        || session.getLoopLeaseUntil() != null) {
                    return Optional.empty();
                }
                SessionCancellationReceiptEntity receipt = receiptRepository
                        .findBySessionIdAndHistoryEpochAndTargetLoopIdAndTargetLoopFenceAndTargetOwnerInstanceId(
                                target.sessionId(), target.historyEpoch(), target.loopId(),
                                target.loopFence(), target.ownerInstanceId())
                        .orElse(null);
                if (receipt == null) return Optional.empty();
                validateCommittedReceipt(receipt);
                return Optional.of(ack(receipt));
            });
        } catch (DurableCancellationRejectedException safeFailure) {
            throw safeFailure;
        } catch (RuntimeException persistenceOrProtocolFailure) {
            if (DurableRecoveryRetryableException.isInfrastructureTransient(
                    persistenceOrProtocolFailure)) {
                throw new DurableCancellationRetryableException();
            }
            throw new IllegalStateException("Durable cancellation reconciliation failed");
        }
    }

    private CancellationAck cancelLocked(CancellationCommand command) {
        Objects.requireNonNull(command, "command");
        SessionEntity session = sessionRepository.findByIdForUpdate(command.sessionId())
                .orElseThrow(SessionDurableCancellationService::rejected);
        validateActor(session, command.userId());

        SessionCancellationReceiptEntity existing = receiptRepository
                .findById(command.requestId()).orElse(null);
        if (existing != null) {
            return replayExactReceipt(command, session, existing);
        }
        Instant databaseNow = sessionRepository.currentDatabaseTime();
        List<SessionToolAttemptEntity> allAttempts = attemptRepository
                .findBySessionIdAndStateIn(command.sessionId(), ATTEMPT_STATES);
        List<SessionToolAttemptEntity> openAttempts = allAttempts.stream()
                .filter(value -> OPEN_ATTEMPT_STATES.contains(value.getState()))
                .toList();
        if (openAttempts.size() > 1) throw rejected();

        LoopDurabilityScope target;
        Transition transition;
        if (openAttempts.isEmpty()) {
            validateLiveTarget(session);
            target = scopeOf(session);
            transition = prepareCommittedTailBeforeFencing(target, allAttempts);
        } else {
            SessionToolAttemptEntity attempt = attemptRepository
                    .findBySessionIdAndIdForUpdate(command.sessionId(), openAttempts.get(0).getId())
                    .orElseThrow(SessionDurableCancellationService::rejected);
            if (DurableToolAttemptState.WAITING_USER.name().equals(attempt.getState())) {
                target = claimWaitingCancellationScope(command.requestId(), session, attempt);
                transition = closeWaitingControl(command.requestId(), target, attempt);
            } else {
                validateLiveTarget(session);
                target = scopeOf(session);
                transition = switch (attempt.getState()) {
                    case "INTENT_COMMITTED" -> closeUnexecutedIntent(
                            command.requestId(), target, attempt);
                    case "EXECUTING" -> parkExecutingAttempt(target, attempt, databaseNow);
                    // Unresolved outcomes can only leave through the audited resolution API.
                    default -> throw rejected();
                };
            }
        }

        applyCancellationState(session, transition.outcome(), databaseNow);
        SessionCancellationReceiptEntity receipt = new SessionCancellationReceiptEntity();
        receipt.setRequestId(command.requestId());
        receipt.setSessionId(command.sessionId());
        receipt.setUserId(command.userId());
        receipt.setHistoryEpoch(target.historyEpoch());
        receipt.setTargetLoopId(target.loopId());
        receipt.setTargetLoopFence(target.loopFence());
        receipt.setTargetOwnerInstanceId(target.ownerInstanceId());
        receipt.setAttemptId(transition.attemptId());
        receipt.setExecutionGeneration(transition.executionGeneration());
        receipt.setOutcome(transition.outcome().name());
        receipt.setCreatedAt(databaseNow);
        receiptRepository.saveAndFlush(receipt);
        sessionRepository.saveAndFlush(session);
        return ack(receipt);
    }

    private CancellationAck replayExactReceipt(
            CancellationCommand command,
            SessionEntity session,
            SessionCancellationReceiptEntity receipt) {
        if (!command.sessionId().equals(receipt.getSessionId())
                || !Objects.equals(command.userId(), receipt.getUserId())
                || session.getHistoryEpoch() != receipt.getHistoryEpoch()
                || session.getLoopFence() != receipt.getTargetLoopFence()
                || session.getActiveLoopId() != null
                || session.getLoopOwnerInstanceId() != null
                || session.getLoopLeaseUntil() != null) {
            throw rejected();
        }
        validateCommittedReceipt(receipt);
        return ack(receipt);
    }

    private void validateCommittedReceipt(SessionCancellationReceiptEntity receipt) {
        CancellationOutcome outcome = parseOutcome(receipt.getOutcome());
        if (receipt.getAttemptId() == null) {
            if (receipt.getExecutionGeneration() != null
                    || outcome != CancellationOutcome.CANCELLED_BEFORE_EXECUTION) {
                throw rejected();
            }
            return;
        }
        SessionToolAttemptEntity attempt = attemptRepository.findBySessionIdAndIdForUpdate(
                        receipt.getSessionId(), receipt.getAttemptId())
                .orElseThrow(SessionDurableCancellationService::rejected);
        validateReceiptAttempt(receipt, attempt, outcome);
    }

    private static void validateReceiptAttempt(
            SessionCancellationReceiptEntity receipt,
            SessionToolAttemptEntity attempt,
            CancellationOutcome outcome) {
        boolean exactExecution = attempt.getHistoryEpoch() == receipt.getHistoryEpoch()
                && receipt.getTargetLoopId().equals(attempt.getExecutionLoopId())
                && Objects.equals(receipt.getTargetLoopFence(), attempt.getExecutionFence())
                && receipt.getTargetOwnerInstanceId().equals(
                        attempt.getExecutionOwnerInstanceId())
                && Objects.equals(
                        receipt.getExecutionGeneration(), attempt.getExecutionGeneration());
        boolean exactState = switch (outcome) {
            case CANCELLED_BEFORE_EXECUTION,
                    CANCELLED_AFTER_RESULTS_COMMITTED ->
                    "RESULTS_COMMITTED".equals(attempt.getState())
                    && ARCHIVE_READY_STATES.contains(attempt.getArchivePreparationState());
            case EXECUTION_OUTCOME_UNCERTAIN ->
                    "UNCERTAIN_PENDING_RESOLUTION".equals(attempt.getState());
        };
        if (!exactExecution || !exactState) throw rejected();
    }

    private Transition closeUnexecutedIntent(
            UUID cancellationRequestId,
            LoopDurabilityScope target,
            SessionToolAttemptEntity attempt) {
        if (attempt.getHistoryEpoch() != target.historyEpoch()
                || !target.loopId().equals(attempt.getOriginLoopId())
                || attempt.getOriginFence() != target.loopFence()
                || attempt.getExecutionGeneration() != 0L) {
            throw rejected();
        }
        ExecutionClaimAck execution = attemptTransactions.claimExecution(
                new ExecutionClaimCommand(
                        target, attempt.getId(), attempt.getStepId(),
                        derivedId("claim", cancellationRequestId),
                        DurableToolAttemptState.INTENT_COMMITTED, 0L));
        IntentCommitAck intent = attemptTransactions.loadIntentForRecovery(
                target, attempt.getId());
        List<MessageSnapshot> cancellationResults = intent.manifest().calls().stream()
                .map(call -> MessageSnapshot.capture(Message.toolResult(
                        call.toolUseId(), CANCELLED_RESULT, true, CANCELLED_ERROR_TYPE)))
                .toList();
        ToolResultCommitAck result = attemptTransactions.commitResults(
                new ToolResultCommitCommand(
                        target,
                        intent.attemptId(),
                        intent.stepId(),
                        execution.claimRequestId(),
                        execution.executionGeneration(),
                        derivedId("results", cancellationRequestId),
                        cancellationResults,
                        frontierOf(intent),
                        intent.assistantPayloadHash(),
                        intent.manifestHash(),
                        CANCEL_TRACE_ID));
        archivePreparation.ensurePrepared(ArchivePreparationCommand.from(result));
        return Transition.beforeExecution(result.attemptId(), result.executionGeneration());
    }

    private LoopDurabilityScope claimWaitingCancellationScope(
            UUID cancellationRequestId,
            SessionEntity session,
            SessionToolAttemptEntity attempt) {
        if (hasCompleteLiveTarget(session)) {
            validateLiveTarget(session);
            return scopeOf(session);
        }
        if (!hasNoLiveTarget(session)
                || !"waiting_user".equals(session.getRuntimeStatus())
                || attempt.getHistoryEpoch() != session.getHistoryEpoch()) {
            throw rejected();
        }
        SessionLoopAdmissionService.ManualContinuationClaimAck continuation =
                loopAdmissionService.claimManualContinuation(
                        session.getId(),
                        session.getUserId(),
                        session.getHistoryEpoch(),
                        derivedId("loop", cancellationRequestId).toString());
        if (continuation.attemptId() != attempt.getId()
                || !continuation.stepId().equals(attempt.getStepId())) {
            throw rejected();
        }
        return continuation.scope();
    }

    private Transition closeWaitingControl(
            UUID cancellationRequestId,
            LoopDurabilityScope target,
            SessionToolAttemptEntity attempt) {
        SessionInteractiveControlTransactionService.InteractiveIntentAck waiting =
                interactiveTransactions.loadWaitingControl(target, attempt.getId());
        if (waiting.intent().attemptId() != attempt.getId()
                || !waiting.intent().stepId().equals(attempt.getStepId())) {
            throw rejected();
        }
        String controlId = waiting.control().controlId();
        SessionInteractiveControlTransactionService.InteractiveAnswerClaimAck claim =
                interactiveTransactions.claimAnswerForDispatch(
                        new ExecutionClaimCommand(
                                target,
                                attempt.getId(),
                                attempt.getStepId(),
                                derivedId("claim", cancellationRequestId),
                                DurableToolAttemptState.WAITING_USER,
                                0L),
                        controlId);
        if (!claim.dispatchGranted()
                || claim.execution().state() != DurableToolAttemptState.EXECUTING
                || !claim.selectedControl().equals(waiting.selectedControl())) {
            throw rejected();
        }
        MessageSnapshot selectedResult = MessageSnapshot.capture(Message.toolResult(
                claim.selectedControl().call().toolUseId(),
                CANCELLED_INTERACTIVE_RESULT,
                true,
                CANCELLED_ERROR_TYPE));
        SessionInteractiveControlTransactionService.InteractiveResultAck result =
                interactiveTransactions.commitAnswerResults(
                        new SessionInteractiveControlTransactionService.InteractiveResultCommand(
                                claim.execution(),
                                controlId,
                                derivedId("results", cancellationRequestId),
                                selectedResult,
                                SessionInteractiveControlTransactionService.ResolutionKind.CANCELLED,
                                null,
                                "cancel",
                                CANCEL_TRACE_ID));
        archivePreparation.ensurePrepared(ArchivePreparationCommand.from(result.results()));
        return Transition.beforeExecution(
                result.results().attemptId(), result.results().executionGeneration());
    }

    private Transition parkExecutingAttempt(
            LoopDurabilityScope target,
            SessionToolAttemptEntity attempt,
            Instant databaseNow) {
        if (attempt.getHistoryEpoch() != target.historyEpoch()
                || !target.loopId().equals(attempt.getExecutionLoopId())
                || !Objects.equals(target.loopFence(), attempt.getExecutionFence())
                || !target.ownerInstanceId().equals(attempt.getExecutionOwnerInstanceId())
                || attempt.getExecutionGeneration() <= 0L
                || attempt.getClaimRequestId() == null
                || attempt.getExecutionLeaseUntil() == null) {
            throw rejected();
        }
        attempt.setState(DurableToolAttemptState.UNCERTAIN_PENDING_RESOLUTION.name());
        // V197 requires a non-null lease carrier for uncertain attempts. Expiring it at DB time
        // stops renewal while retaining the immutable execution tuple for audit/resolution.
        attempt.setExecutionLeaseUntil(databaseNow);
        attemptRepository.saveAndFlush(attempt);
        return Transition.uncertain(attempt.getId(), attempt.getExecutionGeneration());
    }

    private Transition prepareCommittedTailBeforeFencing(
            LoopDurabilityScope target,
            List<SessionToolAttemptEntity> attempts) {
        String tailBatchId = messageRepository.findTopBySessionIdOrderBySeqNoDesc(target.sessionId())
                .map(com.skillforge.server.entity.SessionMessageEntity::getWriteBatchId)
                .orElse(null);
        SessionToolAttemptEntity committedTail = null;
        for (SessionToolAttemptEntity attempt : attempts) {
            if (!"RESULTS_COMMITTED".equals(attempt.getState())
                    || !target.loopId().equals(attempt.getExecutionLoopId())
                    || !Objects.equals(target.loopFence(), attempt.getExecutionFence())
                    || !target.ownerInstanceId().equals(attempt.getExecutionOwnerInstanceId())
                    || attempt.getResultBatchId() == null
                    || !attempt.getResultBatchId().toString().equals(tailBatchId)) {
                continue;
            }
            if (committedTail != null) throw rejected();
            committedTail = attempt;
            if (!ARCHIVE_READY_STATES.contains(attempt.getArchivePreparationState())) {
                archivePreparation.ensurePreparedForRecovery(target, attempt.getId());
            }
        }
        boolean stillBlocked = attemptRepository
                .findBySessionIdAndStateIn(target.sessionId(), ATTEMPT_STATES).stream()
                .anyMatch(value -> OPEN_ATTEMPT_STATES.contains(value.getState())
                        || (("RESULTS_COMMITTED".equals(value.getState())
                            || "RESOLVED_UNKNOWN".equals(value.getState()))
                            && !ARCHIVE_READY_STATES.contains(
                                    value.getArchivePreparationState())));
        if (stillBlocked) throw rejected();
        return committedTail == null
                ? Transition.beforeExecution(null, null)
                : Transition.afterCommittedResults(
                        committedTail.getId(), committedTail.getExecutionGeneration());
    }

    private static void applyCancellationState(
            SessionEntity session,
            CancellationOutcome outcome,
            Instant databaseNow) {
        if (outcome == CancellationOutcome.EXECUTION_OUTCOME_UNCERTAIN) {
            RuntimeFailureState.apply(session, new RuntimeFailureFact(
                    "user_action", "CANCELLED_TOOL_OUTCOME_UNCERTAIN", false,
                    "possible", "Cancellation occurred during Tool execution."));
            session.setRuntimeStep("cancelled_tool_outcome_uncertain");
        } else {
            // A deterministic user cancellation is a terminal outcome, not a runtime error.
            // The durable receipt carries its exact outcome; V175 requires failure columns to be
            // empty whenever runtime_status is not error.
            RuntimeFailureState.clear(session);
            session.setRuntimeStatus("idle");
            session.setRuntimeStep("cancelled");
            session.setCompletedAt(databaseNow);
        }
        session.setActiveLoopId(null);
        session.setLoopOwnerInstanceId(null);
        session.setLoopLeaseUntil(null);
    }

    private static void validateActor(SessionEntity session, long actorUserId) {
        boolean owner = Objects.equals(session.getUserId(), actorUserId);
        boolean systemSessionOperator = Objects.equals(session.getUserId(), 0L)
                && actorUserId > 0L;
        if ((!owner && !systemSessionOperator) || session.isRestorePreparing()) {
            throw rejected();
        }
    }

    private static void validateLiveTarget(SessionEntity session) {
        if (!hasCompleteLiveTarget(session)) throw rejected();
        try {
            UUID parsed = UUID.fromString(session.getActiveLoopId());
            if (!parsed.toString().equals(session.getActiveLoopId())) throw rejected();
        } catch (IllegalArgumentException invalidLoop) {
            throw rejected();
        }
    }

    private static boolean hasCompleteLiveTarget(SessionEntity session) {
        return session.getActiveLoopId() != null
                && session.getLoopOwnerInstanceId() != null
                && session.getLoopLeaseUntil() != null
                && session.getLoopFence() > 0L;
    }

    private static boolean hasNoLiveTarget(SessionEntity session) {
        return session.getActiveLoopId() == null
                && session.getLoopOwnerInstanceId() == null
                && session.getLoopLeaseUntil() == null;
    }

    private static LoopDurabilityScope scopeOf(SessionEntity session) {
        return new LoopDurabilityScope(
                session.getId(), session.getUserId(), session.getHistoryEpoch(),
                session.getActiveLoopId(), session.getLoopFence(),
                session.getLoopOwnerInstanceId());
    }

    private static DurableFrontier frontierOf(IntentCommitAck intent) {
        return new DurableFrontier(
                intent.assistant().messageId(), intent.assistant().seqNo());
    }

    private static UUID derivedId(String purpose, UUID requestId) {
        return UUID.nameUUIDFromBytes(("skillforge:durable-cancel:" + purpose + ":" + requestId)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static CancellationOutcome parseOutcome(String value) {
        try {
            return CancellationOutcome.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw rejected();
        }
    }

    private static CancellationAck ack(SessionCancellationReceiptEntity receipt) {
        return new CancellationAck(
                receipt.getRequestId(), receipt.getSessionId(), receipt.getUserId(),
                receipt.getHistoryEpoch(), receipt.getTargetLoopId(),
                receipt.getTargetLoopFence(), receipt.getTargetOwnerInstanceId(),
                receipt.getAttemptId(), receipt.getExecutionGeneration(),
                parseOutcome(receipt.getOutcome()), receipt.getCreatedAt());
    }

    private static DurableCancellationRejectedException rejected() {
        return new DurableCancellationRejectedException();
    }

    public record CancellationCommand(
            UUID requestId,
            String sessionId,
            long userId) {

        public CancellationCommand {
            Objects.requireNonNull(requestId, "requestId");
            if (sessionId == null || sessionId.isBlank()) {
                throw new IllegalArgumentException("sessionId must not be blank");
            }
            if (userId < 0L) throw new IllegalArgumentException("userId must be nonnegative");
        }
    }

    public record CancellationAck(
            UUID requestId,
            String sessionId,
            long userId,
            long historyEpoch,
            String targetLoopId,
            long targetLoopFence,
            String targetOwnerInstanceId,
            Long attemptId,
            Long executionGeneration,
            CancellationOutcome outcome,
            Instant createdAt) {

        public CancellationAck {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(targetLoopId, "targetLoopId");
            Objects.requireNonNull(targetOwnerInstanceId, "targetOwnerInstanceId");
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    public enum CancellationOutcome {
        CANCELLED_BEFORE_EXECUTION,
        CANCELLED_AFTER_RESULTS_COMMITTED,
        EXECUTION_OUTCOME_UNCERTAIN
    }

    private record Transition(
            Long attemptId,
            Long executionGeneration,
            CancellationOutcome outcome) {

        static Transition beforeExecution(Long attemptId, Long generation) {
            return new Transition(
                    attemptId, generation, CancellationOutcome.CANCELLED_BEFORE_EXECUTION);
        }

        static Transition uncertain(long attemptId, long generation) {
            return new Transition(
                    attemptId, generation, CancellationOutcome.EXECUTION_OUTCOME_UNCERTAIN);
        }

        static Transition afterCommittedResults(long attemptId, long generation) {
            return new Transition(
                    attemptId, generation,
                    CancellationOutcome.CANCELLED_AFTER_RESULTS_COMMITTED);
        }
    }
}
