package com.skillforge.server.session;

import com.skillforge.core.engine.durability.DurableFrontier;
import com.skillforge.core.engine.durability.LoopDurabilityScope;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionMessageEntity;
import com.skillforge.server.entity.SessionToolAttemptEntity;
import com.skillforge.server.repository.SessionMessageRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SessionToolAttemptRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Short read transactions for locating and reconstructing durable unknown post actions. */
@Service
public class UnknownOutcomePostActionTransactionService {

    private static final List<String> RECOVERABLE_STATES = List.of("PENDING", "CLAIMED");
    private static final String CONTINUE = "CONTINUE_CURRENT_TIMELINE";
    private static final int MAX_SCAN = 100;

    private final SessionRepository sessionRepository;
    private final SessionToolAttemptRepository attemptRepository;
    private final SessionMessageRepository messageRepository;

    public UnknownOutcomePostActionTransactionService(
            SessionRepository sessionRepository,
            SessionToolAttemptRepository attemptRepository,
            SessionMessageRepository messageRepository) {
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository");
        this.attemptRepository = Objects.requireNonNull(attemptRepository, "attemptRepository");
        this.messageRepository = Objects.requireNonNull(messageRepository, "messageRepository");
    }

    @Transactional(readOnly = true)
    public List<PostActionLocator> findRecoverable() {
        return attemptRepository.findByPostActionStateInOrderByIdAsc(
                        RECOVERABLE_STATES, PageRequest.of(0, MAX_SCAN))
                .stream()
                .map(attempt -> new PostActionLocator(
                        attempt.getSessionId(), Objects.requireNonNull(attempt.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<PostActionCandidate> findRecoverable(String sessionId, long attemptId) {
        if (sessionId == null || sessionId.isBlank() || attemptId <= 0L) {
            return Optional.empty();
        }
        return attemptRepository.findBySessionIdAndId(sessionId, attemptId)
                .filter(attempt -> RECOVERABLE_STATES.contains(attempt.getPostActionState()))
                .map(attempt -> candidate(sessionId, attempt));
    }

    @Transactional(readOnly = true)
    public ResolvedUnknownContinuationRun loadClaimedRun(
            SessionRunCoordinator.ClaimIdentity claim) {
        Objects.requireNonNull(claim, "claim");
        SessionEntity session = sessionRepository.findById(claim.sessionId())
                .orElseThrow(UnknownOutcomePostActionTransactionService::unavailable);
        SessionToolAttemptEntity attempt = attemptRepository
                .findBySessionIdAndId(claim.sessionId(), claim.attemptId())
                .orElseThrow(UnknownOutcomePostActionTransactionService::unavailable);
        Instant databaseNow = sessionRepository.currentDatabaseTime();
        validateCommon(session, attempt);
        if (!"CLAIMED".equals(attempt.getPostActionState())
                || !claim.equals(claimIdentity(attempt))
                || !claim.loopId().equals(session.getActiveLoopId())
                || session.getLoopFence() != claim.loopFence()
                || session.getLoopOwnerInstanceId() == null
                || session.getLoopLeaseUntil() == null
                || !session.getLoopLeaseUntil().isAfter(databaseNow)) {
            throw unavailable();
        }
        SessionMessageEntity tail = messageRepository
                .findTopBySessionIdOrderBySeqNoDesc(claim.sessionId())
                .orElseThrow(UnknownOutcomePostActionTransactionService::unavailable);
        if (tail.getId() == null) throw unavailable();
        LoopDurabilityScope scope = new LoopDurabilityScope(
                session.getId(), session.getUserId(), session.getHistoryEpoch(),
                claim.loopId(), claim.loopFence(), session.getLoopOwnerInstanceId());
        return new ResolvedUnknownContinuationRun(
                claim, scope, attempt.getId(), attempt.getStepId(),
                attempt.getExecutionGeneration(), session.getLoopLeaseUntil(),
                new DurableFrontier(tail.getId(), tail.getSeqNo()));
    }

    private PostActionCandidate candidate(
            String sessionId, SessionToolAttemptEntity attempt) {
        SessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(UnknownOutcomePostActionTransactionService::unavailable);
        validateCommon(session, attempt);
        if ("PENDING".equals(attempt.getPostActionState())) {
            if (session.getActiveLoopId() != null
                    || session.getLoopOwnerInstanceId() != null
                    || session.getLoopLeaseUntil() != null) {
                throw unavailable();
            }
            long nextFence;
            try {
                nextFence = Math.addExact(session.getLoopFence(), 1L);
            } catch (ArithmeticException exhaustedFence) {
                throw unavailable();
            }
            return PostActionCandidate.pending(
                    sessionId, attempt.getId(), session.getUserId(), session.getHistoryEpoch(),
                    attempt.getStepId(), attempt.getExecutionGeneration(),
                    attempt.getPostActionResolutionRequestId(),
                    attempt.getPostActionResultBatchId(), nextFence);
        }
        if (!"CLAIMED".equals(attempt.getPostActionState())
                || session.getActiveLoopId() == null
                || session.getLoopOwnerInstanceId() == null
                || session.getLoopLeaseUntil() == null) {
            throw unavailable();
        }
        SessionRunCoordinator.ClaimIdentity identity = claimIdentity(attempt);
        if (!identity.loopId().equals(session.getActiveLoopId())
                || identity.loopFence() != session.getLoopFence()) {
            throw unavailable();
        }
        return PostActionCandidate.claimed(
                sessionId, attempt.getId(), session.getUserId(), session.getHistoryEpoch(),
                attempt.getStepId(), attempt.getExecutionGeneration(), identity);
    }

    private static void validateCommon(
            SessionEntity session, SessionToolAttemptEntity attempt) {
        if (attempt.getId() == null
                || !session.getId().equals(attempt.getSessionId())
                || session.getUserId() == null
                || session.getHistoryEpoch() != attempt.getHistoryEpoch()
                || session.isRestorePreparing()
                || !"RESOLVED_UNKNOWN".equals(attempt.getState())
                || attempt.getStepId() == null
                || attempt.getExecutionGeneration() <= 0L
                || attempt.getResultBatchId() == null
                || !Objects.equals(attempt.getResultBatchId(),
                        attempt.getPostActionResultBatchId())
                || attempt.getPostActionResolutionRequestId() == null
                || !CONTINUE.equals(attempt.getPostActionKind())) {
            throw unavailable();
        }
    }

    private static SessionRunCoordinator.ClaimIdentity claimIdentity(
            SessionToolAttemptEntity attempt) {
        try {
            return new SessionRunCoordinator.ClaimIdentity(
                    attempt.getSessionId(), attempt.getId(),
                    attempt.getPostActionResolutionRequestId(),
                    attempt.getPostActionResultBatchId(),
                    SessionRunCoordinator.ContinuationAction.CONTINUE_CURRENT_TIMELINE,
                    attempt.getPostActionClaimRequestId(), attempt.getPostActionLoopId(),
                    attempt.getPostActionFence());
        } catch (RuntimeException invalid) {
            throw unavailable();
        }
    }

    private static UUID stableId(String purpose, UUID resolutionId, UUID resultBatchId) {
        String value = "skillforge:resolved-unknown:" + purpose + ":"
                + resolutionId + ":" + resultBatchId;
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("Resolved unknown continuation is not available");
    }

    public record PostActionLocator(String sessionId, long attemptId) {
        public PostActionLocator {
            if (sessionId == null || sessionId.isBlank() || attemptId <= 0L) {
                throw new IllegalArgumentException("invalid post-action locator");
            }
        }
    }

    public record PostActionCandidate(
            String sessionId,
            long attemptId,
            long userId,
            long historyEpoch,
            UUID stepId,
            long executionGeneration,
            String state,
            UUID resolutionRequestId,
            UUID resultBatchId,
            long requestedFence,
            SessionRunCoordinator.ClaimIdentity claimedIdentity) {

        public PostActionCandidate {
            if (sessionId == null || sessionId.isBlank() || attemptId <= 0L
                    || userId < 0L || historyEpoch < 0L || executionGeneration <= 0L
                    || requestedFence < 0L) {
                throw new IllegalArgumentException("invalid post-action candidate");
            }
            Objects.requireNonNull(stepId, "stepId");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(resolutionRequestId, "resolutionRequestId");
            Objects.requireNonNull(resultBatchId, "resultBatchId");
            if (("PENDING".equals(state)) == (claimedIdentity != null)) {
                throw new IllegalArgumentException("candidate claim shape is invalid");
            }
        }

        public static PostActionCandidate pending(
                String sessionId, long attemptId, long userId, long historyEpoch,
                UUID stepId, long executionGeneration, UUID resolutionRequestId,
                UUID resultBatchId, long requestedFence) {
            return new PostActionCandidate(
                    sessionId, attemptId, userId, historyEpoch, stepId, executionGeneration,
                    "PENDING", resolutionRequestId, resultBatchId, requestedFence, null);
        }

        public static PostActionCandidate claimed(
                String sessionId, long attemptId, long userId, long historyEpoch,
                UUID stepId, long executionGeneration,
                SessionRunCoordinator.ClaimIdentity identity) {
            Objects.requireNonNull(identity, "identity");
            return new PostActionCandidate(
                    sessionId, attemptId, userId, historyEpoch, stepId, executionGeneration,
                    "CLAIMED", identity.resolutionRequestId(), identity.resultBatchId(),
                    identity.loopFence(), identity);
        }

        public SessionRunCoordinator.ClaimCommand claimCommand() {
            if (!"PENDING".equals(state)) {
                throw new IllegalStateException("Only a pending candidate creates a claim");
            }
            return new SessionRunCoordinator.ClaimCommand(
                    sessionId, attemptId, resolutionRequestId, resultBatchId,
                    SessionRunCoordinator.ContinuationAction.CONTINUE_CURRENT_TIMELINE,
                    stableId("claim", resolutionRequestId, resultBatchId),
                    stableId("loop", resolutionRequestId, resultBatchId).toString(),
                    requestedFence);
        }
    }
}
