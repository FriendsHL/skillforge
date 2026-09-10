package com.skillforge.server.session;

import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionToolAttemptEntity;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SessionMessageRepository;
import com.skillforge.server.repository.SessionToolAttemptRepository;
import com.skillforge.server.runtime.RuntimeFailureState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** PostgreSQL-backed owner of the one durable continuation following unknown-outcome resolution. */
@Service
public class JpaSessionRunCoordinator implements SessionRunCoordinator {

    private static final String RESOLVED_UNKNOWN = "RESOLVED_UNKNOWN";
    private static final String CONTINUE = "CONTINUE_CURRENT_TIMELINE";
    private static final String PENDING = "PENDING";
    private static final String CLAIMED = "CLAIMED";
    private static final String COMPLETED = "COMPLETED";
    private static final String SAFE_FAILURE = "Durable continuation is not available";
    private static final int SESSION_ID_MAX_LENGTH = 36;
    private static final int LOOP_ID_MAX_LENGTH = 36;
    private static final int OWNER_ID_MAX_LENGTH = 128;

    private final SessionRepository sessionRepository;
    private final SessionMessageRepository messageRepository;
    private final SessionToolAttemptRepository attemptRepository;
    private final TransactionTemplate transactionTemplate;
    private final String ownerInstanceId;
    private final Duration loopLease;

    @Autowired
    public JpaSessionRunCoordinator(
            SessionRepository sessionRepository,
            SessionMessageRepository messageRepository,
            SessionToolAttemptRepository attemptRepository,
            PlatformTransactionManager transactionManager) {
        this(sessionRepository, messageRepository, attemptRepository, transactionManager,
                UUID.randomUUID().toString(), Duration.ofMinutes(2));
    }

    JpaSessionRunCoordinator(
            SessionRepository sessionRepository,
            SessionMessageRepository messageRepository,
            SessionToolAttemptRepository attemptRepository,
            PlatformTransactionManager transactionManager,
            String ownerInstanceId,
            Duration loopLease) {
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository");
        this.messageRepository = Objects.requireNonNull(messageRepository, "messageRepository");
        this.attemptRepository = Objects.requireNonNull(attemptRepository, "attemptRepository");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.ownerInstanceId = requireBoundedText(
                ownerInstanceId, OWNER_ID_MAX_LENGTH, "ownerInstanceId");
        this.loopLease = Objects.requireNonNull(loopLease, "loopLease");
        if (loopLease.isZero() || loopLease.isNegative()) {
            throw new IllegalArgumentException("loopLease must be positive");
        }
    }

    @Override
    public ClaimResult claim(ClaimCommand command) {
        Objects.requireNonNull(command, "command");
        requireClaimStrings(command.sessionId(), command.loopId());
        return inTransaction(() -> claimLocked(command));
    }

    @Override
    public HandoffResult acceptInboxDrain(ClaimIdentity claim) {
        Objects.requireNonNull(claim, "claim");
        requireClaimStrings(claim.sessionId(), claim.loopId());
        return inTransaction(() -> acceptInboxDrainLocked(claim));
    }

    @Override
    public HandoffResult acceptTranscriptContinuation(ClaimIdentity claim) {
        Objects.requireNonNull(claim, "claim");
        requireClaimStrings(claim.sessionId(), claim.loopId());
        return inTransaction(() -> acceptTranscriptLocked(claim));
    }

    @Override
    public RecoveryResult authorizeCrashRecovery(ClaimIdentity claim) {
        Objects.requireNonNull(claim, "claim");
        requireClaimStrings(claim.sessionId(), claim.loopId());
        return inTransaction(() -> authorizeCrashRecoveryLocked(claim));
    }

    String ownerInstanceIdForTest() {
        return ownerInstanceId;
    }

    private ClaimResult claimLocked(ClaimCommand command) {
        LockedRows rows = lockRows(command.sessionId(), command.attemptId());
        validateBoundary(rows.session(), rows.attempt());
        if (!matchesBoundary(command, rows.attempt())) throw unavailable();

        String postActionState = rows.attempt().getPostActionState();
        if (CLAIMED.equals(postActionState) || COMPLETED.equals(postActionState)) {
            ClaimAck winner = winnerAck(rows.attempt());
            ClaimDisposition disposition = winner.winner().equals(ClaimIdentity.from(command))
                    ? ClaimDisposition.REPLAYED
                    : ClaimDisposition.REJECTED;
            return new ClaimResult(disposition, winner);
        }
        if (!PENDING.equals(postActionState)
                || rows.attempt().isPostActionInboxHandoffAccepted()) {
            throw unavailable();
        }
        validateUnclaimedSession(rows.session(), command.loopFence());

        Instant databaseNow = sessionRepository.currentDatabaseTime();
        rows.session().setActiveLoopId(command.loopId());
        rows.session().setLoopFence(command.loopFence());
        rows.session().setLoopOwnerInstanceId(ownerInstanceId);
        rows.session().setLoopLeaseUntil(databaseNow.plus(loopLease));
        rows.session().setRuntimeStatus("running");
        rows.session().setRuntimeStep("Continuing resolved unknown outcome");
        RuntimeFailureState.clear(rows.session());

        rows.attempt().setPostActionState(CLAIMED);
        rows.attempt().setPostActionClaimRequestId(command.claimRequestId());
        rows.attempt().setPostActionLoopId(command.loopId());
        rows.attempt().setPostActionFence(command.loopFence());
        SessionToolAttemptEntity savedAttempt = attemptRepository.saveAndFlush(rows.attempt());
        sessionRepository.saveAndFlush(rows.session());
        return new ClaimResult(ClaimDisposition.ACCEPTED, winnerAck(savedAttempt));
    }

    private HandoffResult acceptInboxDrainLocked(ClaimIdentity claim) {
        LockedRows rows = lockRows(claim.sessionId(), claim.attemptId());
        validateBoundary(rows.session(), rows.attempt());
        if (!isWinner(claim, rows.attempt())) {
            return handoff(HandoffDisposition.HANDOFF_REJECTED);
        }
        validateClaimedProgress(rows.attempt());
        if (!hasLiveAuthority(rows.session(), claim, sessionRepository.currentDatabaseTime())) {
            return handoff(HandoffDisposition.HANDOFF_REJECTED);
        }
        if (rows.attempt().isPostActionInboxHandoffAccepted()) {
            return handoff(HandoffDisposition.HANDOFF_REPLAYED);
        }
        if (!("PREPARED".equals(rows.attempt().getArchivePreparationState())
                || "RAW_FALLBACK".equals(rows.attempt().getArchivePreparationState()))) {
            return handoff(HandoffDisposition.HANDOFF_REJECTED);
        }
        rows.attempt().setPostActionInboxHandoffAccepted(true);
        attemptRepository.saveAndFlush(rows.attempt());
        return handoff(HandoffDisposition.HANDOFF_ACCEPTED);
    }

    private HandoffResult acceptTranscriptLocked(ClaimIdentity claim) {
        LockedRows rows = lockRows(claim.sessionId(), claim.attemptId());
        validateBoundary(rows.session(), rows.attempt());
        if (!isWinner(claim, rows.attempt())) {
            return handoff(HandoffDisposition.HANDOFF_REJECTED);
        }
        validateClaimedProgress(rows.attempt());
        if (COMPLETED.equals(rows.attempt().getPostActionState())) {
            return handoff(HandoffDisposition.HANDOFF_REPLAYED);
        }
        if (!rows.attempt().isPostActionInboxHandoffAccepted()
                || !CLAIMED.equals(rows.attempt().getPostActionState())
                || !("PREPARED".equals(rows.attempt().getArchivePreparationState())
                        || "RAW_FALLBACK".equals(
                                rows.attempt().getArchivePreparationState()))
                || !sameOrReleasedWinningFence(rows.session(), claim)
                || !hasPersistedTranscriptEvidence(rows.attempt())) {
            return handoff(HandoffDisposition.HANDOFF_REJECTED);
        }
        rows.attempt().setPostActionState(COMPLETED);
        attemptRepository.saveAndFlush(rows.attempt());
        return handoff(HandoffDisposition.HANDOFF_ACCEPTED);
    }

    private boolean hasPersistedTranscriptEvidence(SessionToolAttemptEntity attempt) {
        var results = messageRepository
                .findBySessionIdAndWriteBatchIdOrderByWriteBatchOrdinalAsc(
                        attempt.getSessionId(), attempt.getResultBatchId().toString());
        if (results.size() != attempt.getArchiveTotalCount() || results.isEmpty()) {
            return false;
        }
        long resultTailSeq = results.get(results.size() - 1).getSeqNo();
        return messageRepository.existsBySessionIdAndRoleAndSeqNoGreaterThan(
                attempt.getSessionId(), "assistant", resultTailSeq);
    }

    private static boolean sameOrReleasedWinningFence(
            SessionEntity session, ClaimIdentity claim) {
        if (session.getLoopFence() != claim.loopFence()) return false;
        if (session.getActiveLoopId() == null) {
            return session.getLoopOwnerInstanceId() == null
                    && session.getLoopLeaseUntil() == null;
        }
        return claim.loopId().equals(session.getActiveLoopId())
                && session.getLoopOwnerInstanceId() != null
                && session.getLoopLeaseUntil() != null;
    }

    private RecoveryResult authorizeCrashRecoveryLocked(ClaimIdentity claim) {
        LockedRows rows = lockRows(claim.sessionId(), claim.attemptId());
        validateBoundary(rows.session(), rows.attempt());
        if (!isWinner(claim, rows.attempt())
                || !CLAIMED.equals(rows.attempt().getPostActionState())) {
            return recovery(RecoveryDisposition.RECOVERY_REJECTED);
        }
        Instant databaseNow = sessionRepository.currentDatabaseTime();
        if (!matchesSessionRun(rows.session(), claim)
                || rows.session().getLoopLeaseUntil() == null) {
            return recovery(RecoveryDisposition.RECOVERY_REJECTED);
        }
        boolean alreadyOwned = ownerInstanceId.equals(rows.session().getLoopOwnerInstanceId());
        boolean expired = !rows.session().getLoopLeaseUntil().isAfter(databaseNow);
        if (!alreadyOwned && !expired) {
            return recovery(RecoveryDisposition.RECOVERY_REJECTED);
        }
        rows.session().setLoopOwnerInstanceId(ownerInstanceId);
        rows.session().setLoopLeaseUntil(databaseNow.plus(loopLease));
        sessionRepository.saveAndFlush(rows.session());
        return recovery(RecoveryDisposition.RECOVERY_AUTHORIZED);
    }

    private LockedRows lockRows(String sessionId, long attemptId) {
        SessionEntity session = sessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(JpaSessionRunCoordinator::unavailable);
        SessionToolAttemptEntity attempt = attemptRepository
                .findBySessionIdAndIdForUpdate(sessionId, attemptId)
                .orElseThrow(JpaSessionRunCoordinator::unavailable);
        return new LockedRows(session, attempt);
    }

    private static void validateBoundary(
            SessionEntity session, SessionToolAttemptEntity attempt) {
        if (session.isRestorePreparing()
                || session.getHistoryEpoch() != attempt.getHistoryEpoch()
                || !RESOLVED_UNKNOWN.equals(attempt.getState())
                || !Objects.equals(attempt.getResultBatchId(),
                        attempt.getPostActionResultBatchId())
                || attempt.getPostActionResolutionRequestId() == null
                || !CONTINUE.equals(attempt.getPostActionKind())) {
            throw unavailable();
        }
        switch (attempt.getPostActionState()) {
            case PENDING -> {
                if (attempt.getPostActionClaimRequestId() != null
                        || attempt.getPostActionLoopId() != null
                        || attempt.getPostActionFence() != null
                        || attempt.isPostActionInboxHandoffAccepted()) {
                    throw unavailable();
                }
            }
            case CLAIMED -> {
                requireWinnerTuple(attempt);
            }
            case COMPLETED -> {
                requireWinnerTuple(attempt);
                if (!attempt.isPostActionInboxHandoffAccepted()) {
                    throw unavailable();
                }
            }
            default -> throw unavailable();
        }
    }

    private static void validateUnclaimedSession(SessionEntity session, long requestedFence) {
        boolean hasLoop = session.getActiveLoopId() != null;
        boolean hasOwner = session.getLoopOwnerInstanceId() != null;
        boolean hasLease = session.getLoopLeaseUntil() != null;
        long requiredFence;
        try {
            requiredFence = Math.addExact(session.getLoopFence(), 1L);
        } catch (ArithmeticException exhaustedFence) {
            throw unavailable();
        }
        if (hasLoop || hasOwner || hasLease || requestedFence != requiredFence) {
            throw unavailable();
        }
    }

    private static void validateClaimedProgress(SessionToolAttemptEntity attempt) {
        String state = attempt.getPostActionState();
        if (!(CLAIMED.equals(state) || COMPLETED.equals(state))) throw unavailable();
    }

    private static boolean matchesBoundary(
            ClaimCommand command, SessionToolAttemptEntity attempt) {
        return Objects.equals(command.resolutionRequestId(),
                        attempt.getPostActionResolutionRequestId())
                && Objects.equals(command.resultBatchId(),
                        attempt.getPostActionResultBatchId())
                && command.action().name().equals(attempt.getPostActionKind());
    }

    private static boolean isWinner(
            ClaimIdentity claim, SessionToolAttemptEntity attempt) {
        return winnerAck(attempt).winner().equals(claim);
    }

    private static ClaimAck winnerAck(SessionToolAttemptEntity attempt) {
        requireWinnerTuple(attempt);
        ContinuationAction action;
        try {
            action = ContinuationAction.valueOf(attempt.getPostActionKind());
        } catch (IllegalArgumentException invalidAction) {
            throw unavailable();
        }
        return new ClaimAck(new ClaimIdentity(
                attempt.getSessionId(), attempt.getId(),
                attempt.getPostActionResolutionRequestId(),
                attempt.getPostActionResultBatchId(), action,
                attempt.getPostActionClaimRequestId(), attempt.getPostActionLoopId(),
                attempt.getPostActionFence()));
    }

    private static void requireWinnerTuple(SessionToolAttemptEntity attempt) {
        if (attempt.getId() == null
                || attempt.getPostActionClaimRequestId() == null
                || attempt.getPostActionLoopId() == null
                || attempt.getPostActionLoopId().isBlank()
                || attempt.getPostActionFence() == null
                || attempt.getPostActionFence() < 0L) {
            throw unavailable();
        }
    }

    private boolean hasLiveAuthority(
            SessionEntity session, ClaimIdentity claim, Instant databaseNow) {
        return matchesSessionRun(session, claim)
                && ownerInstanceId.equals(session.getLoopOwnerInstanceId())
                && session.getLoopLeaseUntil() != null
                && session.getLoopLeaseUntil().isAfter(databaseNow);
    }

    private static boolean matchesSessionRun(SessionEntity session, ClaimIdentity claim) {
        return claim.loopId().equals(session.getActiveLoopId())
                && session.getLoopFence() == claim.loopFence();
    }

    private <T> T inTransaction(java.util.function.Supplier<T> action) {
        try {
            T result = transactionTemplate.execute(ignored -> action.get());
            return Objects.requireNonNull(result, "coordination result");
        } catch (CoordinationUnavailable safeFailure) {
            throw safeFailure;
        } catch (RuntimeException persistenceOrProtocolFailure) {
            throw unavailable();
        }
    }

    private static HandoffResult handoff(HandoffDisposition disposition) {
        return new HandoffResult(disposition);
    }

    private static RecoveryResult recovery(RecoveryDisposition disposition) {
        return new RecoveryResult(disposition);
    }

    private static void requireClaimStrings(String sessionId, String loopId) {
        try {
            requireBoundedText(sessionId, SESSION_ID_MAX_LENGTH, "sessionId");
            requireBoundedText(loopId, LOOP_ID_MAX_LENGTH, "loopId");
        } catch (IllegalArgumentException invalidIdentity) {
            throw unavailable();
        }
    }

    private static String requireBoundedText(String value, int maxLength, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " has invalid length");
        }
        return value;
    }

    private static CoordinationUnavailable unavailable() {
        return new CoordinationUnavailable();
    }

    private record LockedRows(SessionEntity session, SessionToolAttemptEntity attempt) {
    }

    private static final class CoordinationUnavailable extends IllegalStateException {
        private CoordinationUnavailable() {
            super(SAFE_FAILURE);
        }
    }
}
