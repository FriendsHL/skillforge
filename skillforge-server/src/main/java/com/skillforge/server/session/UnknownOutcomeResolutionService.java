package com.skillforge.server.session;

import com.skillforge.core.engine.durability.PersistedMessageOccurrence;
import com.skillforge.core.model.Message;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionMessageInboxEntity;
import com.skillforge.server.entity.SessionToolAttemptEntity;
import com.skillforge.server.config.PlatformAccessPrincipal;
import com.skillforge.server.repository.SessionMessageInboxRepository;
import com.skillforge.server.repository.SessionMessageRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SessionToolAttemptRepository;
import com.skillforge.server.session.UnknownOutcomeResolutionAck.ActorAuthority;
import com.skillforge.server.session.UnknownOutcomeResolutionAuditWriter.AuditEntry;
import com.skillforge.server.session.UnknownOutcomeResolutionAuditWriter.AuditSnapshot;
import com.skillforge.server.session.UnknownOutcomeResolutionAuditWriter.AuditedInboxDisposition;
import com.skillforge.server.session.UnknownOutcomeAttemptVerifier.ManifestCall;
import com.skillforge.server.session.UnknownOutcomeResolutionRequest.InboxDisposition;
import com.skillforge.server.session.UnknownOutcomeResolutionRequest.InboxDispositionKind;
import com.skillforge.server.session.UnknownOutcomeResolutionRequest.PostAction;
import com.skillforge.server.session.persistence.DurableMessageBatchIntegrityException;
import com.skillforge.server.session.persistence.PersistedMessageCodec;
import com.skillforge.server.session.persistence.SessionOrderedMessageWriter;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Atomic, human-authorized closure of a generation-fenced Tool outcome that cannot be known.
 *
 * <p>This service deliberately has no Tool executor, Provider, scheduler, or run-coordinator
 * dependency. It only creates a durable post-action boundary; a separate coordinator may later
 * claim CONTINUE_CURRENT_TIMELINE.
 */
@Service
public class UnknownOutcomeResolutionService {

    public static final String ADMIN_AUTHORITY =
            PlatformAccessPrincipal.SESSION_RESOLVE_UNKNOWN_PERMISSION;
    public static final String UNKNOWN_RESULT_TEXT =
            "The operation may already have succeeded. Its outcome is unknown; "
                    + "do not retry automatically. Inspect external state or obtain a new user "
                    + "decision before acting.";

    private static final String UNCERTAIN = "UNCERTAIN_PENDING_RESOLUTION";
    private static final String RESOLVED = "RESOLVED_UNKNOWN";
    private final SessionRepository sessionRepository;
    private final SessionMessageRepository messageRepository;
    private final SessionMessageInboxRepository inboxRepository;
    private final SessionToolAttemptRepository attemptRepository;
    private final SessionOrderedMessageWriter messageWriter;
    private final PersistedMessageCodec messageCodec;
    private final UnknownOutcomeResolutionAuditWriter auditWriter;
    private final UnknownOutcomeAttemptVerifier attemptVerifier;
    private final TransactionTemplate transactionTemplate;

    public UnknownOutcomeResolutionService(
            SessionRepository sessionRepository,
            SessionMessageRepository messageRepository,
            SessionMessageInboxRepository inboxRepository,
            SessionToolAttemptRepository attemptRepository,
            SessionOrderedMessageWriter messageWriter,
            PersistedMessageCodec messageCodec,
            UnknownOutcomeResolutionAuditWriter auditWriter,
            UnknownOutcomeAttemptVerifier attemptVerifier,
            PlatformTransactionManager transactionManager) {
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository");
        this.messageRepository = Objects.requireNonNull(messageRepository, "messageRepository");
        this.inboxRepository = Objects.requireNonNull(inboxRepository, "inboxRepository");
        this.attemptRepository = Objects.requireNonNull(attemptRepository, "attemptRepository");
        this.messageWriter = Objects.requireNonNull(messageWriter, "messageWriter");
        this.messageCodec = Objects.requireNonNull(messageCodec, "messageCodec");
        this.auditWriter = Objects.requireNonNull(auditWriter, "auditWriter");
        this.attemptVerifier = Objects.requireNonNull(attemptVerifier, "attemptVerifier");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    /**
     * Resolves one exact attempt generation as unknown. Session/attempt come from the authenticated
     * route; actor identity and authorities come from the server authentication boundary.
     */
    public UnknownOutcomeResolutionAck resolve(
            String sessionId,
            long attemptId,
            UnknownOutcomeResolutionActor actor,
            UnknownOutcomeResolutionRequest request) {
        try {
            if (sessionId == null || sessionId.isBlank() || attemptId <= 0L) {
                throw unavailable();
            }
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(request, "request");
            UnknownOutcomeResolutionAck acknowledgement = transactionTemplate.execute(
                    ignored -> resolveLocked(sessionId, attemptId, actor, request));
            return Objects.requireNonNull(
                    acknowledgement, "unknown outcome resolution acknowledgement");
        } catch (UnknownOutcomeResolutionException safeFailure) {
            throw safeFailure;
        } catch (RuntimeException persistenceOrProtocolFailure) {
            // TransactionTemplate encloses deferred constraints and the actual commit. Do not retain
            // the cause: SQL diagnostics can contain Tool, inbox, or operator-reason payloads.
            throw persistenceFailure();
        }
    }

    private UnknownOutcomeResolutionAck resolveLocked(
            String sessionId,
            long attemptId,
            UnknownOutcomeResolutionActor actor,
            UnknownOutcomeResolutionRequest request) {
        SessionEntity session = sessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(UnknownOutcomeResolutionService::unavailable);
        ActorAuthority authority = UnknownOutcomeAuthorization.authorize(session, actor);

        AuditSnapshot existingAudit = auditWriter.find(request.resolutionRequestId())
                .orElse(null);
        if (existingAudit != null) {
            validateExactRetry(
                    existingAudit, sessionId, attemptId, actor, authority, request);
            return acknowledgement(existingAudit);
        }

        SessionToolAttemptEntity attempt = attemptRepository
                .findBySessionIdAndIdForUpdate(sessionId, attemptId)
                .orElseThrow(UnknownOutcomeResolutionService::unavailable);
        List<ManifestCall> manifest = validateInitialScope(session, attempt, request);
        List<SessionMessageInboxEntity> inbox = loadBoundedInbox(session);
        List<AuditedInboxDisposition> auditedDispositions = validateDispositions(
                session, inbox, request);
        List<PersistedMessageCodec.PersistedMessage> expectedResults = unknownResults(manifest);
        UUID resultBatchId = UUID.randomUUID();
        List<PersistedMessageOccurrence> persistedResults;
        try {
            persistedResults = messageWriter.appendNewBatchLocked(
                    sessionId, resultBatchId.toString(), expectedResults);
        } catch (DurableMessageBatchIntegrityException failure) {
            throw unavailable();
        }
        validatePersistedVector(persistedResults, manifest);

        applyAttemptResolution(attempt, request, resultBatchId, persistedResults.size());
        applyInboxDisposition(inbox, auditedDispositions);
        clearMatchingLoopClaim(session, attempt);
        session.setRestorePreparing(request.action() == PostAction.PREPARE_RESTORE);
        session.setMessageCount(Math.toIntExact(messageRepository.countBySessionId(sessionId)));
        attemptRepository.save(attempt);
        sessionRepository.save(session);

        AuditSnapshot audit = auditWriter.append(new AuditEntry(
                request.resolutionRequestId(), sessionId, attemptId, attempt.getStepId(),
                request.expectedHistoryEpoch(), request.expectedExecutionGeneration(),
                request.expectedExecutionFence(), actor.actorId(), authority, request.reason(),
                request.action(), auditedDispositions, resultBatchId));
        return acknowledgement(audit);
    }

    private List<ManifestCall> validateInitialScope(
            SessionEntity session,
            SessionToolAttemptEntity attempt,
            UnknownOutcomeResolutionRequest request) {
        if (session.isRestorePreparing()
                || session.getHistoryEpoch() != request.expectedHistoryEpoch()
                || !session.getId().equals(attempt.getSessionId())
                || !UNCERTAIN.equals(attempt.getState())
                || attempt.getHistoryEpoch() != request.expectedHistoryEpoch()
                || attempt.getExecutionGeneration() != request.expectedExecutionGeneration()
                || !Objects.equals(attempt.getExecutionFence(), request.expectedExecutionFence())
                || attempt.getExecutionLoopId() == null
                || attempt.getExecutionOwnerInstanceId() == null
                || attempt.getClaimRequestId() == null
                || attempt.getClaimedAt() == null
                || attempt.getExecutionLeaseUntil() == null
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
                || attempt.getPostActionFence() != null) {
            throw unavailable();
        }
        return attemptVerifier.verify(attempt);
    }

    private List<SessionMessageInboxEntity> loadBoundedInbox(SessionEntity session) {
        List<SessionMessageInboxEntity> inbox = inboxRepository
                .findBySessionIdOrderByIdAsc(
                        session.getId(),
                        PageRequest.of(
                                0, UnknownOutcomeResolutionRequest.MAX_INBOX_DISPOSITIONS + 1));
        if (inbox.size() > UnknownOutcomeResolutionRequest.MAX_INBOX_DISPOSITIONS) {
            throw unavailable();
        }
        return inbox;
    }

    private List<AuditedInboxDisposition> validateDispositions(
            SessionEntity session,
            List<SessionMessageInboxEntity> inbox,
            UnknownOutcomeResolutionRequest request) {
        if (inbox.size() != request.inboxDispositions().size()) throw unavailable();
        Map<UUID, InboxDispositionKind> requested = new HashMap<>();
        for (InboxDisposition disposition : request.inboxDispositions()) {
            if (requested.put(disposition.inboxId(), disposition.disposition()) != null) {
                throw unavailable();
            }
        }
        List<AuditedInboxDisposition> audited = new ArrayList<>(inbox.size());
        for (SessionMessageInboxEntity row : inbox) {
            InboxDispositionKind disposition = requested.remove(row.getInboxId());
            if (row.getId() == null || row.getInboxId() == null
                    || !Objects.equals(row.getUserId(), session.getUserId())
                    || disposition == null || !allowed(request.action(), disposition)) {
                throw unavailable();
            }
            validateExactQueuedUser(row.getMessageJson());
            audited.add(new AuditedInboxDisposition(
                    row.getInboxId(), auditWriter.inboxMessageHash(row.getMessageJson()),
                    disposition));
        }
        if (!requested.isEmpty()) throw unavailable();
        return List.copyOf(audited);
    }

    private void validateExactQueuedUser(String exactMessageJson) {
        try {
            Message message = messageCodec.readMessage(exactMessageJson);
            if (!exactMessageJson.equals(messageCodec.writeMessage(message))) {
                throw unavailable();
            }
            CanonicalConversationalUserValidator.requireValid(message);
        } catch (UnknownOutcomeResolutionException safeFailure) {
            throw safeFailure;
        } catch (RuntimeException invalidInboxPayload) {
            throw unavailable();
        }
    }

    private static boolean allowed(PostAction action, InboxDispositionKind disposition) {
        return switch (action) {
            case CONTINUE_CURRENT_TIMELINE ->
                    disposition == InboxDispositionKind.KEEP_FOR_CONTINUE;
            case PREPARE_RESTORE ->
                    disposition == InboxDispositionKind.KEEP_FOR_RESTORE
                            || disposition == InboxDispositionKind.DISCARD_FOR_RESTORE;
        };
    }

    private static List<PersistedMessageCodec.PersistedMessage> unknownResults(
            List<ManifestCall> manifest) {
        return manifest.stream()
                .map(call -> new PersistedMessageCodec.PersistedMessage(
                        Message.toolResult(
                                call.toolUseId(), UNKNOWN_RESULT_TEXT, true, "OUTCOME_UNKNOWN"),
                        "NORMAL", "normal", null, null, Collections.emptyMap(), null))
                .toList();
    }

    private static void validatePersistedVector(
            List<PersistedMessageOccurrence> results, List<ManifestCall> manifest) {
        if (results.size() != manifest.size()) throw unavailable();
        for (int ordinal = 0; ordinal < results.size(); ordinal++) {
            PersistedMessageOccurrence result = results.get(ordinal);
            if (result.writeBatchOrdinal() != ordinal
                    || result.seqNo() < 0L
                    || !manifest.get(ordinal).toolUseId().equals(
                            toolUseId(result.message().toMessage()))) {
                throw unavailable();
            }
        }
    }

    private static String toolUseId(Message result) {
        if (result.getRole() != Message.Role.USER
                || !(result.getContent() instanceof List<?> blocks)
                || blocks.size() != 1
                || !(blocks.get(0) instanceof Map<?, ?> block)
                || !"tool_result".equals(block.get("type"))
                || !(block.get("tool_use_id") instanceof String toolUseId)
                || !UNKNOWN_RESULT_TEXT.equals(block.get("content"))
                || !Boolean.TRUE.equals(block.get("is_error"))
                || !"OUTCOME_UNKNOWN".equals(block.get("error_type"))) {
            throw unavailable();
        }
        return toolUseId;
    }

    private static void applyAttemptResolution(
            SessionToolAttemptEntity attempt,
            UnknownOutcomeResolutionRequest request,
            UUID resultBatchId,
            int resultCount) {
        attempt.setState(RESOLVED);
        attempt.setResultBatchId(resultBatchId);
        attempt.setResultExecutionGeneration(request.expectedExecutionGeneration());
        attempt.setResultExecutionFence(request.expectedExecutionFence());
        attempt.setArchivePreparationState("PENDING");
        attempt.setArchivePreparedCount(0);
        attempt.setArchiveTotalCount(resultCount);
        if (request.action() == PostAction.CONTINUE_CURRENT_TIMELINE) {
            attempt.setPostActionState("PENDING");
            attempt.setPostActionResolutionRequestId(request.resolutionRequestId());
            attempt.setPostActionResultBatchId(resultBatchId);
            attempt.setPostActionKind(PostAction.CONTINUE_CURRENT_TIMELINE.name());
        }
    }

    private void applyInboxDisposition(
            List<SessionMessageInboxEntity> inbox,
            List<AuditedInboxDisposition> dispositions) {
        Map<UUID, InboxDispositionKind> byId = new HashMap<>();
        dispositions.forEach(value -> byId.put(value.inboxId(), value.disposition()));
        List<SessionMessageInboxEntity> discarded = inbox.stream()
                .filter(row -> byId.get(row.getInboxId())
                        == InboxDispositionKind.DISCARD_FOR_RESTORE)
                .toList();
        if (!discarded.isEmpty()) inboxRepository.deleteAllInBatch(discarded);
    }

    private static void clearMatchingLoopClaim(
            SessionEntity session, SessionToolAttemptEntity attempt) {
        if (session.getActiveLoopId() == null) {
            if (session.getLoopOwnerInstanceId() != null || session.getLoopLeaseUntil() != null) {
                throw unavailable();
            }
            return;
        }
        if (!session.getActiveLoopId().equals(attempt.getExecutionLoopId())
                || session.getLoopFence() != attempt.getExecutionFence()
                || !Objects.equals(session.getLoopOwnerInstanceId(),
                        attempt.getExecutionOwnerInstanceId())
                || session.getLoopLeaseUntil() == null) {
            throw unavailable();
        }
        session.setActiveLoopId(null);
        session.setLoopOwnerInstanceId(null);
        session.setLoopLeaseUntil(null);
    }

    private void validateExactRetry(
            AuditSnapshot audit,
            String sessionId,
            long attemptId,
            UnknownOutcomeResolutionActor actor,
            ActorAuthority authority,
            UnknownOutcomeResolutionRequest request) {
        if (!sessionId.equals(audit.sessionId())
                || attemptId != audit.attemptId()
                || actor.actorId() != audit.actorId()
                || authority != audit.actorAuthority()
                || request.expectedHistoryEpoch() != audit.historyEpoch()
                || request.expectedExecutionGeneration() != audit.executionGeneration()
                || request.expectedExecutionFence() != audit.executionFence()
                || request.action() != audit.action()
                || !auditWriter.reasonHash(request.reason()).equals(audit.reasonHash())
                || !RESOLVED.equals(audit.outcomeState())
                || !sameDispositions(
                        request.inboxDispositions(), audit.publicDispositions())) {
            throw unavailable();
        }
    }

    private static boolean sameDispositions(
            List<InboxDisposition> left, List<InboxDisposition> right) {
        if (left.size() != right.size()) return false;
        Map<UUID, InboxDispositionKind> leftById = new HashMap<>();
        Map<UUID, InboxDispositionKind> rightById = new HashMap<>();
        left.forEach(value -> leftById.put(value.inboxId(), value.disposition()));
        right.forEach(value -> rightById.put(value.inboxId(), value.disposition()));
        return leftById.size() == left.size()
                && rightById.size() == right.size()
                && leftById.equals(rightById);
    }

    private static UnknownOutcomeResolutionAck acknowledgement(AuditSnapshot audit) {
        PostAction action = audit.action();
        return new UnknownOutcomeResolutionAck(
                audit.resolutionRequestId(), audit.sessionId(), audit.attemptId(), audit.stepId(),
                audit.historyEpoch(), audit.executionGeneration(), audit.executionFence(),
                audit.actorAuthority(), action, audit.resultBatchId(), audit.outcomeState(),
                audit.publicDispositions(),
                action == PostAction.CONTINUE_CURRENT_TIMELINE ? "PENDING" : "NONE",
                action == PostAction.PREPARE_RESTORE,
                audit.auditId(), audit.createdAt());
    }

    private static UnknownOutcomeResolutionException unavailable() {
        return new UnknownOutcomeResolutionException(
                UnknownOutcomeResolutionException.Code.RESOLUTION_NOT_AVAILABLE,
                "Unknown outcome resolution is not available");
    }

    private static UnknownOutcomeResolutionException persistenceFailure() {
        return new UnknownOutcomeResolutionException(
                UnknownOutcomeResolutionException.Code.PERSISTENCE_FAILED,
                "Unknown outcome resolution could not be persisted");
    }

}
