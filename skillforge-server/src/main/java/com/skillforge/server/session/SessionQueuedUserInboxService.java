package com.skillforge.server.session;

import com.skillforge.core.engine.durability.DurableFrontier;
import com.skillforge.core.engine.durability.LoopDurabilityScope;
import com.skillforge.core.engine.durability.MessageSnapshot;
import com.skillforge.core.engine.durability.PersistedMessageOccurrence;
import com.skillforge.core.model.Message;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionMessageEntity;
import com.skillforge.server.entity.SessionMessageInboxEntity;
import com.skillforge.server.entity.SessionToolAttemptEntity;
import com.skillforge.server.repository.SessionMessageInboxRepository;
import com.skillforge.server.repository.SessionMessageRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SessionToolAttemptRepository;
import com.skillforge.server.session.persistence.PersistedMessageCodec;
import com.skillforge.server.session.persistence.SessionOrderedMessageWriter;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Transactional boundary for USER messages that arrive while a durable Session loop is open.
 *
 * <p>Idle input is deliberately routed back to loop admission instead of being persisted here:
 * claiming the loop and appending its initiating USER must remain one transaction. Live and
 * expired-loop input is accepted into the inbox while holding the Session row lock. Draining uses
 * each inbox ID as the raw-row write batch ID, so a retry can never append the same accepted input
 * twice even if the acknowledgement was lost.
 */
@Service
public class SessionQueuedUserInboxService {

    private static final int MAX_DRAIN_ITEMS = 512;
    private static final List<String> ATTEMPT_STATES = List.of(
            "INTENT_COMMITTED", "EXECUTING", "WAITING_USER", "RESULTS_COMMITTED",
            "UNCERTAIN_PENDING_RESOLUTION", "RESOLVED_UNKNOWN");
    private static final Set<String> OPEN_ATTEMPT_STATES = Set.of(
            "INTENT_COMMITTED", "EXECUTING", "WAITING_USER",
            "UNCERTAIN_PENDING_RESOLUTION");
    private static final Set<String> CLOSED_ATTEMPT_STATES = Set.of(
            "RESULTS_COMMITTED", "RESOLVED_UNKNOWN");
    private static final Set<String> ARCHIVE_READY_STATES = Set.of(
            "PREPARED", "RAW_FALLBACK");

    private final SessionRepository sessionRepository;
    private final SessionMessageRepository messageRepository;
    private final SessionMessageInboxRepository inboxRepository;
    private final SessionToolAttemptRepository attemptRepository;
    private final PersistedMessageCodec messageCodec;
    private final SessionOrderedMessageWriter messageWriter;
    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;

    public SessionQueuedUserInboxService(
            SessionRepository sessionRepository,
            SessionMessageRepository messageRepository,
            SessionMessageInboxRepository inboxRepository,
            SessionToolAttemptRepository attemptRepository,
            PersistedMessageCodec messageCodec,
            SessionOrderedMessageWriter messageWriter,
            EntityManager entityManager,
            PlatformTransactionManager transactionManager) {
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository");
        this.messageRepository = Objects.requireNonNull(messageRepository, "messageRepository");
        this.inboxRepository = Objects.requireNonNull(inboxRepository, "inboxRepository");
        this.attemptRepository = Objects.requireNonNull(attemptRepository, "attemptRepository");
        this.messageCodec = Objects.requireNonNull(messageCodec, "messageCodec");
        this.messageWriter = Objects.requireNonNull(messageWriter, "messageWriter");
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    /**
     * Accepts a USER message into durable queue order or reports that idle admission must claim the
     * Session and append it directly. The authenticated user ID is supplied by the harness layer.
     */
    public AcceptanceAck acceptOrRoute(
            String sessionId,
            long userId,
            UUID inboxId,
            MessageSnapshot userMessage) {
        try {
            AcceptanceAck acknowledgement = transactionTemplate.execute(
                    ignored -> acceptOrRouteLocked(sessionId, userId, inboxId, userMessage));
            return Objects.requireNonNull(acknowledgement, "inbox acceptance acknowledgement");
        } catch (RuntimeException persistenceOrProtocolFailure) {
            if (DurableRecoveryRetryableException.isInfrastructureTransient(
                    persistenceOrProtocolFailure)) {
                throw new DurableRecoveryRetryableException();
            }
            throw new IllegalStateException("Queued USER acceptance failed");
        }
    }

    /**
     * Drains at most one bounded page in canonical inbox-ID order under the winning Session fence.
     * Callers must continue while {@link DrainAck#remainingCount()} is positive before Provider use.
     */
    public DrainAck drain(
            LoopDurabilityScope scope,
            DurableFrontier expectedPreDrainFrontier) {
        try {
            DrainAck acknowledgement = transactionTemplate.execute(
                    ignored -> drainLocked(scope, expectedPreDrainFrontier));
            return Objects.requireNonNull(acknowledgement, "inbox drain acknowledgement");
        } catch (RuntimeException persistenceOrProtocolFailure) {
            if (DurableRecoveryRetryableException.isInfrastructureTransient(
                    persistenceOrProtocolFailure)) {
                throw new DurableRecoveryRetryableException();
            }
            throw new IllegalStateException("Queued USER drain failed");
        }
    }

    private AcceptanceAck acceptOrRouteLocked(
            String sessionId,
            long userId,
            UUID inboxId,
            MessageSnapshot userMessage) {
        requireSessionId(sessionId);
        if (userId < 0L) throw new IllegalStateException();
        Objects.requireNonNull(inboxId, "inboxId");
        Message materialized = Objects.requireNonNull(userMessage, "userMessage").toMessage();
        CanonicalConversationalUserValidator.requireValid(materialized);
        String exactMessageJson = canonicalMessageJson(materialized);

        SessionEntity session = sessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(IllegalStateException::new);
        if (!Objects.equals(session.getUserId(), userId) || session.isRestorePreparing()) {
            throw new IllegalStateException();
        }
        Instant databaseNow = sessionRepository.currentDatabaseTime();
        validateLoopTupleShape(session);

        PersistedMessageCodec.PersistedMessage expectedRaw = rawUser(materialized);
        List<SessionMessageEntity> alreadyDrained = messageRepository
                .findBySessionIdAndWriteBatchIdOrderByWriteBatchOrdinalAsc(
                        sessionId, inboxId.toString());
        if (!alreadyDrained.isEmpty()) {
            PersistedMessageOccurrence occurrence = messageWriter.readExactBatch(
                    sessionId, inboxId.toString(), List.of(expectedRaw)).get(0);
            return new AcceptanceAck(
                    AcceptanceMode.ALREADY_DRAINED, inboxId, null,
                    MessageSnapshot.capture(materialized), occurrence);
        }

        SessionMessageInboxEntity existing = inboxRepository
                .findBySessionIdAndInboxId(sessionId, inboxId)
                .orElse(null);
        if (existing != null) {
            validateExactInboxRetry(existing, userId, exactMessageJson);
            return queuedAck(session, databaseNow, existing, materialized);
        }

        boolean blockingAttempt = hasDrainBarrier(sessionId);
        if (session.getActiveLoopId() == null && !blockingAttempt) {
            return new AcceptanceAck(
                    AcceptanceMode.IDLE_ADMISSION_REQUIRED, inboxId, null,
                    MessageSnapshot.capture(materialized), null);
        }

        SessionMessageInboxEntity row = new SessionMessageInboxEntity();
        row.setInboxId(inboxId);
        row.setSessionId(sessionId);
        row.setUserId(userId);
        row.setMessageJson(exactMessageJson);
        inboxRepository.saveAndFlush(row);
        entityManager.refresh(row);
        if (row.getId() == null || row.getCreatedAt() == null) {
            throw new IllegalStateException();
        }
        session.setLastUserMessageAt(databaseNow);
        sessionRepository.save(session);
        return queuedAck(session, databaseNow, row, materialized);
    }

    private DrainAck drainLocked(
            LoopDurabilityScope scope,
            DurableFrontier expectedPreDrainFrontier) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(expectedPreDrainFrontier, "expectedPreDrainFrontier");
        SessionEntity session = sessionRepository.findByIdForUpdate(scope.sessionId())
                .orElseThrow(IllegalStateException::new);
        Instant databaseNow = sessionRepository.currentDatabaseTime();
        validateCurrentLiveScope(session, scope, databaseNow);
        if (hasDrainBarrier(scope.sessionId())) throw new IllegalStateException();

        DurableFrontier current = currentFrontier(scope.sessionId());
        if (!current.equals(expectedPreDrainFrontier)) {
            return replayCommittedDrain(
                    scope, expectedPreDrainFrontier, current);
        }

        List<SessionMessageInboxEntity> queued = inboxRepository
                .findBySessionIdOrderByIdAsc(
                        scope.sessionId(), PageRequest.of(0, MAX_DRAIN_ITEMS));
        List<DrainedItem> drained = new ArrayList<>(queued.size());
        for (SessionMessageInboxEntity row : queued) {
            drained.add(drainOneLocked(scope, row));
        }
        if (!queued.isEmpty()) {
            inboxRepository.deleteAllInBatch(queued);
            session.setMessageCount(Math.toIntExact(
                    messageRepository.countBySessionId(scope.sessionId())));
            sessionRepository.save(session);
        }
        long remaining = inboxRepository.countBySessionId(scope.sessionId());
        DurableFrontier frontier = currentFrontier(scope.sessionId());
        return new DrainAck(
                scope, expectedPreDrainFrontier, drained, frontier, remaining);
    }

    private DrainedItem drainOneLocked(
            LoopDurabilityScope scope,
            SessionMessageInboxEntity inboxRow) {
        if (!scope.sessionId().equals(inboxRow.getSessionId())
                || !Objects.equals(scope.userId(), inboxRow.getUserId())
                || inboxRow.getId() == null
                || inboxRow.getInboxId() == null) {
            throw new IllegalStateException();
        }
        Message message = messageCodec.readMessage(inboxRow.getMessageJson());
        CanonicalConversationalUserValidator.requireValid(message);
        if (!canonicalMessageJson(message).equals(inboxRow.getMessageJson())) {
            throw new IllegalStateException();
        }
        PersistedMessageCodec.PersistedMessage expected = rawUser(message);
        String writeBatchId = inboxRow.getInboxId().toString();
        List<SessionMessageEntity> existing = messageRepository
                .findBySessionIdAndWriteBatchIdOrderByWriteBatchOrdinalAsc(
                        scope.sessionId(), writeBatchId);
        PersistedMessageOccurrence occurrence = existing.isEmpty()
                ? messageWriter.appendNewBatchLocked(
                        scope.sessionId(), writeBatchId, List.of(expected)).get(0)
                : messageWriter.readExactBatch(
                        scope.sessionId(), writeBatchId, List.of(expected)).get(0);
        return new DrainedItem(
                inboxRow.getInboxId(), occurrence);
    }

    private DrainAck replayCommittedDrain(
            LoopDurabilityScope scope,
            DurableFrontier expectedPreDrainFrontier,
            DurableFrontier currentFrontier) {
        if (currentFrontier.maxSeq() <= expectedPreDrainFrontier.maxSeq()) {
            throw new IllegalStateException();
        }
        if (!DurableFrontier.EMPTY.equals(expectedPreDrainFrontier)) {
            SessionMessageEntity predecessor = messageRepository
                    .findTopBySessionIdAndSeqNoLessThanOrderBySeqNoDesc(
                            scope.sessionId(), expectedPreDrainFrontier.maxSeq() + 1L)
                    .orElseThrow(IllegalStateException::new);
            if (predecessor.getId() == null
                    || predecessor.getId() != expectedPreDrainFrontier.maxMessageId()
                    || predecessor.getSeqNo() != expectedPreDrainFrontier.maxSeq()) {
                throw new IllegalStateException();
            }
        }
        List<SessionMessageEntity> suffix = messageRepository
                .findBySessionIdAndSeqNoGreaterThanEqualOrderBySeqNoAsc(
                        scope.sessionId(), expectedPreDrainFrontier.maxSeq() + 1L,
                        PageRequest.of(0, MAX_DRAIN_ITEMS + 1))
                .getContent();
        if (suffix.isEmpty() || suffix.size() > MAX_DRAIN_ITEMS) {
            throw new IllegalStateException();
        }
        List<DrainedItem> replayed = new ArrayList<>(suffix.size());
        long expectedSeq = expectedPreDrainFrontier.maxSeq() + 1L;
        for (SessionMessageEntity row : suffix) {
            if (row.getId() == null || row.getSeqNo() != expectedSeq++) {
                throw new IllegalStateException();
            }
            Message message = decodeExactQueuedUser(row);
            UUID inboxId;
            try {
                inboxId = UUID.fromString(row.getWriteBatchId());
                if (!inboxId.toString().equals(row.getWriteBatchId())) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException | NullPointerException invalid) {
                throw new IllegalStateException();
            }
            PersistedMessageOccurrence occurrence = messageWriter.readExactBatch(
                    scope.sessionId(), row.getWriteBatchId(), List.of(rawUser(message))).get(0);
            if (occurrence.messageId() != row.getId()
                    || occurrence.seqNo() != row.getSeqNo()) {
                throw new IllegalStateException();
            }
            replayed.add(new DrainedItem(inboxId, occurrence));
        }
        DrainedItem tail = replayed.get(replayed.size() - 1);
        if (tail.message().messageId() != currentFrontier.maxMessageId()
                || tail.message().seqNo() != currentFrontier.maxSeq()) {
            throw new IllegalStateException();
        }
        return new DrainAck(
                scope,
                expectedPreDrainFrontier,
                replayed,
                currentFrontier,
                inboxRepository.countBySessionId(scope.sessionId()));
    }

    private Message decodeExactQueuedUser(SessionMessageEntity row) {
        if (!"user".equals(row.getRole())
                || !"NORMAL".equals(row.getMsgType())
                || !"normal".equals(row.getMessageType())
                || row.getControlId() != null
                || row.getAnsweredAt() != null
                || row.getTraceId() != null
                || row.getWriteBatchOrdinal() == null
                || row.getWriteBatchOrdinal() != 0) {
            throw new IllegalStateException();
        }
        PersistedMessageCodec.PersistedMessage decoded = messageCodec.decodeRow(
                new PersistedMessageCodec.EncodedRow(
                        row.getRole(), row.getContentJson(), row.getReasoningContent(),
                        row.getMsgType(), row.getMessageType(), row.getControlId(),
                        row.getAnsweredAt(), row.getMetadataJson(), row.getTraceId()));
        if (!decoded.metadata().isEmpty()) throw new IllegalStateException();
        CanonicalConversationalUserValidator.requireValid(decoded.message());
        return decoded.message();
    }

    private DurableFrontier currentFrontier(String sessionId) {
        return messageRepository.findTopBySessionIdOrderBySeqNoDesc(sessionId)
                .map(row -> {
                    if (row.getId() == null) throw new IllegalStateException();
                    return new DurableFrontier(row.getId(), row.getSeqNo());
                })
                .orElse(DurableFrontier.EMPTY);
    }

    private AcceptanceAck queuedAck(
            SessionEntity session,
            Instant databaseNow,
            SessionMessageInboxEntity row,
            Message message) {
        AcceptanceMode mode = session.getActiveLoopId() != null
                && session.getLoopLeaseUntil().isAfter(databaseNow)
                ? AcceptanceMode.QUEUED_LIVE
                : AcceptanceMode.QUEUED_RECOVERY_REQUIRED;
        return new AcceptanceAck(
                mode, row.getInboxId(), row.getId(), MessageSnapshot.capture(message), null);
    }

    private boolean hasDrainBarrier(String sessionId) {
        for (SessionToolAttemptEntity attempt : attemptRepository
                .findBySessionIdAndStateIn(sessionId, ATTEMPT_STATES)) {
            if (OPEN_ATTEMPT_STATES.contains(attempt.getState())
                    || (CLOSED_ATTEMPT_STATES.contains(attempt.getState())
                    && !ARCHIVE_READY_STATES.contains(
                            attempt.getArchivePreparationState()))) {
                return true;
            }
        }
        // Fail closed for legacy/corrupt histories where a TOOL_USE tail exists without the
        // durable attempt row that should normally make it visible through the check above.
        return messageRepository.findTopBySessionIdOrderBySeqNoDesc(sessionId)
                .map(this::isToolIntentTail)
                .orElse(false);
    }

    private boolean isToolIntentTail(SessionMessageEntity row) {
        PersistedMessageCodec.PersistedMessage decoded = messageCodec.decodeRow(
                new PersistedMessageCodec.EncodedRow(
                        row.getRole(), row.getContentJson(), row.getReasoningContent(),
                        row.getMsgType(), row.getMessageType(), row.getControlId(),
                        row.getAnsweredAt(), row.getMetadataJson(), row.getTraceId()));
        return decoded.message().getRole() == Message.Role.ASSISTANT
                && !decoded.message().getToolUseBlocks().isEmpty();
    }

    private static PersistedMessageCodec.PersistedMessage rawUser(Message message) {
        return new PersistedMessageCodec.PersistedMessage(
                message, "NORMAL", "normal", null, null,
                Collections.emptyMap(), null);
    }

    private String canonicalMessageJson(Message message) {
        String encoded = messageCodec.writeMessage(message);
        Message decoded = messageCodec.readMessage(encoded);
        if (!encoded.equals(messageCodec.writeMessage(decoded))) {
            throw new IllegalStateException();
        }
        return encoded;
    }

    private static void validateExactInboxRetry(
            SessionMessageInboxEntity row,
            long userId,
            String exactMessageJson) {
        if (!Objects.equals(row.getUserId(), userId)
                || !exactMessageJson.equals(row.getMessageJson())
                || row.getId() == null
                || row.getCreatedAt() == null) {
            throw new IllegalStateException();
        }
    }

    private static void validateLoopTupleShape(SessionEntity session) {
        boolean hasLoop = session.getActiveLoopId() != null;
        boolean hasOwner = session.getLoopOwnerInstanceId() != null;
        boolean hasLease = session.getLoopLeaseUntil() != null;
        if (hasLoop != hasOwner || hasLoop != hasLease) {
            throw new IllegalStateException();
        }
    }

    private static void validateCurrentLiveScope(
            SessionEntity session,
            LoopDurabilityScope scope,
            Instant databaseNow) {
        validateLoopTupleShape(session);
        if (!Objects.equals(session.getUserId(), scope.userId())
                || session.getHistoryEpoch() != scope.historyEpoch()
                || session.isRestorePreparing()
                || !scope.loopId().equals(session.getActiveLoopId())
                || session.getLoopFence() != scope.loopFence()
                || !scope.ownerInstanceId().equals(session.getLoopOwnerInstanceId())
                || session.getLoopLeaseUntil() == null
                || !session.getLoopLeaseUntil().isAfter(databaseNow)) {
            throw new IllegalStateException();
        }
    }

    private static void requireSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalStateException();
    }

    public enum AcceptanceMode {
        IDLE_ADMISSION_REQUIRED,
        QUEUED_LIVE,
        QUEUED_RECOVERY_REQUIRED,
        ALREADY_DRAINED
    }

    public record AcceptanceAck(
            AcceptanceMode mode,
            UUID inboxId,
            Long inboxRowId,
            MessageSnapshot message,
            PersistedMessageOccurrence drainedOccurrence) {

        public AcceptanceAck {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(inboxId, "inboxId");
            Objects.requireNonNull(message, "message");
            if ((mode == AcceptanceMode.QUEUED_LIVE
                    || mode == AcceptanceMode.QUEUED_RECOVERY_REQUIRED)
                    != (inboxRowId != null)) {
                throw new IllegalArgumentException("queued acceptance requires its inbox row");
            }
            if ((mode == AcceptanceMode.ALREADY_DRAINED)
                    != (drainedOccurrence != null)) {
                throw new IllegalArgumentException(
                        "drained acceptance requires its persisted occurrence");
            }
            if (inboxRowId != null && inboxRowId <= 0L) {
                throw new IllegalArgumentException("inboxRowId must be positive");
            }
            if (drainedOccurrence != null
                    && (!inboxId.toString().equals(drainedOccurrence.writeBatchId())
                    || drainedOccurrence.writeBatchOrdinal() != 0
                    || !message.equals(drainedOccurrence.message()))) {
                throw new IllegalArgumentException(
                        "drained acceptance must identify the same exact USER");
            }
        }
    }

    public record DrainedItem(
            UUID inboxId,
            PersistedMessageOccurrence message) {

        public DrainedItem {
            Objects.requireNonNull(inboxId, "inboxId");
            Objects.requireNonNull(message, "message");
            if (!inboxId.toString().equals(message.writeBatchId())
                    || message.writeBatchOrdinal() != 0) {
                throw new IllegalArgumentException(
                        "drained USER must retain its inbox identity");
            }
        }
    }

    public record DrainAck(
            LoopDurabilityScope scope,
            DurableFrontier preDrainFrontier,
            List<DrainedItem> items,
            DurableFrontier postDrainFrontier,
            long remainingCount) {

        public DrainAck {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(preDrainFrontier, "preDrainFrontier");
            items = List.copyOf(Objects.requireNonNull(items, "items"));
            Objects.requireNonNull(postDrainFrontier, "postDrainFrontier");
            if (remainingCount < 0L) {
                throw new IllegalArgumentException("remainingCount must be nonnegative");
            }
            long previousMessageSeq = -1L;
            for (DrainedItem item : items) {
                if (item.message().seqNo() <= previousMessageSeq) {
                    throw new IllegalArgumentException(
                            "drained USER items must retain database order");
                }
                previousMessageSeq = item.message().seqNo();
            }
        }
    }
}
