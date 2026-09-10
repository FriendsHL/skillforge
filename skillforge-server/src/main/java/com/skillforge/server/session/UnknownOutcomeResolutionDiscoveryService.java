package com.skillforge.server.session;

import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionMessageInboxEntity;
import com.skillforge.server.entity.SessionToolAttemptEntity;
import com.skillforge.server.repository.SessionMessageInboxRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SessionToolAttemptRepository;
import com.skillforge.server.session.UnknownOutcomeResolutionAck.ActorAuthority;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Read-only discovery of the one exact unknown outcome currently blocking a Session. */
@Service
public class UnknownOutcomeResolutionDiscoveryService {

    private static final String UNCERTAIN = "UNCERTAIN_PENDING_RESOLUTION";

    private final SessionRepository sessionRepository;
    private final SessionToolAttemptRepository attemptRepository;
    private final SessionMessageInboxRepository inboxRepository;
    private final UnknownOutcomeAttemptVerifier attemptVerifier;

    public UnknownOutcomeResolutionDiscoveryService(
            SessionRepository sessionRepository,
            SessionToolAttemptRepository attemptRepository,
            SessionMessageInboxRepository inboxRepository,
            UnknownOutcomeAttemptVerifier attemptVerifier) {
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository");
        this.attemptRepository = Objects.requireNonNull(attemptRepository, "attemptRepository");
        this.inboxRepository = Objects.requireNonNull(inboxRepository, "inboxRepository");
        this.attemptVerifier = Objects.requireNonNull(attemptVerifier, "attemptVerifier");
    }

    @Transactional(readOnly = true)
    public Optional<UnknownOutcomeResolutionDiscovery> find(
            String sessionId, UnknownOutcomeResolutionActor actor) {
        if (sessionId == null || sessionId.isBlank()) throw unavailable();
        Objects.requireNonNull(actor, "actor");
        SessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(UnknownOutcomeResolutionDiscoveryService::unavailable);
        ActorAuthority actorAuthority = UnknownOutcomeAuthorization.authorize(session, actor);

        List<SessionToolAttemptEntity> attempts = attemptRepository
                .findBySessionIdAndStateOrderByIdAsc(
                        sessionId, UNCERTAIN, PageRequest.of(0, 2));
        if (attempts.isEmpty()) return Optional.empty();
        if (attempts.size() != 1) throw unavailable();

        SessionToolAttemptEntity attempt = attempts.get(0);
        validateIdentity(session, attempt);
        List<UnknownOutcomeAttemptVerifier.ManifestCall> manifest =
                attemptVerifier.verify(attempt);

        List<SessionMessageInboxEntity> inbox = inboxRepository.findBySessionIdOrderByIdAsc(
                sessionId,
                PageRequest.of(0, UnknownOutcomeResolutionRequest.MAX_INBOX_DISPOSITIONS + 1));
        if (inbox.size() > UnknownOutcomeResolutionRequest.MAX_INBOX_DISPOSITIONS) {
            throw unavailable();
        }
        List<UUID> inboxIds = inbox.stream()
                .map(row -> validatedInboxId(session, row))
                .toList();
        return Optional.of(new UnknownOutcomeResolutionDiscovery(
                sessionId,
                attempt.getId(),
                attempt.getHistoryEpoch(),
                attempt.getExecutionGeneration(),
                attempt.getExecutionFence(),
                attempt.getState(),
                actorAuthority,
                manifest.stream()
                        .map(UnknownOutcomeResolutionDiscoveryService::displayCall)
                        .toList(),
                inboxIds));
    }

    private static UnknownOutcomeResolutionDiscovery.ToolCall displayCall(
            UnknownOutcomeAttemptVerifier.ManifestCall call) {
        return new UnknownOutcomeResolutionDiscovery.ToolCall(
                call.providerOrdinal(), call.toolUseId(), call.toolName(),
                call.input().toString());
    }

    private static void validateIdentity(
            SessionEntity session, SessionToolAttemptEntity attempt) {
        if (session.isRestorePreparing()
                || attempt.getId() == null || attempt.getId() <= 0L
                || !session.getId().equals(attempt.getSessionId())
                || !UNCERTAIN.equals(attempt.getState())
                || session.getHistoryEpoch() != attempt.getHistoryEpoch()
                || attempt.getExecutionGeneration() <= 0L
                || attempt.getExecutionFence() == null || attempt.getExecutionFence() < 0L
                || attempt.getExecutionLoopId() == null
                || attempt.getExecutionOwnerInstanceId() == null
                || attempt.getClaimRequestId() == null
                || attempt.getClaimedAt() == null
                || attempt.getExecutionLeaseUntil() == null
                || !hasValidSessionClaim(session, attempt)
                || attempt.getResultBatchId() != null
                || attempt.getResultExecutionGeneration() != null
                || attempt.getResultExecutionFence() != null
                || !"NOT_STARTED".equals(attempt.getArchivePreparationState())
                || attempt.getArchivePreparedCount() != 0
                || attempt.getArchiveTotalCount() != 0
                || !"NONE".equals(attempt.getPostActionState())
                || attempt.getPostActionResolutionRequestId() != null
                || attempt.getPostActionResultBatchId() != null
                || attempt.getPostActionKind() != null
                || attempt.getPostActionClaimRequestId() != null
                || attempt.getPostActionLoopId() != null
                || attempt.getPostActionFence() != null
                || attempt.isPostActionInboxHandoffAccepted()) {
            throw unavailable();
        }
    }

    private static boolean hasValidSessionClaim(
            SessionEntity session, SessionToolAttemptEntity attempt) {
        if (session.getActiveLoopId() == null) {
            return session.getLoopOwnerInstanceId() == null
                    && session.getLoopLeaseUntil() == null;
        }
        return session.getActiveLoopId().equals(attempt.getExecutionLoopId())
                && session.getLoopFence() == attempt.getExecutionFence()
                && Objects.equals(session.getLoopOwnerInstanceId(),
                        attempt.getExecutionOwnerInstanceId())
                && session.getLoopLeaseUntil() != null;
    }

    private static UUID validatedInboxId(
            SessionEntity session, SessionMessageInboxEntity row) {
        if (row == null || row.getId() == null || row.getId() <= 0L
                || row.getInboxId() == null
                || !session.getId().equals(row.getSessionId())
                || !Objects.equals(session.getUserId(), row.getUserId())) {
            throw unavailable();
        }
        return row.getInboxId();
    }

    private static UnknownOutcomeResolutionException unavailable() {
        return new UnknownOutcomeResolutionException(
                UnknownOutcomeResolutionException.Code.RESOLUTION_NOT_AVAILABLE,
                "Unknown outcome resolution is not available");
    }
}
