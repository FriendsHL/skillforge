package com.skillforge.server.session;

import com.skillforge.core.engine.durability.DurableFrontier;
import com.skillforge.core.engine.durability.DurableToolAttemptState;
import com.skillforge.core.engine.durability.ExecutionClaimAck;
import com.skillforge.core.engine.durability.ExecutionClaimCommand;
import com.skillforge.core.engine.durability.FrozenJson;
import com.skillforge.core.engine.durability.IntentCommitAck;
import com.skillforge.core.engine.durability.IntentCommitCommand;
import com.skillforge.core.engine.durability.InteractiveStepPlanner;
import com.skillforge.core.engine.durability.LoopDurabilityScope;
import com.skillforge.core.engine.durability.MessageSnapshot;
import com.skillforge.core.engine.durability.PersistedBlockOccurrence;
import com.skillforge.core.engine.durability.PersistedMessageOccurrence;
import com.skillforge.core.engine.durability.ToolCallIntent;
import com.skillforge.core.engine.durability.ToolResultCommitAck;
import com.skillforge.core.engine.durability.ToolResultCommitCommand;
import com.skillforge.core.model.Message;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionMessageEntity;
import com.skillforge.server.entity.SessionToolAttemptEntity;
import com.skillforge.server.repository.SessionMessageRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SessionToolAttemptRepository;
import com.skillforge.server.runtime.RuntimeFailureFact;
import com.skillforge.server.runtime.RuntimeFailureState;
import com.skillforge.server.session.persistence.DurableMessageBatchIntegrityException;
import com.skillforge.server.session.persistence.PersistedMessageCodec;
import com.skillforge.server.session.persistence.SessionOrderedMessageWriter;
import jakarta.persistence.EntityManager;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Transaction owner for durable interactive controls.
 *
 * <p>The assistant Tool vector remains an ordinary provider-visible durable intent. The selected
 * control is appended in the same database transaction as a {@code SYSTEM_EVENT}, so it is
 * recoverable by the harness but filtered from provider history. Answer claims reuse the normal
 * Session/attempt generation CAS; result commit then closes the entire original provider vector.
 */
@Service
public class SessionInteractiveControlTransactionService {

    private static final String SYSTEM_EVENT = "SYSTEM_EVENT";
    private static final String WAITING_USER = "WAITING_USER";
    private static final String EXECUTING = "EXECUTING";
    private static final String RESULTS_COMMITTED = "RESULTS_COMMITTED";
    private static final String PENDING = "pending";
    private static final int MAX_CONTROL_ID_LENGTH = 64;
    private static final int MAX_ANSWER_LENGTH = 65_536;
    private static final int MAX_ANSWER_MODE_LENGTH = 64;
    private static final int MAX_RECOVERED_OPTIONS = 100;
    private static final Set<String> OPEN_ATTEMPT_STATES = Set.of(
            "INTENT_COMMITTED", "EXECUTING", "WAITING_USER",
            "UNCERTAIN_PENDING_RESOLUTION");
    private static final Set<String> RECOVERED_PAYLOAD_FIELDS = Set.of(
            "controlId", "interactionKind", "toolUseId", "toolName", "question",
            "context", "options", "allowOther", "extra");
    private static final Set<String> CONTROL_METADATA_FIELDS = Set.of(
            "schemaVersion", "attemptId", "stepId", "controlId", "interactionKind",
            "providerOrdinal", "toolUseId", "toolName", "state", "answer", "answerMode",
            "payload");

    private final SessionRepository sessionRepository;
    private final SessionMessageRepository messageRepository;
    private final SessionToolAttemptRepository attemptRepository;
    private final SessionOrderedMessageWriter messageWriter;
    private final PersistedMessageCodec messageCodec;
    private final SessionToolAttemptTransactionService attemptTransactions;
    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;

    public SessionInteractiveControlTransactionService(
            SessionRepository sessionRepository,
            SessionMessageRepository messageRepository,
            SessionToolAttemptRepository attemptRepository,
            SessionOrderedMessageWriter messageWriter,
            PersistedMessageCodec messageCodec,
            SessionToolAttemptTransactionService attemptTransactions,
            EntityManager entityManager,
            PlatformTransactionManager transactionManager) {
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository");
        this.messageRepository = Objects.requireNonNull(messageRepository, "messageRepository");
        this.attemptRepository = Objects.requireNonNull(attemptRepository, "attemptRepository");
        this.messageWriter = Objects.requireNonNull(messageWriter, "messageWriter");
        this.messageCodec = Objects.requireNonNull(messageCodec, "messageCodec");
        this.attemptTransactions = Objects.requireNonNull(
                attemptTransactions, "attemptTransactions");
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    /** Atomically writes the exact assistant intent, WAITING_USER attempt and filtered control. */
    public InteractiveIntentAck commitWaitingIntent(InteractiveIntentCommand command) {
        try {
            InteractiveIntentAck acknowledgement = transactionTemplate.execute(
                    ignored -> commitWaitingIntentLocked(command));
            return Objects.requireNonNull(acknowledgement, "interactive intent acknowledgement");
        } catch (InteractiveIntegrityFailure safeFailure) {
            throw safeFailure;
        } catch (RuntimeException persistenceOrProtocolFailure) {
            throw new IllegalStateException("Durable interactive intent persistence failed");
        }
    }

    /** Rebuilds the exact durable control after a recovery loop has fenced and claimed Session. */
    public InteractiveIntentAck loadWaitingControl(
            LoopDurabilityScope recoveryScope, long attemptId) {
        try {
            InteractiveIntentAck acknowledgement = transactionTemplate.execute(
                    ignored -> loadWaitingControlLocked(recoveryScope, attemptId));
            return Objects.requireNonNull(acknowledgement, "waiting control acknowledgement");
        } catch (InteractiveIntegrityFailure safeFailure) {
            throw safeFailure;
        } catch (TransientDataAccessException | CannotCreateTransactionException retryable) {
            throw new DurableRecoveryRetryableException();
        } catch (RuntimeException persistenceOrProtocolFailure) {
            throw new IllegalStateException("Durable interactive control recovery failed");
        }
    }

    /**
     * Reads an exact parked WAITING_USER control without claiming a loop or Tool generation.
     * The expected owner and epoch come from the startup scan and are rechecked under the
     * Session lock, so a concurrent answer or restore turns this read into a closed failure.
     */
    public InteractiveIntentAck loadParkedWaitingControl(
            String sessionId, long expectedUserId, long expectedHistoryEpoch) {
        try {
            InteractiveIntentAck acknowledgement = transactionTemplate.execute(
                    ignored -> loadParkedWaitingControlLocked(
                            sessionId, expectedUserId, expectedHistoryEpoch));
            return Objects.requireNonNull(acknowledgement, "parked waiting control");
        } catch (InteractiveIntegrityFailure safeFailure) {
            throw safeFailure;
        } catch (TransientDataAccessException | CannotCreateTransactionException retryable) {
            throw new DurableRecoveryRetryableException();
        } catch (RuntimeException persistenceOrProtocolFailure) {
            throw new IllegalStateException("Durable interactive control recovery failed");
        }
    }

    /**
     * Records only a sanitized fail-closed runtime fact after parked-control corruption.
     * If the scanned owner/epoch/status changed concurrently, this method is a no-op.
     */
    public void recordParkedWaitingControlRecoveryFailure(
            String sessionId, long expectedUserId, long expectedHistoryEpoch) {
        try {
            transactionTemplate.executeWithoutResult(ignored -> {
                SessionEntity session = sessionRepository.findByIdForUpdate(sessionId)
                        .orElse(null);
                if (session == null
                        || !Objects.equals(session.getUserId(), expectedUserId)
                        || session.getHistoryEpoch() != expectedHistoryEpoch
                        || session.isRestorePreparing()
                        || !"waiting_user".equals(session.getRuntimeStatus())
                        || session.getActiveLoopId() != null
                        || session.getLoopOwnerInstanceId() != null
                        || session.getLoopLeaseUntil() != null) {
                    return;
                }
                RuntimeFailureState.apply(session, new RuntimeFailureFact(
                        "harness",
                        "WAITING_CONTROL_RECOVERY_FAILED",
                        false,
                        "none",
                        "The pending interactive control could not be reconstructed."));
                sessionRepository.save(session);
            });
        } catch (RuntimeException persistenceFailure) {
            throw new IllegalStateException(
                    "Durable interactive control failure could not be recorded");
        }
    }

    /**
     * Claims WAITING_USER through the ordinary generation/fence CAS before any selected effect.
     * Compatibility view for callers that do not dispatch a selected effect themselves.
     */
    public ExecutionClaimAck claimAnswer(ExecutionClaimCommand command, String controlId) {
        return claimAnswerForDispatch(command, controlId).execution();
    }

    /**
     * Atomically claims an answer and tells the caller whether this transaction won dispatch.
     * Exact retries in EXECUTING or RESULTS_COMMITTED read back identity but never grant dispatch.
     */
    public InteractiveAnswerClaimAck claimAnswerForDispatch(
            ExecutionClaimCommand command, String controlId) {
        try {
            InteractiveAnswerClaimAck acknowledgement = transactionTemplate.execute(
                    ignored -> claimAnswerLocked(command, controlId));
            return Objects.requireNonNull(acknowledgement, "interactive answer claim");
        } catch (InteractiveIntegrityFailure safeFailure) {
            throw safeFailure;
        } catch (TransientDataAccessException | CannotCreateTransactionException retryable) {
            throw new DurableRecoveryRetryableException();
        } catch (RuntimeException persistenceOrProtocolFailure) {
            throw new IllegalStateException("Durable interactive answer claim failed");
        }
    }

    /**
     * Rebuilds the exact committed answer ACK without requiring an answer or selected result from
     * the caller. This is the safe whole-HTTP-request replay path after dispatch already closed.
     */
    public InteractiveResultAck loadCommittedAnswerResults(
            ExecutionClaimAck execution, String controlId) {
        try {
            InteractiveResultAck acknowledgement = transactionTemplate.execute(
                    ignored -> loadCommittedAnswerResultsLocked(execution, controlId));
            return Objects.requireNonNull(acknowledgement, "interactive result readback");
        } catch (InteractiveIntegrityFailure safeFailure) {
            throw safeFailure;
        } catch (TransientDataAccessException | CannotCreateTransactionException retryable) {
            throw new DurableRecoveryRetryableException();
        } catch (RuntimeException persistenceOrProtocolFailure) {
            throw new IllegalStateException("Durable interactive result recovery failed");
        }
    }

    /**
     * Marks the control answered and atomically appends the complete provider-ordered result vector.
     * Every non-selected sibling receives ABORTED_FOR_INTERACTIVE_CONTROL without dispatch.
     */
    public InteractiveResultAck commitAnswerResults(InteractiveResultCommand command) {
        try {
            InteractiveResultAck acknowledgement = transactionTemplate.execute(
                    ignored -> commitAnswerResultsLocked(command));
            return Objects.requireNonNull(acknowledgement, "interactive result acknowledgement");
        } catch (InteractiveIntegrityFailure safeFailure) {
            throw safeFailure;
        } catch (RuntimeException persistenceOrProtocolFailure) {
            throw new IllegalStateException("Durable interactive result persistence failed");
        }
    }

    private InteractiveIntentAck commitWaitingIntentLocked(InteractiveIntentCommand command) {
        Objects.requireNonNull(command, "command");
        LoopDurabilityScope origin = command.intent().origin();
        SessionEntity session = sessionRepository.findByIdForUpdate(origin.sessionId())
                .orElseThrow(SessionInteractiveControlTransactionService::integrityFailure);
        validateScopeIdentity(session, origin);

        IntentCommitAck intent = attemptTransactions.commitInteractiveIntent(command.intent());
        SessionToolAttemptEntity attempt = attemptRepository.findBySessionIdAndIdForUpdate(
                        origin.sessionId(), intent.attemptId())
                .orElseThrow(SessionInteractiveControlTransactionService::integrityFailure);
        PersistedMessageCodec.PersistedMessage expectedControl = expectedPendingControl(
                command, intent.attemptId());
        String controlBatchId = controlBatchId(origin.sessionId(), intent.stepId());
        List<SessionMessageEntity> existing = messageRepository
                .findBySessionIdAndWriteBatchIdOrderByWriteBatchOrdinalAsc(
                        origin.sessionId(), controlBatchId);

        PersistedMessageOccurrence control;
        if ("INTENT_COMMITTED".equals(attempt.getState())) {
            if (!existing.isEmpty()) throw integrityFailure();
            control = appendControl(origin.sessionId(), controlBatchId, expectedControl);
            attempt.setState(WAITING_USER);
            SessionToolAttemptEntity saved = attemptRepository.saveAndFlush(attempt);
            entityManager.refresh(saved);
        } else if (WAITING_USER.equals(attempt.getState())) {
            control = readExactControl(origin.sessionId(), controlBatchId, expectedControl);
        } else {
            throw integrityFailure();
        }
        validateControlOccurrence(intent, control, command.controlId(), PENDING, false);
        requireCurrentTail(origin.sessionId(), control);
        session.setMessageCount(Math.toIntExact(messageRepository.countBySessionId(origin.sessionId())));
        return new InteractiveIntentAck(intent, control, command.plan().selectedControl());
    }

    private InteractiveIntentAck loadWaitingControlLocked(
            LoopDurabilityScope recoveryScope, long attemptId) {
        Objects.requireNonNull(recoveryScope, "recoveryScope");
        IntentCommitAck intent = attemptTransactions.loadWaitingIntentForRecovery(
                recoveryScope, attemptId);
        PersistedMessageOccurrence control = readPersistedControl(
                recoveryScope.sessionId(), intent, null, false);
        requireCurrentTail(recoveryScope.sessionId(), control);
        return new InteractiveIntentAck(
                intent, control, selectedControl(intent, control.metadata()));
    }

    private InteractiveIntentAck loadParkedWaitingControlLocked(
            String sessionId, long expectedUserId, long expectedHistoryEpoch) {
        if (sessionId == null || sessionId.isBlank()
                || expectedUserId < 0L || expectedHistoryEpoch < 0L) {
            throw integrityFailure();
        }
        SessionEntity session = sessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(SessionInteractiveControlTransactionService::integrityFailure);
        if (!Objects.equals(session.getUserId(), expectedUserId)
                || session.getHistoryEpoch() != expectedHistoryEpoch
                || session.isRestorePreparing()
                || !SessionEntity.ORIGIN_PRODUCTION.equals(session.getOrigin())
                || !"waiting_user".equals(session.getRuntimeStatus())
                || session.getActiveLoopId() != null
                || session.getLoopOwnerInstanceId() != null
                || session.getLoopLeaseUntil() != null) {
            throw integrityFailure();
        }
        List<SessionToolAttemptEntity> blocking = attemptRepository
                .findBySessionIdAndStateIn(sessionId, OPEN_ATTEMPT_STATES);
        if (blocking.size() != 1 || !WAITING_USER.equals(blocking.get(0).getState())) {
            throw integrityFailure();
        }
        SessionToolAttemptEntity attempt = blocking.get(0);
        if (attempt.getId() == null) throw integrityFailure();
        IntentCommitAck intent = attemptTransactions.loadParkedWaitingIntentLocked(
                sessionId, expectedUserId, expectedHistoryEpoch, attempt.getId());
        PersistedMessageOccurrence control = readPersistedControl(
                sessionId, intent, null, false);
        requireCurrentTail(sessionId, control);
        InteractiveStepPlanner.SelectedControl selected =
                selectedControl(intent, control.metadata());
        validateRecoverablePayload(sessionId, control, selected);
        return new InteractiveIntentAck(intent, control, selected);
    }

    private InteractiveAnswerClaimAck claimAnswerLocked(
            ExecutionClaimCommand command, String controlId) {
        Objects.requireNonNull(command, "command");
        requireControlId(controlId);
        if (command.expectedState() != DurableToolAttemptState.WAITING_USER
                || command.expectedGeneration() != 0L) {
            throw integrityFailure();
        }
        LoopDurabilityScope claimant = command.claimant();
        SessionEntity session = sessionRepository.findByIdForUpdate(claimant.sessionId())
                .orElseThrow(SessionInteractiveControlTransactionService::integrityFailure);
        validateScopeIdentity(session, claimant);
        SessionToolAttemptEntity attempt = attemptRepository.findBySessionIdAndIdForUpdate(
                        claimant.sessionId(), command.attemptId())
                .orElseThrow(SessionInteractiveControlTransactionService::integrityFailure);
        boolean committedRetry = RESULTS_COMMITTED.equals(attempt.getState());
        if (!command.stepId().equals(attempt.getStepId())
                || (!WAITING_USER.equals(attempt.getState())
                    && !EXECUTING.equals(attempt.getState())
                    && !committedRetry)) {
            throw integrityFailure();
        }
        IntentCommitAck intent = WAITING_USER.equals(attempt.getState())
                ? attemptTransactions.loadWaitingIntentForRecovery(claimant, attempt.getId())
                : attemptTransactions.loadInteractiveIntentForResult(claimant, attempt.getId());
        PersistedMessageOccurrence control = readPersistedControl(
                claimant.sessionId(), intent, controlId, committedRetry);
        InteractiveStepPlanner.SelectedControl selected =
                selectedControl(intent, control.metadata());
        if (committedRetry) {
            return new InteractiveAnswerClaimAck(
                    readCommittedAnswerClaim(command, attempt), false, control, selected);
        }
        requireCurrentTail(claimant.sessionId(), control);
        boolean firstClaim = WAITING_USER.equals(attempt.getState());
        return new InteractiveAnswerClaimAck(
                attemptTransactions.claimExecution(command), firstClaim, control, selected);
    }

    private static ExecutionClaimAck readCommittedAnswerClaim(
            ExecutionClaimCommand command, SessionToolAttemptEntity attempt) {
        LoopDurabilityScope claimant = command.claimant();
        long claimedGeneration = command.expectedGeneration() + 1L;
        if (!RESULTS_COMMITTED.equals(attempt.getState())
                || attempt.getExecutionGeneration() != claimedGeneration
                || !command.claimRequestId().equals(attempt.getClaimRequestId())
                || !claimant.loopId().equals(attempt.getExecutionLoopId())
                || !Objects.equals(claimant.loopFence(), attempt.getExecutionFence())
                || !claimant.ownerInstanceId().equals(attempt.getExecutionOwnerInstanceId())
                || attempt.getClaimedAt() == null
                || attempt.getExecutionLeaseUntil() == null
                || !attempt.getExecutionLeaseUntil().isAfter(attempt.getClaimedAt())
                || attempt.getResultBatchId() == null
                || !Objects.equals(claimedGeneration, attempt.getResultExecutionGeneration())
                || !Objects.equals(claimant.loopFence(), attempt.getResultExecutionFence())) {
            throw integrityFailure();
        }
        return new ExecutionClaimAck(
                attempt.getId(),
                attempt.getStepId(),
                attempt.getClaimRequestId(),
                DurableToolAttemptState.RESULTS_COMMITTED,
                claimant,
                attempt.getExecutionGeneration(),
                attempt.getClaimedAt(),
                attempt.getExecutionLeaseUntil());
    }

    private InteractiveResultAck loadCommittedAnswerResultsLocked(
            ExecutionClaimAck execution, String controlId) {
        Objects.requireNonNull(execution, "execution");
        requireControlId(controlId);
        if (execution.state() != DurableToolAttemptState.RESULTS_COMMITTED) {
            throw integrityFailure();
        }
        LoopDurabilityScope scope = execution.executionScope();
        SessionEntity session = sessionRepository.findByIdForUpdate(scope.sessionId())
                .orElseThrow(SessionInteractiveControlTransactionService::integrityFailure);
        validateScopeIdentity(session, scope);
        SessionToolAttemptEntity attempt = attemptRepository.findBySessionIdAndIdForUpdate(
                        scope.sessionId(), execution.attemptId())
                .orElseThrow(SessionInteractiveControlTransactionService::integrityFailure);
        validateExecutionIdentity(execution, attempt);
        if (!RESULTS_COMMITTED.equals(attempt.getState())) throw integrityFailure();

        IntentCommitAck intent = attemptTransactions.loadInteractiveIntentForResult(
                scope, attempt.getId());
        PersistedMessageOccurrence control = readPersistedControl(
                scope.sessionId(), intent, controlId, true);
        validateResolvedControlForReadback(control);
        ToolResultCommitAck resultAck = readCommittedResultAck(
                execution, session, attempt, intent, control);
        return new InteractiveResultAck(
                resultAck, control, selectedControl(intent, control.metadata()));
    }

    private ToolResultCommitAck readCommittedResultAck(
            ExecutionClaimAck execution,
            SessionEntity session,
            SessionToolAttemptEntity attempt,
            IntentCommitAck intent,
            PersistedMessageOccurrence control) {
        UUID resultBatchId = attempt.getResultBatchId();
        int expectedCount = intent.manifest().calls().size();
        if (resultBatchId == null
                || !Objects.equals(execution.executionGeneration(),
                        attempt.getResultExecutionGeneration())
                || !Objects.equals(execution.executionScope().loopFence(),
                        attempt.getResultExecutionFence())
                || attempt.getArchiveTotalCount() != expectedCount
                || !validArchiveCounters(attempt, expectedCount)) {
            throw integrityFailure();
        }

        String batchId = resultBatchId.toString();
        List<SessionMessageEntity> rows = messageRepository
                .findBySessionIdAndWriteBatchIdOrderByWriteBatchOrdinalAsc(
                        execution.executionScope().sessionId(), batchId);
        if (rows.size() != expectedCount) throw integrityFailure();

        List<PersistedMessageCodec.PersistedMessage> expectedRows =
                new ArrayList<>(expectedCount);
        String traceId = null;
        for (int ordinal = 0; ordinal < expectedCount; ordinal++) {
            SessionMessageEntity row = rows.get(ordinal);
            PersistedMessageCodec.PersistedMessage decoded;
            try {
                decoded = messageCodec.decodeRow(encodedRow(row));
            } catch (PersistedMessageCodec.CodecException invalidRow) {
                throw integrityFailure();
            }
            validateCommittedResultMessage(decoded, intent.manifest().calls().get(ordinal));
            if (ordinal == 0) {
                traceId = decoded.traceId();
            } else if (!Objects.equals(traceId, decoded.traceId())) {
                throw integrityFailure();
            }
            expectedRows.add(decoded);
        }

        List<PersistedMessageOccurrence> results;
        try {
            results = messageWriter.readExactBatch(
                    execution.executionScope().sessionId(), batchId, expectedRows);
        } catch (DurableMessageBatchIntegrityException invalidBatch) {
            throw integrityFailure();
        }
        PersistedMessageOccurrence first = results.get(0);
        SessionMessageEntity previous = messageRepository
                .findTopBySessionIdAndSeqNoLessThanOrderBySeqNoDesc(
                        execution.executionScope().sessionId(), first.seqNo())
                .orElseThrow(SessionInteractiveControlTransactionService::integrityFailure);
        if (!Objects.equals(previous.getId(), control.messageId())
                || previous.getSeqNo() != control.seqNo()
                || previous.getSeqNo() + 1L != first.seqNo()) {
            throw integrityFailure();
        }
        PersistedMessageOccurrence last = results.get(results.size() - 1);
        List<PersistedBlockOccurrence> blocks = results.stream()
                .map(result -> persistedBlockOccurrence(
                        execution.executionScope().sessionId(), resultBatchId, result))
                .toList();
        LoopDurabilityScope persistedScope = new LoopDurabilityScope(
                attempt.getSessionId(), session.getUserId(), attempt.getHistoryEpoch(),
                attempt.getExecutionLoopId(), attempt.getExecutionFence(),
                attempt.getExecutionOwnerInstanceId());
        return new ToolResultCommitAck(
                attempt.getId(),
                attempt.getStepId(),
                resultBatchId,
                persistedScope,
                attempt.getResultExecutionGeneration(),
                results,
                blocks,
                new DurableFrontier(control.messageId(), control.seqNo()),
                new DurableFrontier(last.messageId(), last.seqNo()),
                intent.assistantPayloadHash(),
                intent.manifestHash());
    }

    private InteractiveResultAck commitAnswerResultsLocked(InteractiveResultCommand command) {
        Objects.requireNonNull(command, "command");
        ExecutionClaimAck execution = command.execution();
        LoopDurabilityScope scope = execution.executionScope();
        SessionEntity session = sessionRepository.findByIdForUpdate(scope.sessionId())
                .orElseThrow(SessionInteractiveControlTransactionService::integrityFailure);
        validateScopeIdentity(session, scope);
        SessionToolAttemptEntity attempt = attemptRepository.findBySessionIdAndIdForUpdate(
                        scope.sessionId(), execution.attemptId())
                .orElseThrow(SessionInteractiveControlTransactionService::integrityFailure);
        validateExecutionIdentity(execution, attempt);
        IntentCommitAck intent = attemptTransactions.loadInteractiveIntentForResult(
                scope, attempt.getId());
        PersistedMessageOccurrence persistedControl = readPersistedControl(
                scope.sessionId(), intent, command.controlId(),
                RESULTS_COMMITTED.equals(attempt.getState()));
        InteractiveStepPlanner.InteractiveStepPlan plan = new InteractiveStepPlanner.InteractiveStepPlan(
                intent.manifest().calls(), selectedControl(intent, persistedControl.metadata()));
        List<MessageSnapshot> results = plan.completeResultVector(command.selectedResult());

        SessionMessageEntity controlRow = messageRepository.findById(persistedControl.messageId())
                .orElseThrow(SessionInteractiveControlTransactionService::integrityFailure);
        if (EXECUTING.equals(attempt.getState())) {
            if (controlRow.getAnsweredAt() != null) throw integrityFailure();
            controlRow.setAnsweredAt(sessionRepository.currentDatabaseTime());
            controlRow.setMetadataJson(messageCodec.encodeRow(new PersistedMessageCodec.PersistedMessage(
                    persistedControl.message().toMessage(),
                    SYSTEM_EVENT,
                    persistedControl.messageType(),
                    persistedControl.controlId(),
                    controlRow.getAnsweredAt(),
                    resolvedMetadata(persistedControl.metadata(), command),
                    persistedControl.traceId())).metadataJson());
            messageRepository.saveAndFlush(controlRow);
        } else if (RESULTS_COMMITTED.equals(attempt.getState())) {
            validateResolvedControl(persistedControl, command);
        } else {
            throw integrityFailure();
        }

        entityManager.flush();
        entityManager.refresh(controlRow);
        PersistedMessageOccurrence answeredControl = readPersistedControl(
                scope.sessionId(), intent, command.controlId(), true);
        ToolResultCommitCommand resultCommand = new ToolResultCommitCommand(
                scope,
                execution.attemptId(),
                execution.stepId(),
                execution.claimRequestId(),
                execution.executionGeneration(),
                command.resultBatchId(),
                results,
                new DurableFrontier(answeredControl.messageId(), answeredControl.seqNo()),
                intent.assistantPayloadHash(),
                intent.manifestHash(),
                command.traceId());
        ToolResultCommitAck resultAck = attemptTransactions.commitInteractiveResults(
                resultCommand, answeredControl.messageId());
        PersistedMessageOccurrence finalControl = readPersistedControl(
                scope.sessionId(), intent, command.controlId(), true);
        validateResolvedControl(finalControl, command);
        return new InteractiveResultAck(resultAck, finalControl, plan.selectedControl());
    }

    private PersistedMessageOccurrence readPersistedControl(
            String sessionId,
            IntentCommitAck intent,
            String expectedControlId,
            boolean answered) {
        String batchId = controlBatchId(sessionId, intent.stepId());
        List<SessionMessageEntity> rows = messageRepository
                .findBySessionIdAndWriteBatchIdOrderByWriteBatchOrdinalAsc(sessionId, batchId);
        if (rows.size() != 1) throw integrityFailure();
        SessionMessageEntity row = rows.get(0);
        PersistedMessageCodec.PersistedMessage decoded = messageCodec.decodeRow(encodedRow(row));
        PersistedMessageOccurrence control = readExactControl(sessionId, batchId, decoded);
        validateControlOccurrence(intent, control, expectedControlId, null, answered);
        return control;
    }

    private PersistedMessageOccurrence appendControl(
            String sessionId,
            String controlBatchId,
            PersistedMessageCodec.PersistedMessage expectedControl) {
        try {
            return messageWriter.appendNewBatchLocked(
                    sessionId, controlBatchId, List.of(expectedControl)).get(0);
        } catch (DurableMessageBatchIntegrityException failure) {
            throw integrityFailure();
        }
    }

    private PersistedMessageOccurrence readExactControl(
            String sessionId,
            String controlBatchId,
            PersistedMessageCodec.PersistedMessage expectedControl) {
        try {
            return messageWriter.readExactBatch(
                    sessionId, controlBatchId, List.of(expectedControl)).get(0);
        } catch (DurableMessageBatchIntegrityException failure) {
            throw integrityFailure();
        }
    }

    private PersistedMessageCodec.PersistedMessage expectedPendingControl(
            InteractiveIntentCommand command, long attemptId) {
        InteractiveStepPlanner.SelectedControl selected = command.plan().selectedControl();
        return new PersistedMessageCodec.PersistedMessage(
                Message.assistant(command.displayText()),
                SYSTEM_EVENT,
                messageType(selected.kind()),
                command.controlId(),
                null,
                pendingMetadata(command, attemptId),
                command.intent().traceId());
    }

    private static Map<String, Object> pendingMetadata(
            InteractiveIntentCommand command, long attemptId) {
        InteractiveStepPlanner.SelectedControl selected = command.plan().selectedControl();
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("schemaVersion", 1);
        metadata.put("attemptId", attemptId);
        metadata.put("stepId", command.intent().stepId().toString());
        metadata.put("controlId", command.controlId());
        metadata.put("interactionKind", interactionKind(selected.kind()));
        metadata.put("providerOrdinal", selected.providerOrdinal());
        metadata.put("toolUseId", selected.call().toolUseId());
        metadata.put("toolName", selected.call().toolName());
        metadata.put("state", PENDING);
        metadata.put("answer", null);
        metadata.put("answerMode", null);
        metadata.put("payload", command.payload().toJavaValue());
        return metadata;
    }

    private static Map<String, Object> resolvedMetadata(
            Map<String, Object> current, InteractiveResultCommand command) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(current);
        metadata.put("state", command.resolutionKind().wireValue());
        metadata.put("answer", command.answer());
        metadata.put("answerMode", command.answerMode());
        return metadata;
    }

    private static void validateResolvedControl(
            PersistedMessageOccurrence control, InteractiveResultCommand command) {
        if (control.answeredAt() == null
                || !command.resolutionKind().wireValue().equals(control.metadata().get("state"))
                || !Objects.equals(command.answer(), control.metadata().get("answer"))
                || !Objects.equals(command.answerMode(), control.metadata().get("answerMode"))) {
            throw integrityFailure();
        }
    }

    private static void validateResolvedControlForReadback(
            PersistedMessageOccurrence control) {
        Object rawState = control.metadata().get("state");
        boolean knownResolution = false;
        for (ResolutionKind kind : ResolutionKind.values()) {
            knownResolution |= kind.wireValue().equals(rawState);
        }
        Object answer = control.metadata().get("answer");
        Object answerMode = control.metadata().get("answerMode");
        if (control.answeredAt() == null
                || !knownResolution
                || (answer != null && (!(answer instanceof String text)
                    || text.length() > MAX_ANSWER_LENGTH))
                || (answerMode != null && (!(answerMode instanceof String mode)
                    || mode.length() > MAX_ANSWER_MODE_LENGTH))) {
            throw integrityFailure();
        }
    }

    private static void validateCommittedResultMessage(
            PersistedMessageCodec.PersistedMessage persisted, ToolCallIntent call) {
        Message result = persisted.message();
        if (!"NORMAL".equals(persisted.msgType())
                || !"normal".equals(persisted.messageType())
                || persisted.controlId() != null
                || persisted.answeredAt() != null
                || !persisted.metadata().isEmpty()
                || result.getRole() != Message.Role.USER
                || result.getReasoningContent() != null
                || !(result.getContent() instanceof List<?> blocks)
                || blocks.size() != 1
                || !(blocks.get(0) instanceof Map<?, ?> rawBlock)) {
            throw integrityFailure();
        }
        LinkedHashMap<String, Object> block = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawBlock.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw integrityFailure();
            block.put(key, entry.getValue());
        }
        Set<String> fields = block.keySet();
        Set<String> plainFields = Set.of(
                "type", "tool_use_id", "content", "is_error");
        Set<String> errorFields = Set.of(
                "type", "tool_use_id", "content", "is_error", "error_type");
        if ((!fields.equals(plainFields) && !fields.equals(errorFields))
                || !"tool_result".equals(block.get("type"))
                || !call.toolUseId().equals(block.get("tool_use_id"))
                || !(block.get("content") instanceof String)
                || !(block.get("is_error") instanceof Boolean isError)
                || (fields.contains("error_type")
                    && (!isError || !(block.get("error_type") instanceof String errorType)
                        || errorType.isBlank()))) {
            throw integrityFailure();
        }
    }

    private static PersistedBlockOccurrence persistedBlockOccurrence(
            String sessionId, UUID resultBatchId, PersistedMessageOccurrence result) {
        Message message = result.message().toMessage();
        if (!(message.getContent() instanceof List<?> blocks)
                || blocks.size() != 1
                || !(blocks.get(0) instanceof Map<?, ?> block)
                || !(block.get("tool_use_id") instanceof String toolUseId)
                || !(block.get("content") instanceof String content)
                || !(block.get("is_error") instanceof Boolean isError)) {
            throw integrityFailure();
        }
        Object rawErrorType = block.get("error_type");
        if (rawErrorType != null && !(rawErrorType instanceof String)) {
            throw integrityFailure();
        }
        return new PersistedBlockOccurrence(
                result.messageId(), result.seqNo(), sessionId, resultBatchId,
                result.writeBatchOrdinal(), 0, toolUseId, content, isError,
                (String) rawErrorType, result.traceId());
    }

    private static boolean validArchiveCounters(
            SessionToolAttemptEntity attempt, int expectedCount) {
        return switch (attempt.getArchivePreparationState()) {
            case "PENDING", "RAW_FALLBACK" -> attempt.getArchivePreparedCount() == 0;
            case "PREPARED" -> attempt.getArchivePreparedCount() == expectedCount;
            default -> false;
        };
    }

    private static void validateControlOccurrence(
            IntentCommitAck intent,
            PersistedMessageOccurrence control,
            String expectedControlId,
            String expectedState,
            boolean answered) {
        Map<String, Object> metadata = control.metadata();
        InteractiveStepPlanner.SelectedControl selected = selectedControl(intent, metadata);
        if (control.seqNo() != intent.assistant().seqNo() + 1L
                || control.writeBatchOrdinal() != 0
                || control.message().role() != Message.Role.ASSISTANT
                || !control.message().toMessage().getToolUseBlocks().isEmpty()
                || !SYSTEM_EVENT.equals(control.msgType())
                || !messageType(selected.kind()).equals(control.messageType())
                || control.controlId() == null
                || (expectedControlId != null && !expectedControlId.equals(control.controlId()))
                || (answered != (control.answeredAt() != null))
                || !CONTROL_METADATA_FIELDS.equals(metadata.keySet())
                || !Integer.valueOf(1).equals(metadata.get("schemaVersion"))
                || !(metadata.get("attemptId") instanceof Number attemptId)
                || attemptId.longValue() != intent.attemptId()
                || !intent.stepId().toString().equals(metadata.get("stepId"))
                || !control.controlId().equals(metadata.get("controlId"))
                || !interactionKind(selected.kind()).equals(metadata.get("interactionKind"))
                || !selected.call().toolUseId().equals(metadata.get("toolUseId"))
                || !selected.call().toolName().equals(metadata.get("toolName"))
                || (expectedState != null && !expectedState.equals(metadata.get("state")))) {
            throw integrityFailure();
        }
    }

    private static InteractiveStepPlanner.SelectedControl selectedControl(
            IntentCommitAck intent, Map<String, Object> metadata) {
        Object rawOrdinal = metadata.get("providerOrdinal");
        if (!(rawOrdinal instanceof Number number)) throw integrityFailure();
        int ordinal = number.intValue();
        if (ordinal < 0 || ordinal >= intent.manifest().calls().size()
                || number.longValue() != ordinal) {
            throw integrityFailure();
        }
        InteractiveStepPlanner.CallKind kind;
        Object rawKind = metadata.get("interactionKind");
        if ("ask_user".equals(rawKind)) {
            kind = InteractiveStepPlanner.CallKind.ASK_USER;
        } else if ("confirmation".equals(rawKind)) {
            kind = InteractiveStepPlanner.CallKind.CONFIRMATION;
        } else {
            throw integrityFailure();
        }
        return new InteractiveStepPlanner.SelectedControl(intent.manifest().calls().get(ordinal), kind);
    }

    private static void validateRecoverablePayload(
            String sessionId,
            PersistedMessageOccurrence control,
            InteractiveStepPlanner.SelectedControl selected) {
        Object rawPayload = control.metadata().get("payload");
        if (!(rawPayload instanceof Map<?, ?> payload)
                || !RECOVERED_PAYLOAD_FIELDS.equals(payload.keySet())
                || !control.controlId().equals(payload.get("controlId"))
                || !interactionKind(selected.kind()).equals(payload.get("interactionKind"))
                || !selected.call().toolUseId().equals(payload.get("toolUseId"))
                || !selected.call().toolName().equals(payload.get("toolName"))
                || !(payload.get("question") instanceof String question)
                || question.isBlank()
                || question.length() > MAX_ANSWER_LENGTH
                || !question.equals(control.message().toMessage().getTextContent())
                || !(payload.get("context") instanceof String context)
                || context.length() > MAX_ANSWER_LENGTH
                || !(payload.get("allowOther") instanceof Boolean)
                || !(payload.get("options") instanceof List<?> options)
                || options.isEmpty()
                || options.size() > MAX_RECOVERED_OPTIONS
                || !(payload.get("extra") instanceof Map<?, ?> extra)) {
            throw integrityFailure();
        }
        validateRecoveredOptions(selected.kind(), options);
        if (selected.kind() == InteractiveStepPlanner.CallKind.ASK_USER) {
            if (!extra.isEmpty()) throw integrityFailure();
            return;
        }
        if (!Boolean.FALSE.equals(payload.get("allowOther"))) throw integrityFailure();
        validateRecoveredConfirmationExtra(
                sessionId, question, context, selected, extra);
    }

    private static void validateRecoveredOptions(
            InteractiveStepPlanner.CallKind kind, List<?> options) {
        Set<String> allowedFields = kind == InteractiveStepPlanner.CallKind.ASK_USER
                ? Set.of("label", "description")
                : Set.of("value", "label", "style");
        for (Object rawOption : options) {
            if (!(rawOption instanceof Map<?, ?> option)
                    || !allowedFields.containsAll(option.keySet())
                    || !(option.get("label") instanceof String label)
                    || label.length() > MAX_ANSWER_LENGTH) {
                throw integrityFailure();
            }
            if (kind == InteractiveStepPlanner.CallKind.ASK_USER) {
                Object description = option.get("description");
                if (description != null
                        && (!(description instanceof String text)
                            || text.length() > MAX_ANSWER_LENGTH)) {
                    throw integrityFailure();
                }
            } else if (!(option.get("value") instanceof String value)
                    || value.length() > MAX_ANSWER_LENGTH
                    || !(option.get("style") instanceof String style)
                    || style.length() > MAX_ANSWER_MODE_LENGTH) {
                throw integrityFailure();
            }
        }
    }

    private static void validateRecoveredConfirmationExtra(
            String sessionId,
            String question,
            String context,
            InteractiveStepPlanner.SelectedControl selected,
            Map<?, ?> extra) {
        Set<String> fields = Set.of(
                "confirmationKind", "sessionId", "installTool", "installTarget",
                "commandPreview", "title", "description", "expiresAt", "toolInput");
        if (!fields.equals(extra.keySet())
                || !sessionId.equals(extra.get("sessionId"))
                || !(extra.get("confirmationKind") instanceof String confirmationKind)
                || confirmationKind.isBlank()
                || confirmationKind.length() > MAX_ANSWER_MODE_LENGTH
                || !(extra.get("title") instanceof String title)
                || title.isBlank()
                || title.length() > MAX_ANSWER_LENGTH
                || !question.equals(title)
                || !(extra.get("description") instanceof String description)
                || description.length() > MAX_ANSWER_LENGTH
                || !context.equals(description)
                || !(extra.get("toolInput") instanceof Map<?, ?> toolInput)
                || !selected.call().input().equals(FrozenJson.capture(toolInput))) {
            throw integrityFailure();
        }
        for (String field : List.of(
                "installTool", "installTarget", "commandPreview", "expiresAt")) {
            Object value = extra.get(field);
            if (value != null
                    && (!(value instanceof String text)
                        || text.length() > MAX_ANSWER_LENGTH)) {
                throw integrityFailure();
            }
        }
        Object expiresAt = extra.get("expiresAt");
        if (expiresAt instanceof String value && !value.isBlank()) {
            try {
                java.time.Instant.parse(value);
            } catch (java.time.format.DateTimeParseException invalid) {
                throw integrityFailure();
            }
        }
    }

    private void requireCurrentTail(String sessionId, PersistedMessageOccurrence expected) {
        SessionMessageEntity tail = messageRepository.findTopBySessionIdOrderBySeqNoDesc(sessionId)
                .orElseThrow(SessionInteractiveControlTransactionService::integrityFailure);
        if (!Objects.equals(tail.getId(), expected.messageId())
                || tail.getSeqNo() != expected.seqNo()) {
            throw integrityFailure();
        }
    }

    private static void validateExecutionIdentity(
            ExecutionClaimAck execution, SessionToolAttemptEntity attempt) {
        if (!execution.stepId().equals(attempt.getStepId())
                || !execution.claimRequestId().equals(attempt.getClaimRequestId())
                || execution.executionGeneration() != attempt.getExecutionGeneration()
                || !execution.executionScope().loopId().equals(attempt.getExecutionLoopId())
                || !Objects.equals(execution.executionScope().loopFence(), attempt.getExecutionFence())
                || !execution.executionScope().ownerInstanceId()
                        .equals(attempt.getExecutionOwnerInstanceId())
                || (!EXECUTING.equals(attempt.getState())
                    && !RESULTS_COMMITTED.equals(attempt.getState()))) {
            throw integrityFailure();
        }
    }

    private static void validateScopeIdentity(SessionEntity session, LoopDurabilityScope scope) {
        if (!Objects.equals(session.getUserId(), scope.userId())
                || session.getHistoryEpoch() != scope.historyEpoch()
                || session.isRestorePreparing()
                || !scope.loopId().equals(session.getActiveLoopId())
                || session.getLoopFence() != scope.loopFence()
                || !scope.ownerInstanceId().equals(session.getLoopOwnerInstanceId())) {
            throw integrityFailure();
        }
    }

    private static PersistedMessageCodec.EncodedRow encodedRow(SessionMessageEntity row) {
        return new PersistedMessageCodec.EncodedRow(
                row.getRole(), row.getContentJson(), row.getReasoningContent(), row.getMsgType(),
                row.getMessageType(), row.getControlId(), row.getAnsweredAt(),
                row.getMetadataJson(), row.getTraceId());
    }

    private static String controlBatchId(String sessionId, UUID stepId) {
        String identity = "skillforge:durable-interactive-control:" + sessionId + ":" + stepId;
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String messageType(InteractiveStepPlanner.CallKind kind) {
        return switch (kind) {
            case ASK_USER -> "ask_user";
            case CONFIRMATION -> "confirmation";
            case NORMAL -> throw integrityFailure();
        };
    }

    private static String interactionKind(InteractiveStepPlanner.CallKind kind) {
        return messageType(kind);
    }

    private static void requireControlId(String controlId) {
        if (controlId == null || controlId.isBlank() || controlId.length() > MAX_CONTROL_ID_LENGTH) {
            throw new IllegalArgumentException("controlId must contain 1-64 characters");
        }
    }

    private static void requireBoundedNullable(String value, int max, String field) {
        if (value != null && value.length() > max) {
            throw new IllegalArgumentException(field + " is too long");
        }
    }

    private static InteractiveIntegrityFailure integrityFailure() {
        return new InteractiveIntegrityFailure();
    }

    public record InteractiveIntentCommand(
            IntentCommitCommand intent,
            InteractiveStepPlanner.InteractiveStepPlan plan,
            String controlId,
            String displayText,
            FrozenJson payload) {

        public InteractiveIntentCommand {
            Objects.requireNonNull(intent, "intent");
            Objects.requireNonNull(plan, "plan");
            requireControlId(controlId);
            Objects.requireNonNull(displayText, "displayText");
            Objects.requireNonNull(payload, "payload");
            if (!intent.manifest().calls().equals(plan.calls())) {
                throw new IllegalArgumentException("interactive plan must match intent manifest");
            }
        }
    }

    public record InteractiveIntentAck(
            IntentCommitAck intent,
            PersistedMessageOccurrence control,
            InteractiveStepPlanner.SelectedControl selectedControl) {

        public InteractiveIntentAck {
            Objects.requireNonNull(intent, "intent");
            Objects.requireNonNull(control, "control");
            Objects.requireNonNull(selectedControl, "selectedControl");
        }
    }

    /** Atomic claim outcome; only {@code dispatchGranted=true} authorizes selected effect dispatch. */
    public record InteractiveAnswerClaimAck(
            ExecutionClaimAck execution,
            boolean dispatchGranted,
            PersistedMessageOccurrence control,
            InteractiveStepPlanner.SelectedControl selectedControl) {

        public InteractiveAnswerClaimAck {
            Objects.requireNonNull(execution, "execution");
            Objects.requireNonNull(control, "control");
            Objects.requireNonNull(selectedControl, "selectedControl");
            if (dispatchGranted
                    && execution.state() != DurableToolAttemptState.EXECUTING) {
                throw new IllegalArgumentException(
                        "dispatch may only be granted for an executing claim");
            }
            if (execution.state() != DurableToolAttemptState.EXECUTING
                    && execution.state() != DurableToolAttemptState.RESULTS_COMMITTED) {
                throw new IllegalArgumentException(
                        "interactive answer claim must be executing or results committed");
            }
        }
    }

    public record InteractiveResultCommand(
            ExecutionClaimAck execution,
            String controlId,
            UUID resultBatchId,
            MessageSnapshot selectedResult,
            ResolutionKind resolutionKind,
            String answer,
            String answerMode,
            String traceId) {

        public InteractiveResultCommand {
            Objects.requireNonNull(execution, "execution");
            requireControlId(controlId);
            Objects.requireNonNull(resultBatchId, "resultBatchId");
            Objects.requireNonNull(selectedResult, "selectedResult");
            Objects.requireNonNull(resolutionKind, "resolutionKind");
            requireBoundedNullable(answer, MAX_ANSWER_LENGTH, "answer");
            requireBoundedNullable(answerMode, MAX_ANSWER_MODE_LENGTH, "answerMode");
        }
    }

    public record InteractiveResultAck(
            ToolResultCommitAck results,
            PersistedMessageOccurrence control,
            InteractiveStepPlanner.SelectedControl selectedControl) {

        public InteractiveResultAck {
            Objects.requireNonNull(results, "results");
            Objects.requireNonNull(control, "control");
            Objects.requireNonNull(selectedControl, "selectedControl");
        }
    }

    public enum ResolutionKind {
        ANSWERED("answered"),
        APPROVED("approved"),
        DENIED("denied"),
        TIMEOUT("timeout"),
        CANCELLED("cancelled"),
        SUPERSEDED("superseded");

        private final String wireValue;

        ResolutionKind(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }

    /** Closed failure that never includes Session, control, answer, or Tool payload data. */
    private static final class InteractiveIntegrityFailure extends IllegalStateException {

        private InteractiveIntegrityFailure() {
            super("Durable interactive control is partial or inconsistent");
        }
    }
}
