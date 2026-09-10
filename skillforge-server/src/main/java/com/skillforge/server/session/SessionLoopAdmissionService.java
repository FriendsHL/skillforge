package com.skillforge.server.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.history.SessionHistoryRowAuthority;
import com.skillforge.core.engine.durability.DurableFrontier;
import com.skillforge.core.engine.durability.DurableToolAttemptState;
import com.skillforge.core.engine.durability.ExecutionClaimAck;
import com.skillforge.core.engine.durability.LoopDurabilityScope;
import com.skillforge.core.engine.durability.MessageSnapshot;
import com.skillforge.core.engine.durability.PersistedMessageOccurrence;
import com.skillforge.core.model.Message;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionToolAttemptEntity;
import com.skillforge.server.repository.SessionMessageRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SessionToolAttemptRepository;
import com.skillforge.server.runtime.RuntimeFailureState;
import com.skillforge.server.runtime.RuntimeFailureFact;
import com.skillforge.server.session.persistence.DurableMessageBatchIntegrityException;
import com.skillforge.server.session.persistence.PersistedMessageCodec;
import com.skillforge.server.session.persistence.SessionOrderedMessageWriter;
import org.springframework.stereotype.Service;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Atomically claims a fenced Session loop and appends its exact initiating USER row. */
@Service
public class SessionLoopAdmissionService {

    // The heartbeat renews every 30 seconds. A two-minute lease tolerates transient
    // scheduler stalls while keeping hard-crash recovery bounded.
    private static final Duration LOOP_LEASE = Duration.ofMinutes(2);
    private static final Set<String> OPEN_ATTEMPT_STATES = Set.of(
            "INTENT_COMMITTED", "EXECUTING", "WAITING_USER", "UNCERTAIN_PENDING_RESOLUTION");
    private static final Set<String> TERMINAL_ATTEMPT_STATES = Set.of(
            "RESULTS_COMMITTED", "RESOLVED_UNKNOWN");
    private static final Set<String> ARCHIVE_READY_STATES = Set.of("PREPARED", "RAW_FALLBACK");
    private static final List<String> ALL_ATTEMPT_STATES = List.of(
            "INTENT_COMMITTED", "EXECUTING", "WAITING_USER", "RESULTS_COMMITTED",
            "UNCERTAIN_PENDING_RESOLUTION", "RESOLVED_UNKNOWN");

    private final SessionRepository sessionRepository;
    private final SessionMessageRepository messageRepository;
    private final SessionToolAttemptRepository attemptRepository;
    private final SessionOrderedMessageWriter messageWriter;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final String ownerInstanceId = UUID.randomUUID().toString();

    public SessionLoopAdmissionService(
            SessionRepository sessionRepository,
            SessionMessageRepository messageRepository,
            SessionToolAttemptRepository attemptRepository,
            SessionOrderedMessageWriter messageWriter,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper) {
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository");
        this.messageRepository = Objects.requireNonNull(messageRepository, "messageRepository");
        this.attemptRepository = Objects.requireNonNull(attemptRepository, "attemptRepository");
        this.messageWriter = Objects.requireNonNull(messageWriter, "messageWriter");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    public AdmissionAck admit(
            String sessionId,
            long userId,
            UUID admissionRequestId,
            String loopId,
            MessageSnapshot userMessage,
            String traceId) {
        try {
            AdmissionAck acknowledgement = transactionTemplate.execute(ignored -> admitLocked(
                    sessionId, userId, admissionRequestId, loopId, userMessage, traceId));
            return Objects.requireNonNull(acknowledgement, "admission acknowledgement");
        } catch (RuntimeException persistenceOrProtocolFailure) {
            // Discard the cause because SQL and codec diagnostics may contain transcript data.
            throw new IllegalStateException("Durable loop admission failed");
        }
    }

    /** Releases only the exact winning loop. The monotonic fence is never decremented. */
    public void release(LoopDurabilityScope scope) {
        try {
            transactionTemplate.executeWithoutResult(ignored -> releaseLocked(scope));
        } catch (RuntimeException persistenceOrProtocolFailure) {
            throw new IllegalStateException("Durable loop release failed");
        }
    }

    /** Renews the exact current loop and any execution claim owned by that same fence. */
    public LeaseAck renew(LoopDurabilityScope scope) {
        try {
            LeaseAck acknowledgement = transactionTemplate.execute(
                    ignored -> renewLocked(scope));
            return Objects.requireNonNull(acknowledgement, "lease acknowledgement");
        } catch (RuntimeException persistenceOrProtocolFailure) {
            if (DurableRecoveryRetryableException.isInfrastructureTransient(
                    persistenceOrProtocolFailure)) {
                throw new DurableRecoveryRetryableException();
            }
            throw new IllegalStateException("Durable loop lease renewal failed");
        }
    }

    /** Claims an expired durable Session for restart recovery without appending a USER row. */
    public RecoveryAdmissionAck claimRecovery(
            String sessionId,
            long userId,
            String loopId) {
        try {
            RecoveryAdmissionAck acknowledgement = transactionTemplate.execute(
                    ignored -> claimRecoveryLocked(sessionId, userId, loopId));
            return Objects.requireNonNull(acknowledgement, "recovery admission acknowledgement");
        } catch (DurableRecoveryNotReadyException notReady) {
            throw notReady;
        } catch (TransientDataAccessException | CannotCreateTransactionException retryable) {
            throw new DurableRecoveryRetryableException();
        } catch (RuntimeException persistenceOrProtocolFailure) {
            throw new DurableRecoveryFailureException();
        }
    }

    /**
     * Claims a new fenced loop for an exact parked {@code WAITING_USER} attempt.
     *
     * <p>The caller must retain {@code loopId} across transport retries. The first
     * successful call advances the Session fence exactly once; an ACK-loss retry
     * with the same loop id returns the existing scope. On first claim this method
     * deliberately leaves the attempt in generation zero so the interactive
     * transaction can claim the answer through the ordinary attempt CAS. A later
     * exact retry may observe that same attempt as EXECUTING or RESULTS_COMMITTED;
     * its persisted execution tuple must still match the active Session scope.
     */
    public ManualContinuationClaimAck claimManualContinuation(
            String sessionId,
            long userId,
            long expectedHistoryEpoch,
            String loopId) {
        try {
            ManualContinuationClaimAck acknowledgement = transactionTemplate.execute(
                    ignored -> claimManualContinuationLocked(
                            sessionId, userId, expectedHistoryEpoch, loopId));
            return Objects.requireNonNull(
                    acknowledgement, "manual continuation acknowledgement");
        } catch (DurableRecoveryNotReadyException busy) {
            throw busy;
        } catch (RuntimeException persistenceOrProtocolFailure) {
            if (DurableRecoveryRetryableException.isInfrastructureTransient(
                    persistenceOrProtocolFailure)) {
                throw new DurableRecoveryRetryableException();
            }
            throw new DurableRecoveryFailureException();
        }
    }

    private ManualContinuationClaimAck claimManualContinuationLocked(
            String sessionId,
            long userId,
            long expectedHistoryEpoch,
            String loopId) {
        requireSessionId(sessionId);
        if (userId < 0L || expectedHistoryEpoch < 0L) throw new IllegalStateException();
        requireCanonicalLoopId(loopId);

        // Lock ordering is always Session first, then the one continuation attempt.
        SessionEntity session = sessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(IllegalStateException::new);
        Instant databaseNow = sessionRepository.currentDatabaseTime();
        if (!Objects.equals(session.getUserId(), userId)
                || session.getHistoryEpoch() != expectedHistoryEpoch
                || session.isRestorePreparing()) {
            throw new IllegalStateException();
        }
        if (!SessionHistoryRowAuthority.isVerified(session, messageRepository, objectMapper)) {
            throw new IllegalStateException("Authoritative row history is required");
        }

        boolean hasLoopId = session.getActiveLoopId() != null;
        boolean hasOwner = session.getLoopOwnerInstanceId() != null;
        boolean hasLease = session.getLoopLeaseUntil() != null;
        if (hasLoopId != hasOwner || hasLoopId != hasLease) {
            throw new IllegalStateException();
        }
        List<SessionToolAttemptEntity> allAttempts = attemptRepository
                .findBySessionIdAndStateIn(sessionId, ALL_ATTEMPT_STATES);
        if (hasLoopId) {
            boolean exactRetry = loopId.equals(session.getActiveLoopId())
                    && ownerInstanceId.equals(session.getLoopOwnerInstanceId())
                    && session.getLoopFence() > 0L;
            if (!exactRetry) throw new DurableRecoveryNotReadyException();
            SessionToolAttemptEntity retryCandidate = selectManualContinuationRetry(
                    session, allAttempts, expectedHistoryEpoch);
            SessionToolAttemptEntity attempt = attemptRepository
                    .findBySessionIdAndIdForUpdate(sessionId, retryCandidate.getId())
                    .orElseThrow(IllegalStateException::new);
            validateManualContinuationRetry(session, attempt, expectedHistoryEpoch);
            return manualContinuationAck(session, attempt);
        }
        if (!"waiting_user".equals(session.getRuntimeStatus())) {
            throw new IllegalStateException();
        }
        List<SessionToolAttemptEntity> blockingAttempts = allAttempts.stream()
                .filter(SessionLoopAdmissionService::isBlockingAttempt)
                .toList();
        if (blockingAttempts.size() != 1
                || !DurableToolAttemptState.WAITING_USER.name()
                        .equals(blockingAttempts.get(0).getState())) {
            throw new IllegalStateException();
        }
        SessionToolAttemptEntity attempt = attemptRepository
                .findBySessionIdAndIdForUpdate(sessionId, blockingAttempts.get(0).getId())
                .orElseThrow(IllegalStateException::new);
        validatePristineWaitingAttempt(attempt, expectedHistoryEpoch);

        session.setActiveLoopId(loopId);
        session.setLoopFence(Math.addExact(session.getLoopFence(), 1L));
        session.setLoopOwnerInstanceId(ownerInstanceId);
        session.setLoopLeaseUntil(databaseNow.plus(LOOP_LEASE));
        session.setRuntimeStatus("running");
        session.setRuntimeStep("Answering interactive control");
        RuntimeFailureState.clear(session);
        SessionEntity saved = sessionRepository.saveAndFlush(session);
        return manualContinuationAck(saved, attempt);
    }

    private static void validatePristineWaitingAttempt(
            SessionToolAttemptEntity attempt, long expectedHistoryEpoch) {
        if (!DurableToolAttemptState.WAITING_USER.name().equals(attempt.getState())
                || attempt.getHistoryEpoch() != expectedHistoryEpoch
                || attempt.getExecutionGeneration() != 0L
                || attempt.getExecutionLoopId() != null
                || attempt.getExecutionFence() != null
                || attempt.getExecutionOwnerInstanceId() != null
                || attempt.getClaimRequestId() != null
                || attempt.getClaimedAt() != null
                || attempt.getExecutionLeaseUntil() != null
                || attempt.getResultBatchId() != null
                || attempt.getResultExecutionGeneration() != null
                || attempt.getResultExecutionFence() != null) {
            throw new IllegalStateException();
        }
    }

    private static ManualContinuationClaimAck manualContinuationAck(
            SessionEntity session, SessionToolAttemptEntity attempt) {
        LoopDurabilityScope scope = new LoopDurabilityScope(
                session.getId(),
                session.getUserId(),
                session.getHistoryEpoch(),
                session.getActiveLoopId(),
                session.getLoopFence(),
                session.getLoopOwnerInstanceId());
        return new ManualContinuationClaimAck(
                scope, attempt.getId(), attempt.getStepId(), session.getLoopLeaseUntil());
    }

    private static SessionToolAttemptEntity selectManualContinuationRetry(
            SessionEntity session,
            List<SessionToolAttemptEntity> attempts,
            long expectedHistoryEpoch) {
        List<SessionToolAttemptEntity> candidates = attempts.stream()
                .filter(attempt -> attempt.getHistoryEpoch() == expectedHistoryEpoch)
                .filter(attempt -> isManualContinuationRetryCandidate(session, attempt))
                .toList();
        if (candidates.size() != 1) throw new IllegalStateException();
        return candidates.get(0);
    }

    private static boolean isManualContinuationRetryCandidate(
            SessionEntity session, SessionToolAttemptEntity attempt) {
        if (DurableToolAttemptState.WAITING_USER.name().equals(attempt.getState())) {
            return attempt.getExecutionGeneration() == 0L;
        }
        boolean completedAnswerState = DurableToolAttemptState.EXECUTING.name()
                .equals(attempt.getState())
                || DurableToolAttemptState.RESULTS_COMMITTED.name().equals(attempt.getState());
        return completedAnswerState
                && attempt.getExecutionGeneration() == 1L
                && session.getActiveLoopId().equals(attempt.getExecutionLoopId())
                && Objects.equals(session.getLoopFence(), attempt.getExecutionFence())
                && session.getLoopOwnerInstanceId()
                        .equals(attempt.getExecutionOwnerInstanceId());
    }

    private static void validateManualContinuationRetry(
            SessionEntity session,
            SessionToolAttemptEntity attempt,
            long expectedHistoryEpoch) {
        if (DurableToolAttemptState.WAITING_USER.name().equals(attempt.getState())) {
            validatePristineWaitingAttempt(attempt, expectedHistoryEpoch);
            return;
        }
        if (attempt.getHistoryEpoch() != expectedHistoryEpoch
                || attempt.getExecutionGeneration() != 1L
                || !session.getActiveLoopId().equals(attempt.getExecutionLoopId())
                || !Objects.equals(session.getLoopFence(), attempt.getExecutionFence())
                || !session.getLoopOwnerInstanceId()
                        .equals(attempt.getExecutionOwnerInstanceId())
                || attempt.getClaimRequestId() == null
                || attempt.getClaimedAt() == null
                || attempt.getExecutionLeaseUntil() == null) {
            throw new IllegalStateException();
        }
        if (DurableToolAttemptState.EXECUTING.name().equals(attempt.getState())) {
            if (attempt.getResultBatchId() != null
                    || attempt.getResultExecutionGeneration() != null
                    || attempt.getResultExecutionFence() != null) {
                throw new IllegalStateException();
            }
            return;
        }
        if (!DurableToolAttemptState.RESULTS_COMMITTED.name().equals(attempt.getState())
                || attempt.getResultBatchId() == null
                || !Objects.equals(1L, attempt.getResultExecutionGeneration())
                || !Objects.equals(
                        session.getLoopFence(), attempt.getResultExecutionFence())) {
            throw new IllegalStateException();
        }
    }

    private RecoveryAdmissionAck claimRecoveryLocked(
            String sessionId,
            long userId,
            String loopId) {
        requireSessionId(sessionId);
        requireCanonicalLoopId(loopId);
        SessionEntity session = sessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(IllegalStateException::new);
        Instant databaseNow = sessionRepository.currentDatabaseTime();
        if (!Objects.equals(session.getUserId(), userId) || session.isRestorePreparing()) {
            throw new IllegalStateException();
        }
        if (!SessionHistoryRowAuthority.isVerified(session, messageRepository, objectMapper)) {
            throw new IllegalStateException("Authoritative row history is required");
        }
        List<SessionToolAttemptEntity> allAttempts = attemptRepository
                .findBySessionIdAndStateIn(sessionId, ALL_ATTEMPT_STATES);
        List<SessionToolAttemptEntity> blocking = allAttempts.stream()
                .filter(SessionLoopAdmissionService::isBlockingAttempt)
                .toList();
        if (blocking.size() > 1) throw new IllegalStateException();
        String tailBatchId = messageRepository.findTopBySessionIdOrderBySeqNoDesc(sessionId)
                .map(com.skillforge.server.entity.SessionMessageEntity::getWriteBatchId)
                .orElse(null);
        SessionToolAttemptEntity candidate = blocking.isEmpty()
                ? allAttempts.stream()
                        .filter(value -> TERMINAL_ATTEMPT_STATES.contains(value.getState()))
                        // A closed historical attempt is a recovery boundary only while
                        // its exact result batch still owns the transcript tail.
                        .filter(value -> value.getResultBatchId() != null
                                && value.getResultBatchId().toString().equals(tailBatchId))
                        .max(java.util.Comparator.comparingLong(SessionToolAttemptEntity::getId))
                        .orElse(null)
                : blocking.get(0);
        SessionToolAttemptEntity attempt = candidate == null
                ? null
                : attemptRepository.findBySessionIdAndIdForUpdate(sessionId, candidate.getId())
                        .orElseThrow(IllegalStateException::new);

        boolean exactRetry = loopId.equals(session.getActiveLoopId())
                && ownerInstanceId.equals(session.getLoopOwnerInstanceId())
                && session.getLoopFence() > 0L;
        if (!exactRetry) {
            boolean completeOldScope = session.getActiveLoopId() != null
                    && session.getLoopOwnerInstanceId() != null
                    && session.getLoopLeaseUntil() != null;
            if (!completeOldScope) {
                throw new IllegalStateException();
            }
            if (session.getLoopLeaseUntil().isAfter(databaseNow)) {
                throw new DurableRecoveryNotReadyException();
            }
            session.setActiveLoopId(loopId);
            session.setLoopFence(Math.addExact(session.getLoopFence(), 1L));
            session.setLoopOwnerInstanceId(ownerInstanceId);
        }
        Instant leaseUntil = databaseNow.plus(LOOP_LEASE);
        session.setLoopLeaseUntil(leaseUntil);
        session.setRuntimeStatus("running");
        session.setRuntimeStep("Recovering");
        sessionRepository.save(session);
        LoopDurabilityScope scope = new LoopDurabilityScope(
                sessionId, userId, session.getHistoryEpoch(), loopId,
                session.getLoopFence(), ownerInstanceId);
        boolean attemptAlreadyClaimedByScope = attempt != null
                && scope.loopId().equals(attempt.getExecutionLoopId())
                && Objects.equals(scope.loopFence(), attempt.getExecutionFence())
                && scope.ownerInstanceId().equals(attempt.getExecutionOwnerInstanceId());
        return new RecoveryAdmissionAck(
                scope,
                attempt != null ? attempt.getId() : null,
                attempt != null ? attempt.getStepId() : null,
                attempt != null ? DurableToolAttemptState.valueOf(attempt.getState()) : null,
                attempt != null ? attempt.getExecutionGeneration() : null,
                attemptAlreadyClaimedByScope,
                leaseUntil);
    }

    /** Atomically records a terminal harness/provider failure only when no attempt needs recovery. */
    public boolean failIfNoBlockingAttempt(
            LoopDurabilityScope scope,
            RuntimeFailureFact failure) {
        try {
            Boolean accepted = transactionTemplate.execute(
                    ignored -> failLocked(scope, failure));
            return Boolean.TRUE.equals(accepted);
        } catch (RuntimeException persistenceOrProtocolFailure) {
            throw new IllegalStateException("Durable loop failure transition failed");
        }
    }

    /**
     * Ends automatic recovery at a durable manual boundary without pretending the
     * blocking Tool attempt has been resolved.
     */
    public void parkForManualContinuation(
            LoopDurabilityScope scope,
            DurableToolAttemptState expectedState,
            RuntimeFailureFact uncertaintyFailure) {
        try {
            transactionTemplate.executeWithoutResult(ignored -> parkLocked(
                    scope, expectedState, uncertaintyFailure));
        } catch (RuntimeException persistenceOrProtocolFailure) {
            throw new IllegalStateException("Durable recovery parking failed");
        }
    }

    /** Makes a claimed-but-unreconstructable recovery visible and releases its lease. */
    public void parkRecoveryFailure(
            LoopDurabilityScope scope,
            RuntimeFailureFact failure) {
        try {
            transactionTemplate.executeWithoutResult(ignored -> {
                Objects.requireNonNull(scope, "scope");
                Objects.requireNonNull(failure, "failure");
                SessionEntity session = sessionRepository.findByIdForUpdate(scope.sessionId())
                        .orElseThrow(IllegalStateException::new);
                if (!Objects.equals(session.getUserId(), scope.userId())
                        || session.getHistoryEpoch() != scope.historyEpoch()
                        || session.isRestorePreparing()
                        || !scope.loopId().equals(session.getActiveLoopId())
                        || session.getLoopFence() != scope.loopFence()
                        || !scope.ownerInstanceId().equals(
                                session.getLoopOwnerInstanceId())) {
                    throw new IllegalStateException();
                }
                RuntimeFailureState.apply(session, failure);
                session.setRuntimeStatus("error");
                session.setRuntimeStep("recovery_failed");
                session.setActiveLoopId(null);
                session.setLoopOwnerInstanceId(null);
                session.setLoopLeaseUntil(null);
                sessionRepository.save(session);
            });
        } catch (RuntimeException persistenceOrProtocolFailure) {
            throw new IllegalStateException("Durable recovery failure parking failed");
        }
    }

    /**
     * User cancellation invalidates a still-running Tool generation immediately.
     * A late executor can no longer commit results, and no automatic safe replay
     * is started against the user's cancellation intent.
     */
    public void parkCancelledExecution(
            LoopDurabilityScope scope,
            ExecutionClaimAck execution,
            RuntimeFailureFact failure) {
        try {
            transactionTemplate.executeWithoutResult(ignored -> {
                Objects.requireNonNull(scope, "scope");
                Objects.requireNonNull(execution, "execution");
                Objects.requireNonNull(failure, "failure");
                if (!scope.equals(execution.executionScope())) throw new IllegalStateException();
                SessionEntity session = sessionRepository.findByIdForUpdate(scope.sessionId())
                        .orElseThrow(IllegalStateException::new);
                if (!Objects.equals(session.getUserId(), scope.userId())
                        || session.getHistoryEpoch() != scope.historyEpoch()
                        || session.isRestorePreparing()
                        || !scope.loopId().equals(session.getActiveLoopId())
                        || session.getLoopFence() != scope.loopFence()
                        || !scope.ownerInstanceId().equals(
                                session.getLoopOwnerInstanceId())) {
                    throw new IllegalStateException();
                }
                SessionToolAttemptEntity attempt = attemptRepository
                        .findBySessionIdAndIdForUpdate(scope.sessionId(), execution.attemptId())
                        .orElseThrow(IllegalStateException::new);
                if (!"EXECUTING".equals(attempt.getState())
                        || !execution.stepId().equals(attempt.getStepId())
                        || execution.executionGeneration()
                                != attempt.getExecutionGeneration()
                        || !execution.claimRequestId().equals(attempt.getClaimRequestId())
                        || !scope.loopId().equals(attempt.getExecutionLoopId())
                        || !Objects.equals(scope.loopFence(), attempt.getExecutionFence())
                        || !scope.ownerInstanceId().equals(
                                attempt.getExecutionOwnerInstanceId())) {
                    throw new IllegalStateException();
                }
                attempt.setState(DurableToolAttemptState
                        .UNCERTAIN_PENDING_RESOLUTION.name());
                attemptRepository.save(attempt);
                RuntimeFailureState.apply(session, failure);
                session.setRuntimeStatus("error");
                session.setRuntimeStep("cancelled_tool_outcome_uncertain");
                session.setActiveLoopId(null);
                session.setLoopOwnerInstanceId(null);
                session.setLoopLeaseUntil(null);
                sessionRepository.save(session);
            });
        } catch (RuntimeException persistenceOrProtocolFailure) {
            throw new IllegalStateException("Durable cancelled execution parking failed");
        }
    }

    private void parkLocked(
            LoopDurabilityScope scope,
            DurableToolAttemptState expectedState,
            RuntimeFailureFact uncertaintyFailure) {
        Objects.requireNonNull(scope, "scope");
        if (expectedState != DurableToolAttemptState.WAITING_USER
                && expectedState != DurableToolAttemptState.UNCERTAIN_PENDING_RESOLUTION
                && expectedState != DurableToolAttemptState.RESOLVED_UNKNOWN) {
            throw new IllegalStateException();
        }
        SessionEntity session = sessionRepository.findByIdForUpdate(scope.sessionId())
                .orElseThrow(IllegalStateException::new);
        if (!Objects.equals(session.getUserId(), scope.userId())
                || session.getHistoryEpoch() != scope.historyEpoch()
                || session.isRestorePreparing()
                || !scope.loopId().equals(session.getActiveLoopId())
                || session.getLoopFence() != scope.loopFence()
                || !scope.ownerInstanceId().equals(session.getLoopOwnerInstanceId())) {
            throw new IllegalStateException();
        }
        List<SessionToolAttemptEntity> blocking = attemptRepository
                .findBySessionIdAndStateIn(scope.sessionId(), ALL_ATTEMPT_STATES)
                .stream()
                .filter(SessionLoopAdmissionService::isBlockingAttempt)
                .toList();
        if (blocking.size() != 1
                || !expectedState.name().equals(blocking.get(0).getState())) {
            throw new IllegalStateException();
        }
        if (expectedState == DurableToolAttemptState.WAITING_USER) {
            session.setRuntimeStatus("waiting_user");
            session.setRuntimeStep("waiting_control");
            RuntimeFailureState.clear(session);
        } else {
            if (uncertaintyFailure == null) throw new IllegalStateException();
            RuntimeFailureState.apply(session, uncertaintyFailure);
            session.setRuntimeStatus("error");
            session.setRuntimeStep(expectedState
                    == DurableToolAttemptState.UNCERTAIN_PENDING_RESOLUTION
                            ? "tool_outcome_uncertain"
                            : "resolved_tool_outcome_pending");
        }
        session.setActiveLoopId(null);
        session.setLoopOwnerInstanceId(null);
        session.setLoopLeaseUntil(null);
        sessionRepository.save(session);
    }

    private boolean failLocked(
            LoopDurabilityScope scope,
            RuntimeFailureFact failure) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(failure, "failure");
        SessionEntity session = sessionRepository.findByIdForUpdate(scope.sessionId())
                .orElseThrow(IllegalStateException::new);
        if (!Objects.equals(session.getUserId(), scope.userId())
                || session.getHistoryEpoch() != scope.historyEpoch()
                || session.isRestorePreparing()
                || !scope.loopId().equals(session.getActiveLoopId())
                || session.getLoopFence() != scope.loopFence()
                || !scope.ownerInstanceId().equals(session.getLoopOwnerInstanceId())) {
            throw new IllegalStateException();
        }
        if (hasBlockingAttempt(scope.sessionId())) return false;
        RuntimeFailureState.apply(session, failure);
        session.setCompletedAt(Instant.now());
        session.setActiveLoopId(null);
        session.setLoopOwnerInstanceId(null);
        session.setLoopLeaseUntil(null);
        sessionRepository.save(session);
        return true;
    }

    private LeaseAck renewLocked(LoopDurabilityScope scope) {
        Objects.requireNonNull(scope, "scope");
        SessionEntity session = sessionRepository.findByIdForUpdate(scope.sessionId())
                .orElseThrow(IllegalStateException::new);
        Instant databaseNow = sessionRepository.currentDatabaseTime();
        if (!Objects.equals(session.getUserId(), scope.userId())
                || session.getHistoryEpoch() != scope.historyEpoch()
                || session.isRestorePreparing()
                || !scope.loopId().equals(session.getActiveLoopId())
                || session.getLoopFence() != scope.loopFence()
                || !scope.ownerInstanceId().equals(session.getLoopOwnerInstanceId())) {
            throw new IllegalStateException();
        }
        Instant leaseUntil = databaseNow.plus(LOOP_LEASE);
        for (SessionToolAttemptEntity candidate : attemptRepository
                .findBySessionIdAndStateIn(scope.sessionId(), List.of("EXECUTING"))) {
            SessionToolAttemptEntity attempt = attemptRepository
                    .findBySessionIdAndIdForUpdate(scope.sessionId(), candidate.getId())
                    .orElseThrow(IllegalStateException::new);
            if (!scope.loopId().equals(attempt.getExecutionLoopId())
                    || !Objects.equals(scope.loopFence(), attempt.getExecutionFence())
                    || !scope.ownerInstanceId().equals(attempt.getExecutionOwnerInstanceId())
                    || attempt.getExecutionGeneration() <= 0L) {
                throw new IllegalStateException();
            }
            attempt.setExecutionLeaseUntil(leaseUntil);
            attemptRepository.save(attempt);
        }
        session.setLoopLeaseUntil(leaseUntil);
        sessionRepository.save(session);
        return new LeaseAck(scope, databaseNow, leaseUntil);
    }

    private void releaseLocked(LoopDurabilityScope scope) {
        Objects.requireNonNull(scope, "scope");
        SessionEntity session = sessionRepository.findByIdForUpdate(scope.sessionId())
                .orElseThrow(IllegalStateException::new);
        if (!Objects.equals(session.getUserId(), scope.userId())
                || session.getHistoryEpoch() != scope.historyEpoch()
                || !scope.loopId().equals(session.getActiveLoopId())
                || session.getLoopFence() != scope.loopFence()
                || !scope.ownerInstanceId().equals(session.getLoopOwnerInstanceId())) {
            throw new IllegalStateException();
        }
        rejectBlockingAttempt(scope.sessionId());
        session.setActiveLoopId(null);
        session.setLoopOwnerInstanceId(null);
        session.setLoopLeaseUntil(null);
        sessionRepository.save(session);
    }

    private AdmissionAck admitLocked(
            String sessionId,
            long userId,
            UUID admissionRequestId,
            String loopId,
            MessageSnapshot userMessage,
            String traceId) {
        requireSessionId(sessionId);
        if (userId < 0L) throw new IllegalStateException();
        Objects.requireNonNull(admissionRequestId, "admissionRequestId");
        requireCanonicalLoopId(loopId);
        Objects.requireNonNull(userMessage, "userMessage");
        Message materializedUser = userMessage.toMessage();
        validateOrdinaryUser(materializedUser);

        SessionEntity session = sessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(IllegalStateException::new);
        Instant databaseNow = sessionRepository.currentDatabaseTime();
        if (!Objects.equals(session.getUserId(), userId) || session.isRestorePreparing()) {
            throw new IllegalStateException();
        }
        if (!SessionHistoryRowAuthority.isVerified(session, messageRepository, objectMapper)) {
            throw new IllegalStateException("Authoritative row history is required");
        }
        String batchId = admissionRequestId.toString();
        PersistedMessageCodec.PersistedMessage expectedUser =
                new PersistedMessageCodec.PersistedMessage(
                        materializedUser,
                        "NORMAL",
                        "normal",
                        null,
                        null,
                        Collections.emptyMap(),
                        traceId);

        List<com.skillforge.server.entity.SessionMessageEntity> existing = messageRepository
                .findBySessionIdAndWriteBatchIdOrderByWriteBatchOrdinalAsc(sessionId, batchId);
        if (!existing.isEmpty()) {
            validateRetryScope(session, loopId, databaseNow);
            PersistedMessageOccurrence persisted = readUser(sessionId, batchId, expectedUser);
            return ack(session, persisted);
        }

        validateAvailableSession(session, databaseNow);
        rejectBlockingAttempt(sessionId);
        long nextFence = Math.addExact(session.getLoopFence(), 1L);
        Instant leaseUntil = databaseNow.plus(LOOP_LEASE);
        session.setActiveLoopId(loopId);
        session.setLoopFence(nextFence);
        session.setLoopOwnerInstanceId(ownerInstanceId);
        session.setLoopLeaseUntil(leaseUntil);
        session.setRuntimeStatus("running");
        session.setRuntimeStep("Starting");
        session.setLastUserMessageAt(databaseNow);
        RuntimeFailureState.clear(session);

        PersistedMessageOccurrence persisted = appendUser(sessionId, batchId, expectedUser);
        session.setMessageCount(Math.toIntExact(messageRepository.countBySessionId(sessionId)));
        sessionRepository.save(session);
        return ack(session, persisted);
    }

    private AdmissionAck ack(SessionEntity session, PersistedMessageOccurrence persisted) {
        LoopDurabilityScope scope = new LoopDurabilityScope(
                session.getId(),
                session.getUserId(),
                session.getHistoryEpoch(),
                session.getActiveLoopId(),
                session.getLoopFence(),
                session.getLoopOwnerInstanceId());
        return new AdmissionAck(
                scope,
                persisted,
                new DurableFrontier(persisted.messageId(), persisted.seqNo()),
                session.getLoopLeaseUntil());
    }

    private PersistedMessageOccurrence appendUser(
            String sessionId,
            String batchId,
            PersistedMessageCodec.PersistedMessage expectedUser) {
        try {
            return messageWriter.appendNewBatchLocked(
                    sessionId, batchId, List.of(expectedUser)).get(0);
        } catch (DurableMessageBatchIntegrityException integrityFailure) {
            throw new IllegalStateException();
        }
    }

    private PersistedMessageOccurrence readUser(
            String sessionId,
            String batchId,
            PersistedMessageCodec.PersistedMessage expectedUser) {
        try {
            return messageWriter.readExactBatch(
                    sessionId, batchId, List.of(expectedUser)).get(0);
        } catch (DurableMessageBatchIntegrityException integrityFailure) {
            throw new IllegalStateException();
        }
    }

    private static void validateAvailableSession(SessionEntity session, Instant databaseNow) {
        boolean hasLoopId = session.getActiveLoopId() != null;
        boolean hasOwner = session.getLoopOwnerInstanceId() != null;
        boolean hasLease = session.getLoopLeaseUntil() != null;
        if (hasLoopId != hasOwner || hasLoopId != hasLease) {
            throw new IllegalStateException();
        }
        if (hasLoopId && session.getLoopLeaseUntil().isAfter(databaseNow)) {
            throw new IllegalStateException();
        }
    }

    private void validateRetryScope(
            SessionEntity session,
            String loopId,
            Instant databaseNow) {
        if (!loopId.equals(session.getActiveLoopId())
                || !ownerInstanceId.equals(session.getLoopOwnerInstanceId())
                || session.getLoopLeaseUntil() == null
                || !session.getLoopLeaseUntil().isAfter(databaseNow)) {
            throw new IllegalStateException();
        }
    }

    private void rejectBlockingAttempt(String sessionId) {
        if (hasBlockingAttempt(sessionId)) throw new IllegalStateException();
    }

    private boolean hasBlockingAttempt(String sessionId) {
        for (SessionToolAttemptEntity attempt : attemptRepository
                .findBySessionIdAndStateIn(sessionId, ALL_ATTEMPT_STATES)) {
            if (OPEN_ATTEMPT_STATES.contains(attempt.getState())
                    || (TERMINAL_ATTEMPT_STATES.contains(attempt.getState())
                    && !ARCHIVE_READY_STATES.contains(attempt.getArchivePreparationState()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBlockingAttempt(SessionToolAttemptEntity attempt) {
        return OPEN_ATTEMPT_STATES.contains(attempt.getState())
                || (TERMINAL_ATTEMPT_STATES.contains(attempt.getState())
                && !ARCHIVE_READY_STATES.contains(attempt.getArchivePreparationState()));
    }

    private static void validateOrdinaryUser(Message message) {
        if (message.getRole() != Message.Role.USER || message.getContent() == null) {
            throw new IllegalStateException();
        }
        if (message.getContent() instanceof List<?> blocks) {
            for (Object value : blocks) {
                if (value instanceof Map<?, ?> block) {
                    Object type = block.get("type");
                    if ("tool_use".equals(type) || "tool_result".equals(type)) {
                        throw new IllegalStateException();
                    }
                }
            }
        }
    }

    private static void requireSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalStateException();
    }

    private static void requireCanonicalLoopId(String loopId) {
        try {
            UUID parsed = UUID.fromString(loopId);
            if (!parsed.toString().equals(loopId)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new IllegalStateException();
        }
    }

    public record AdmissionAck(
            LoopDurabilityScope scope,
            PersistedMessageOccurrence userMessage,
            DurableFrontier frontier,
            Instant leaseUntil) {

        public AdmissionAck {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(userMessage, "userMessage");
            Objects.requireNonNull(frontier, "frontier");
            Objects.requireNonNull(leaseUntil, "leaseUntil");
            if (frontier.maxMessageId() != userMessage.messageId()
                    || frontier.maxSeq() != userMessage.seqNo()) {
                throw new IllegalArgumentException("frontier must identify the USER occurrence");
            }
        }
    }

    public record LeaseAck(
            LoopDurabilityScope scope,
            Instant databaseTime,
            Instant leaseUntil) {

        public LeaseAck {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(databaseTime, "databaseTime");
            Objects.requireNonNull(leaseUntil, "leaseUntil");
            if (!leaseUntil.isAfter(databaseTime)) {
                throw new IllegalArgumentException("leaseUntil must be after databaseTime");
            }
        }
    }

    public record RecoveryAdmissionAck(
            LoopDurabilityScope scope,
            Long attemptId,
            UUID stepId,
            DurableToolAttemptState state,
            Long executionGeneration,
            boolean attemptAlreadyClaimedByScope,
            Instant leaseUntil) {

        public RecoveryAdmissionAck {
            Objects.requireNonNull(scope, "scope");
            boolean hasAttempt = attemptId != null || stepId != null
                    || state != null || executionGeneration != null;
            boolean completeAttempt = attemptId != null && stepId != null
                    && state != null && executionGeneration != null;
            if (hasAttempt != completeAttempt) {
                throw new IllegalArgumentException("recovery attempt identity must be complete");
            }
            if (attemptId != null && attemptId <= 0L) {
                throw new IllegalArgumentException("attemptId must be positive");
            }
            if (executionGeneration != null && executionGeneration < 0L) {
                throw new IllegalArgumentException("executionGeneration must be nonnegative");
            }
            if (attemptAlreadyClaimedByScope
                    && (!completeAttempt || executionGeneration <= 0L)) {
                throw new IllegalArgumentException(
                        "claimed recovery scope requires an attempt identity");
            }
            Objects.requireNonNull(leaseUntil, "leaseUntil");
        }
    }

    /** Immutable handoff from Session admission to the interactive answer CAS. */
    public record ManualContinuationClaimAck(
            LoopDurabilityScope scope,
            long attemptId,
            UUID stepId,
            Instant leaseUntil) {

        public ManualContinuationClaimAck {
            Objects.requireNonNull(scope, "scope");
            if (attemptId <= 0L) throw new IllegalArgumentException("attemptId must be positive");
            Objects.requireNonNull(stepId, "stepId");
            Objects.requireNonNull(leaseUntil, "leaseUntil");
        }
    }
}
