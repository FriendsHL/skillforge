package com.skillforge.server.session;

import com.skillforge.core.engine.durability.DurableFrontier;
import com.skillforge.core.engine.durability.LoopDurabilityScope;
import com.skillforge.core.engine.durability.MessageSnapshot;
import com.skillforge.core.engine.durability.PersistedMessageOccurrence;
import com.skillforge.core.model.Message;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionMessageEntity;
import com.skillforge.server.entity.SessionToolAttemptEntity;
import com.skillforge.server.repository.SessionMessageRepository;
import com.skillforge.server.repository.SessionMessageInboxRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SessionToolAttemptRepository;
import com.skillforge.server.runtime.RuntimeFailureState;
import com.skillforge.server.session.persistence.DurableMessageBatchIntegrityException;
import com.skillforge.server.session.persistence.PersistedMessageCodec;
import com.skillforge.server.session.persistence.SessionOrderedMessageWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Append-only final reconciliation for a durable Agent loop.
 *
 * <p>This boundary never derives missing Tool rows from the engine's in-memory transcript and
 * never invokes a rewrite path. It validates the winning loop scope and acknowledged database
 * frontier, then may append exactly one ordinary terminal assistant through the ordered writer.
 */
@Service
public class SessionDurableCompletionReconciler {

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
    private final SessionMessageInboxRepository inboxRepository;
    private final SessionOrderedMessageWriter messageWriter;
    private final TransactionTemplate transactionTemplate;

    public SessionDurableCompletionReconciler(
            SessionRepository sessionRepository,
            SessionMessageRepository messageRepository,
            SessionToolAttemptRepository attemptRepository,
            SessionMessageInboxRepository inboxRepository,
            SessionOrderedMessageWriter messageWriter,
            PlatformTransactionManager transactionManager) {
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository");
        this.messageRepository = Objects.requireNonNull(messageRepository, "messageRepository");
        this.attemptRepository = Objects.requireNonNull(attemptRepository, "attemptRepository");
        this.inboxRepository = Objects.requireNonNull(inboxRepository, "inboxRepository");
        this.messageWriter = Objects.requireNonNull(messageWriter, "messageWriter");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    public CompletionAck reconcile(
            LoopDurabilityScope scope,
            DurableFrontier acknowledgedFrontier,
            UUID completionBatchId,
            MessageSnapshot terminalAssistant,
            String traceId) {
        return reconcile(scope, acknowledgedFrontier, completionBatchId,
                terminalAssistant, traceId, 0L, 0L);
    }

    public CompletionAck reconcile(
            LoopDurabilityScope scope,
            DurableFrontier acknowledgedFrontier,
            UUID completionBatchId,
            MessageSnapshot terminalAssistant,
            String traceId,
            long inputTokens,
            long outputTokens) {
        try {
            CompletionAck acknowledgement = transactionTemplate.execute(ignored -> reconcileLocked(
                    scope, acknowledgedFrontier, completionBatchId, terminalAssistant, traceId,
                    inputTokens, outputTokens));
            return Objects.requireNonNull(acknowledgement, "completion acknowledgement");
        } catch (RuntimeException persistenceOrProtocolFailure) {
            if (DurableRecoveryRetryableException.isInfrastructureTransient(
                    persistenceOrProtocolFailure)) {
                throw new DurableRecoveryRetryableException();
            }
            // Never expose SQL diagnostics or a rejected terminal payload through this boundary.
            throw new IllegalStateException("Durable completion reconciliation failed");
        }
    }

    private CompletionAck reconcileLocked(
            LoopDurabilityScope scope,
            DurableFrontier acknowledgedFrontier,
            UUID completionBatchId,
            MessageSnapshot terminalAssistant,
            String traceId,
            long inputTokens,
            long outputTokens) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(acknowledgedFrontier, "acknowledgedFrontier");
        Objects.requireNonNull(completionBatchId, "completionBatchId");
        Objects.requireNonNull(terminalAssistant, "terminalAssistant");
        if (inputTokens < 0L || outputTokens < 0L) throw new IllegalStateException();
        SessionEntity session = sessionRepository.findByIdForUpdate(scope.sessionId())
                .orElseThrow(IllegalStateException::new);
        String batchId = completionBatchId.toString();
        List<SessionMessageEntity> existing = messageRepository
                .findBySessionIdAndWriteBatchIdOrderByWriteBatchOrdinalAsc(
                        scope.sessionId(), batchId);
        boolean acknowledgementRetry = !existing.isEmpty();
        if (existing.isEmpty()) {
            validateLiveScope(session, scope, sessionRepository.currentDatabaseTime());
        } else {
            validateRetryScope(session, scope);
        }
        rejectBlockingAttempt(scope.sessionId());

        Message terminal = terminalAssistant.toMessage();
        validatePlainTerminalAssistant(terminal);

        PersistedMessageCodec.PersistedMessage expectedTerminal =
                new PersistedMessageCodec.PersistedMessage(
                        terminal,
                        "NORMAL",
                        "normal",
                        null,
                        null,
                        Collections.emptyMap(),
                        traceId);
        PersistedMessageOccurrence persistedTerminal;
        if (existing.isEmpty()) {
            requireCurrentFrontier(scope.sessionId(), acknowledgedFrontier);
            persistedTerminal = appendTerminal(scope.sessionId(), batchId, expectedTerminal);
        } else {
            persistedTerminal = readTerminal(scope.sessionId(), batchId, expectedTerminal);
            requirePredecessor(scope.sessionId(), persistedTerminal, acknowledgedFrontier);
            requireCurrentFrontier(scope.sessionId(), frontierOf(persistedTerminal));
        }

        if (persistedTerminal.seqNo() != acknowledgedFrontier.maxSeq() + 1L) {
            throw new IllegalStateException();
        }
        requirePredecessor(scope.sessionId(), persistedTerminal, acknowledgedFrontier);
        session.setMessageCount(Math.toIntExact(messageRepository.countBySessionId(scope.sessionId())));
        applyUsageOnce(session, acknowledgementRetry, inputTokens, outputTokens);
        boolean continuationRequired = inboxRepository.countBySessionId(scope.sessionId()) > 0L;
        if (continuationRequired) {
            // The Session lock also serializes inbox acceptance. Therefore either the queued
            // input is observed here and this exact fence remains live, or a later accept sees
            // the released idle Session and routes through a fresh admission. No accepted USER
            // can be stranded behind a terminal assistant.
            session.setCompletedAt(null);
            session.setRuntimeStatus("running");
            RuntimeFailureState.clear(session);
            session.setRuntimeStep("Queued input");
        } else {
            completeAndRelease(session);
        }
        sessionRepository.save(session);
        DurableFrontier postCompletion = frontierOf(persistedTerminal);
        return new CompletionAck(
                completionBatchId,
                acknowledgedFrontier,
                postCompletion,
                persistedTerminal,
                continuationRequired,
                continuationRequired ? session.getLoopLeaseUntil() : null);
    }

    private PersistedMessageOccurrence appendTerminal(
            String sessionId,
            String batchId,
            PersistedMessageCodec.PersistedMessage expectedTerminal) {
        try {
            return messageWriter.appendNewBatchLocked(
                    sessionId, batchId, List.of(expectedTerminal)).get(0);
        } catch (DurableMessageBatchIntegrityException integrityFailure) {
            throw new IllegalStateException();
        }
    }

    private PersistedMessageOccurrence readTerminal(
            String sessionId,
            String batchId,
            PersistedMessageCodec.PersistedMessage expectedTerminal) {
        try {
            return messageWriter.readExactBatch(
                    sessionId, batchId, List.of(expectedTerminal)).get(0);
        } catch (DurableMessageBatchIntegrityException integrityFailure) {
            throw new IllegalStateException();
        }
    }

    private void requireCurrentFrontier(String sessionId, DurableFrontier expected) {
        DurableFrontier actual = messageRepository.findTopBySessionIdOrderBySeqNoDesc(sessionId)
                .map(SessionDurableCompletionReconciler::frontierOf)
                .orElse(DurableFrontier.EMPTY);
        if (!expected.equals(actual)) {
            throw new IllegalStateException();
        }
    }

    private void requirePredecessor(
            String sessionId,
            PersistedMessageOccurrence terminal,
            DurableFrontier expected) {
        if (terminal.seqNo() == 0L) {
            if (!DurableFrontier.EMPTY.equals(expected)) throw new IllegalStateException();
            return;
        }
        SessionMessageEntity predecessor = messageRepository
                .findTopBySessionIdAndSeqNoLessThanOrderBySeqNoDesc(sessionId, terminal.seqNo())
                .orElseThrow(IllegalStateException::new);
        if (!expected.equals(frontierOf(predecessor))
                || predecessor.getSeqNo() + 1L != terminal.seqNo()) {
            throw new IllegalStateException();
        }
    }

    private void rejectBlockingAttempt(String sessionId) {
        for (SessionToolAttemptEntity attempt : attemptRepository
                .findBySessionIdAndStateIn(sessionId, ALL_ATTEMPT_STATES)) {
            if (OPEN_ATTEMPT_STATES.contains(attempt.getState())
                    || (TERMINAL_ATTEMPT_STATES.contains(attempt.getState())
                    && !ARCHIVE_READY_STATES.contains(attempt.getArchivePreparationState()))) {
                throw new IllegalStateException();
            }
        }
    }

    private static void validatePlainTerminalAssistant(Message message) {
        if (message.getRole() != Message.Role.ASSISTANT || message.getContent() == null
                || !message.getToolUseBlocks().isEmpty()) {
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

    private static void validateLiveScope(
            SessionEntity session,
            LoopDurabilityScope scope,
            Instant databaseNow) {
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

    private static void validateRetryScope(
            SessionEntity session,
            LoopDurabilityScope scope) {
        boolean sameLiveScope = scope.loopId().equals(session.getActiveLoopId())
                && session.getLoopFence() == scope.loopFence()
                && scope.ownerInstanceId().equals(session.getLoopOwnerInstanceId());
        boolean completedScope = session.getActiveLoopId() == null
                && session.getLoopOwnerInstanceId() == null
                && session.getLoopLeaseUntil() == null
                && session.getLoopFence() == scope.loopFence()
                && "idle".equals(session.getRuntimeStatus());
        if (!Objects.equals(session.getUserId(), scope.userId())
                || session.getHistoryEpoch() != scope.historyEpoch()
                || session.isRestorePreparing()
                || (!sameLiveScope && !completedScope)) {
            throw new IllegalStateException();
        }
    }

    private static void completeAndRelease(SessionEntity session) {
        session.setCompletedAt(Instant.now());
        session.setRuntimeStatus("idle");
        session.setRuntimeStep(null);
        RuntimeFailureState.clear(session);
        session.setActiveLoopId(null);
        session.setLoopOwnerInstanceId(null);
        session.setLoopLeaseUntil(null);
    }

    private static void applyUsageOnce(
            SessionEntity session,
            boolean acknowledgementRetry,
            long inputTokens,
            long outputTokens) {
        if (acknowledgementRetry) return;
        session.setTotalInputTokens(Math.addExact(
                session.getTotalInputTokens(), inputTokens));
        session.setTotalOutputTokens(Math.addExact(
                session.getTotalOutputTokens(), outputTokens));
    }

    private static DurableFrontier frontierOf(SessionMessageEntity row) {
        if (row.getId() == null) throw new IllegalStateException();
        return new DurableFrontier(row.getId(), row.getSeqNo());
    }

    private static DurableFrontier frontierOf(PersistedMessageOccurrence occurrence) {
        return new DurableFrontier(occurrence.messageId(), occurrence.seqNo());
    }

    public record CompletionAck(
            UUID completionBatchId,
            DurableFrontier preCompletionFrontier,
            DurableFrontier postCompletionFrontier,
            PersistedMessageOccurrence terminalAssistant,
            boolean continuationRequired,
            Instant continuationLeaseUntil) {

        public CompletionAck(
                UUID completionBatchId,
                DurableFrontier preCompletionFrontier,
                DurableFrontier postCompletionFrontier,
                PersistedMessageOccurrence terminalAssistant) {
            this(completionBatchId, preCompletionFrontier, postCompletionFrontier,
                    terminalAssistant, false, null);
        }

        public CompletionAck {
            Objects.requireNonNull(completionBatchId, "completionBatchId");
            Objects.requireNonNull(preCompletionFrontier, "preCompletionFrontier");
            Objects.requireNonNull(postCompletionFrontier, "postCompletionFrontier");
            Objects.requireNonNull(terminalAssistant, "terminalAssistant");
            if (continuationRequired != (continuationLeaseUntil != null)) {
                throw new IllegalArgumentException(
                        "continuation must retain its exact live lease");
            }
        }
    }
}
