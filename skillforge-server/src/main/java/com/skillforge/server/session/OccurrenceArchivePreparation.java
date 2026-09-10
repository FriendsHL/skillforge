package com.skillforge.server.session;

import com.skillforge.core.engine.durability.ArchivePreparationAck;
import com.skillforge.core.engine.durability.ArchivePreparationCommand;
import com.skillforge.core.engine.durability.ArchivePreparationState;
import com.skillforge.core.engine.durability.LoopDurabilityScope;
import com.skillforge.core.engine.durability.PersistedBlockOccurrence;
import com.skillforge.core.model.Message;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionMessageEntity;
import com.skillforge.server.entity.SessionToolAttemptEntity;
import com.skillforge.server.repository.SessionMessageRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SessionToolAttemptRepository;
import com.skillforge.server.session.persistence.PersistedMessageCodec;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded, idempotent continuation gate for occurrence archive preparation.
 *
 * <p>Each preparation attempt owns one database transaction and revalidates the exact committed
 * result carriers under Session-then-attempt locks. Three storage failures produce a durable
 * {@code RAW_FALLBACK}; protocol/identity failures never degrade into fallback.
 */
@Service
public class OccurrenceArchivePreparation {

    static final int MAX_ATTEMPTS = 3;

    private final SessionRepository sessionRepository;
    private final SessionMessageRepository messageRepository;
    private final SessionToolAttemptRepository attemptRepository;
    private final PersistedMessageCodec messageCodec;
    private final ToolResultOccurrenceArchiveWriter archiveWriter;
    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;

    public OccurrenceArchivePreparation(
            SessionRepository sessionRepository,
            SessionMessageRepository messageRepository,
            SessionToolAttemptRepository attemptRepository,
            PersistedMessageCodec messageCodec,
            ToolResultOccurrenceArchiveWriter archiveWriter,
            EntityManager entityManager,
            PlatformTransactionManager transactionManager) {
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository");
        this.messageRepository = Objects.requireNonNull(messageRepository, "messageRepository");
        this.attemptRepository = Objects.requireNonNull(attemptRepository, "attemptRepository");
        this.messageCodec = Objects.requireNonNull(messageCodec, "messageCodec");
        this.archiveWriter = Objects.requireNonNull(archiveWriter, "archiveWriter");
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    public ArchivePreparationAck ensurePrepared(ArchivePreparationCommand command) {
        Objects.requireNonNull(command, "command");
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                ArchivePreparationAck acknowledgement = transactionTemplate.execute(
                        ignored -> prepareInTransaction(command));
                return Objects.requireNonNull(acknowledgement, "archive preparation acknowledgement");
            } catch (ArchivePreparationRejectedException protocolFailure) {
                throw protocolFailure;
            } catch (ArchivePreparationIntegrityException integrityFailure) {
                throw rejected();
            } catch (RuntimeException failure) {
                if (!DurableRecoveryRetryableException.isInfrastructureTransient(failure)) {
                    throw rejected();
                }
                // Retry the complete transaction with the same exact occurrence identities.
            }
        }
        try {
            ArchivePreparationAck fallback = transactionTemplate.execute(
                    ignored -> markRawFallbackInTransaction(command));
            return Objects.requireNonNull(fallback, "raw fallback acknowledgement");
        } catch (ArchivePreparationRejectedException protocolFailure) {
            throw protocolFailure;
        } catch (RuntimeException persistenceFailure) {
            if (DurableRecoveryRetryableException.isInfrastructureTransient(
                    persistenceFailure)) {
                throw new DurableRecoveryRetryableException();
            }
            throw new IllegalStateException("Tool result archive fallback persistence failed");
        }
    }

    /**
     * Holds the Session row lock across the final result-event send so a recovery takeover cannot
     * occur between authority validation and visibility. The callback must only publish already
     * committed occurrences; it must never perform Tool work.
     */
    public void withResultVisibilityAuthority(
            ArchivePreparationCommand command,
            Runnable visibilityAction) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(visibilityAction, "visibilityAction");
        try {
            transactionTemplate.executeWithoutResult(ignored -> {
                LockedPreparation locked = lockAndVerify(command);
                if (!("PREPARED".equals(locked.attempt().getArchivePreparationState())
                        || "RAW_FALLBACK".equals(
                                locked.attempt().getArchivePreparationState()))) {
                    throw rejected();
                }
                visibilityAction.run();
            });
        } catch (ArchivePreparationRejectedException protocolFailure) {
            throw protocolFailure;
        } catch (RuntimeException visibilityOrPersistenceFailure) {
            if (DurableRecoveryRetryableException.isInfrastructureTransient(
                    visibilityOrPersistenceFailure)) {
                throw new DurableRecoveryRetryableException();
            }
            throw new IllegalStateException("Tool result visibility authority failed");
        }
    }

    /** Reconstructs committed occurrence identity after restart, then runs the same gate. */
    public ArchivePreparationAck ensurePreparedForRecovery(
            LoopDurabilityScope coordinatorScope,
            long attemptId) {
        return ensurePreparedForRecoveryWithCommand(coordinatorScope, attemptId).acknowledgement();
    }

    /**
     * Recovery variant that retains the verified command needed by the post-commit visibility
     * fence. Losing this command would allow Provider continuation without a way to authorize
     * replay of the committed result event.
     */
    public RecoveredArchive ensurePreparedForRecoveryWithCommand(
            LoopDurabilityScope coordinatorScope,
            long attemptId) {
        ArchivePreparationCommand command;
        try {
            command = transactionTemplate.execute(
                    ignored -> recoveryCommandLocked(coordinatorScope, attemptId));
        } catch (RuntimeException persistenceOrProtocolFailure) {
            if (DurableRecoveryRetryableException.isInfrastructureTransient(
                    persistenceOrProtocolFailure)) {
                throw new DurableRecoveryRetryableException();
            }
            throw rejected();
        }
        ArchivePreparationCommand exactCommand = Objects.requireNonNull(
                command, "recovery archive command");
        return new RecoveredArchive(exactCommand, ensurePrepared(exactCommand));
    }

    /**
     * Reconstructs the committed unknown-result vector under the winning post-action scope.
     * The caller publishes that immutable vector and then invokes {@link #ensurePrepared}; no
     * broadcaster or Provider callback runs while this read transaction is open.
     */
    public ArchivePreparationCommand loadResolvedUnknownPostActionArchive(
            LoopDurabilityScope coordinatorScope,
            SessionRunCoordinator.ClaimIdentity claim) {
        try {
            ArchivePreparationCommand command = transactionTemplate.execute(
                    ignored -> resolvedUnknownCommandLocked(coordinatorScope, claim));
            return Objects.requireNonNull(command, "resolved unknown archive command");
        } catch (RuntimeException persistenceOrProtocolFailure) {
            if (DurableRecoveryRetryableException.isInfrastructureTransient(
                    persistenceOrProtocolFailure)) {
                throw new DurableRecoveryRetryableException();
            }
            throw rejected();
        }
    }

    public record RecoveredArchive(
            ArchivePreparationCommand command,
            ArchivePreparationAck acknowledgement) {

        public RecoveredArchive {
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(acknowledgement, "acknowledgement");
            if (command.attemptId() != acknowledgement.attemptId()
                    || !command.stepId().equals(acknowledgement.stepId())
                    || !command.resultBatchId().equals(acknowledgement.resultBatchId())
                    || command.executionGeneration()
                            != acknowledgement.executionGeneration()) {
                throw new IllegalArgumentException(
                        "Recovered archive command and acknowledgement do not match");
            }
        }
    }

    private ArchivePreparationCommand recoveryCommandLocked(
            LoopDurabilityScope coordinatorScope,
            long attemptId) {
        Objects.requireNonNull(coordinatorScope, "coordinatorScope");
        SessionEntity session = sessionRepository.findByIdForUpdate(coordinatorScope.sessionId())
                .orElseThrow(OccurrenceArchivePreparation::rejected);
        validateCoordinatorScope(
                session, coordinatorScope, sessionRepository.currentDatabaseTime());
        SessionToolAttemptEntity attempt = attemptRepository.findBySessionIdAndIdForUpdate(
                        coordinatorScope.sessionId(), attemptId)
                .orElseThrow(OccurrenceArchivePreparation::rejected);
        if (!"RESULTS_COMMITTED".equals(attempt.getState())
                || attempt.getExecutionLoopId() == null
                || attempt.getExecutionFence() == null
                || attempt.getExecutionOwnerInstanceId() == null
                || attempt.getResultBatchId() == null
                || attempt.getResultExecutionGeneration() == null
                || attempt.getResultExecutionFence() == null
                || attempt.getExecutionGeneration() != attempt.getResultExecutionGeneration()
                || !Objects.equals(attempt.getExecutionFence(), attempt.getResultExecutionFence())
                || attempt.getArchiveTotalCount() <= 0) {
            throw rejected();
        }
        LoopDurabilityScope executionScope = new LoopDurabilityScope(
                attempt.getSessionId(), session.getUserId(), attempt.getHistoryEpoch(),
                attempt.getExecutionLoopId(), attempt.getExecutionFence(),
                attempt.getExecutionOwnerInstanceId());
        List<PersistedBlockOccurrence> blocks = readExactResultBlocks(
                executionScope, attempt.getResultBatchId(), attempt.getArchiveTotalCount());
        return new ArchivePreparationCommand(
                executionScope, attempt.getId(), attempt.getStepId(), attempt.getResultBatchId(),
                attempt.getResultExecutionGeneration(), attempt.getAssistantPayloadHash(),
                attempt.getManifestHash(), blocks, coordinatorScope);
    }

    private ArchivePreparationCommand resolvedUnknownCommandLocked(
            LoopDurabilityScope coordinatorScope,
            SessionRunCoordinator.ClaimIdentity claim) {
        Objects.requireNonNull(coordinatorScope, "coordinatorScope");
        Objects.requireNonNull(claim, "claim");
        SessionEntity session = sessionRepository.findByIdForUpdate(coordinatorScope.sessionId())
                .orElseThrow(OccurrenceArchivePreparation::rejected);
        validateCoordinatorScope(
                session, coordinatorScope, sessionRepository.currentDatabaseTime());
        SessionToolAttemptEntity attempt = attemptRepository.findBySessionIdAndIdForUpdate(
                        coordinatorScope.sessionId(), claim.attemptId())
                .orElseThrow(OccurrenceArchivePreparation::rejected);
        if (!"RESOLVED_UNKNOWN".equals(attempt.getState())
                || !"CLAIMED".equals(attempt.getPostActionState())
                || attempt.getExecutionLoopId() == null
                || attempt.getExecutionFence() == null
                || attempt.getExecutionOwnerInstanceId() == null
                || attempt.getResultBatchId() == null
                || attempt.getResultExecutionGeneration() == null
                || attempt.getResultExecutionFence() == null
                || attempt.getExecutionGeneration() != attempt.getResultExecutionGeneration()
                || !Objects.equals(attempt.getExecutionFence(),
                        attempt.getResultExecutionFence())
                || attempt.getArchiveTotalCount() <= 0
                || !matchesPostActionClaim(attempt, claim, coordinatorScope)) {
            throw rejected();
        }
        LoopDurabilityScope executionScope = new LoopDurabilityScope(
                attempt.getSessionId(), session.getUserId(), attempt.getHistoryEpoch(),
                attempt.getExecutionLoopId(), attempt.getExecutionFence(),
                attempt.getExecutionOwnerInstanceId());
        List<PersistedBlockOccurrence> blocks = readExactResultBlocks(
                executionScope, attempt.getResultBatchId(), attempt.getArchiveTotalCount());
        return new ArchivePreparationCommand(
                executionScope, attempt.getId(), attempt.getStepId(), attempt.getResultBatchId(),
                attempt.getResultExecutionGeneration(), attempt.getAssistantPayloadHash(),
                attempt.getManifestHash(), blocks, coordinatorScope);
    }

    private ArchivePreparationAck prepareInTransaction(ArchivePreparationCommand command) {
        LockedPreparation locked = lockAndVerify(command);
        String state = locked.attempt().getArchivePreparationState();
        if ("PREPARED".equals(state) || "RAW_FALLBACK".equals(state)) {
            return buildAck(command, locked.session(), locked.attempt());
        }
        if (!"PENDING".equals(state)) throw rejected();

        archiveWriter.prepare(command.resultBlocks());
        locked.attempt().setArchivePreparationState("PREPARED");
        locked.attempt().setArchivePreparedCount(command.resultBlocks().size());
        SessionToolAttemptEntity saved = attemptRepository.saveAndFlush(locked.attempt());
        entityManager.refresh(saved);
        return buildAck(command, locked.session(), saved);
    }

    private ArchivePreparationAck markRawFallbackInTransaction(ArchivePreparationCommand command) {
        LockedPreparation locked = lockAndVerify(command);
        String state = locked.attempt().getArchivePreparationState();
        if ("PREPARED".equals(state) || "RAW_FALLBACK".equals(state)) {
            return buildAck(command, locked.session(), locked.attempt());
        }
        if (!"PENDING".equals(state)) throw rejected();
        locked.attempt().setArchivePreparationState("RAW_FALLBACK");
        locked.attempt().setArchivePreparedCount(0);
        SessionToolAttemptEntity saved = attemptRepository.saveAndFlush(locked.attempt());
        entityManager.refresh(saved);
        return buildAck(command, locked.session(), saved);
    }

    private LockedPreparation lockAndVerify(ArchivePreparationCommand command) {
        LoopDurabilityScope execution = command.executionScope();
        LoopDurabilityScope coordinator = command.coordinatorScope();
        SessionEntity session = sessionRepository.findByIdForUpdate(coordinator.sessionId())
                .orElseThrow(OccurrenceArchivePreparation::rejected);
        validateCoordinatorScope(
                session, coordinator, sessionRepository.currentDatabaseTime());
        SessionToolAttemptEntity attempt = attemptRepository.findBySessionIdAndIdForUpdate(
                        execution.sessionId(), command.attemptId())
                .orElseThrow(OccurrenceArchivePreparation::rejected);
        if (!validPreparationState(attempt, coordinator)
                || !command.stepId().equals(attempt.getStepId())
                || attempt.getHistoryEpoch() != execution.historyEpoch()
                || !execution.loopId().equals(attempt.getExecutionLoopId())
                || !Objects.equals(execution.loopFence(), attempt.getExecutionFence())
                || !execution.ownerInstanceId().equals(attempt.getExecutionOwnerInstanceId())
                || attempt.getExecutionGeneration() != command.executionGeneration()
                || !Objects.equals(command.resultBatchId(), attempt.getResultBatchId())
                || !Objects.equals(command.executionGeneration(),
                        attempt.getResultExecutionGeneration())
                || !Objects.equals(execution.loopFence(), attempt.getResultExecutionFence())
                || !command.assistantPayloadHash().equals(attempt.getAssistantPayloadHash())
                || !command.manifestHash().equals(attempt.getManifestHash())
                || attempt.getArchiveTotalCount() != command.resultBlocks().size()) {
            throw rejected();
        }
        List<PersistedBlockOccurrence> persisted = readExactResultBlocks(command);
        if (!persisted.equals(command.resultBlocks())) throw rejected();
        return new LockedPreparation(session, attempt);
    }

    private static boolean validPreparationState(
            SessionToolAttemptEntity attempt, LoopDurabilityScope coordinator) {
        if ("RESULTS_COMMITTED".equals(attempt.getState())) return true;
        return "RESOLVED_UNKNOWN".equals(attempt.getState())
                && "CLAIMED".equals(attempt.getPostActionState())
                && Objects.equals(attempt.getResultBatchId(),
                        attempt.getPostActionResultBatchId())
                && "CONTINUE_CURRENT_TIMELINE".equals(attempt.getPostActionKind())
                && coordinator.loopId().equals(attempt.getPostActionLoopId())
                && Objects.equals(coordinator.loopFence(), attempt.getPostActionFence());
    }

    private static boolean matchesPostActionClaim(
            SessionToolAttemptEntity attempt,
            SessionRunCoordinator.ClaimIdentity claim,
            LoopDurabilityScope coordinator) {
        return attempt.getId() != null
                && attempt.getId() == claim.attemptId()
                && attempt.getSessionId().equals(claim.sessionId())
                && Objects.equals(attempt.getPostActionResolutionRequestId(),
                        claim.resolutionRequestId())
                && Objects.equals(attempt.getPostActionResultBatchId(),
                        claim.resultBatchId())
                && claim.action()
                        == SessionRunCoordinator.ContinuationAction.CONTINUE_CURRENT_TIMELINE
                && Objects.equals(attempt.getPostActionClaimRequestId(),
                        claim.claimRequestId())
                && Objects.equals(attempt.getPostActionLoopId(), claim.loopId())
                && Objects.equals(attempt.getPostActionFence(), claim.loopFence())
                && claim.loopId().equals(coordinator.loopId())
                && claim.loopFence() == coordinator.loopFence();
    }

    private List<PersistedBlockOccurrence> readExactResultBlocks(
            ArchivePreparationCommand command) {
        return readExactResultBlocks(
                command.executionScope(), command.resultBatchId(), command.resultBlocks().size());
    }

    private List<PersistedBlockOccurrence> readExactResultBlocks(
            LoopDurabilityScope executionScope,
            java.util.UUID resultBatchId,
            int expectedCount) {
        List<SessionMessageEntity> rows = messageRepository
                .findBySessionIdAndWriteBatchIdOrderByWriteBatchOrdinalAsc(
                        executionScope.sessionId(), resultBatchId.toString());
        if (rows.size() != expectedCount) throw rejected();
        List<PersistedBlockOccurrence> occurrences = new ArrayList<>(rows.size());
        for (int ordinal = 0; ordinal < rows.size(); ordinal++) {
            SessionMessageEntity row = rows.get(ordinal);
            if (row.getId() == null || row.getWriteBatchOrdinal() == null
                    || row.getWriteBatchOrdinal() != ordinal
                    || !"user".equals(row.getRole())
                    || !"NORMAL".equals(row.getMsgType())
                    || !"normal".equals(row.getMessageType())
                    || row.getControlId() != null || row.getAnsweredAt() != null) {
                throw rejected();
            }
            PersistedMessageCodec.PersistedMessage persisted = messageCodec.decodeRow(
                    new PersistedMessageCodec.EncodedRow(
                            row.getRole(), row.getContentJson(), row.getReasoningContent(),
                            row.getMsgType(), row.getMessageType(), row.getControlId(),
                            row.getAnsweredAt(), row.getMetadataJson(), row.getTraceId()));
            if (!persisted.metadata().isEmpty()) throw rejected();
            occurrences.add(blockOccurrence(
                    executionScope, resultBatchId, row, persisted.message(), ordinal));
        }
        return List.copyOf(occurrences);
    }

    private static PersistedBlockOccurrence blockOccurrence(
            LoopDurabilityScope executionScope,
            java.util.UUID resultBatchId,
            SessionMessageEntity row,
            Message message,
            int ordinal) {
        if (message.getReasoningContent() != null
                || !(message.getContent() instanceof List<?> blocks) || blocks.size() != 1
                || !(blocks.get(0) instanceof Map<?, ?> rawBlock)) {
            throw rejected();
        }
        Map<String, Object> block = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawBlock.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw rejected();
            block.put(key, entry.getValue());
        }
        if (!"tool_result".equals(block.get("type"))
                || !(block.get("tool_use_id") instanceof String toolUseId)
                || !(block.get("content") instanceof String content)
                || !(block.get("is_error") instanceof Boolean isError)) {
            throw rejected();
        }
        Object rawErrorType = block.get("error_type");
        if (rawErrorType != null && !(rawErrorType instanceof String)) throw rejected();
        return new PersistedBlockOccurrence(
                row.getId(), row.getSeqNo(), executionScope.sessionId(),
                resultBatchId, ordinal, 0, toolUseId, content, isError,
                (String) rawErrorType, row.getTraceId());
    }

    private static void validateCoordinatorScope(
            SessionEntity session,
            LoopDurabilityScope coordinator,
            java.time.Instant databaseNow) {
        if (!Objects.equals(session.getUserId(), coordinator.userId())
                || session.getHistoryEpoch() != coordinator.historyEpoch()
                || session.isRestorePreparing()
                || !coordinator.loopId().equals(session.getActiveLoopId())
                || session.getLoopFence() != coordinator.loopFence()
                || !coordinator.ownerInstanceId().equals(session.getLoopOwnerInstanceId())
                || session.getLoopLeaseUntil() == null
                || !session.getLoopLeaseUntil().isAfter(databaseNow)) {
            throw rejected();
        }
    }

    private static ArchivePreparationAck buildAck(
            ArchivePreparationCommand command,
            SessionEntity session,
            SessionToolAttemptEntity attempt) {
        ArchivePreparationState state;
        try {
            state = ArchivePreparationState.valueOf(attempt.getArchivePreparationState());
        } catch (IllegalArgumentException invalidState) {
            throw rejected();
        }
        LoopDurabilityScope persistedScope = new LoopDurabilityScope(
                attempt.getSessionId(), session.getUserId(), attempt.getHistoryEpoch(),
                attempt.getExecutionLoopId(), attempt.getExecutionFence(),
                attempt.getExecutionOwnerInstanceId());
        return new ArchivePreparationAck(
                attempt.getId(), attempt.getStepId(), attempt.getResultBatchId(),
                persistedScope, attempt.getResultExecutionGeneration(), state,
                attempt.getArchivePreparedCount(), attempt.getArchiveTotalCount(),
                command.resultBlocks());
    }

    private static ArchivePreparationRejectedException rejected() {
        return new ArchivePreparationRejectedException();
    }

    private record LockedPreparation(
            SessionEntity session, SessionToolAttemptEntity attempt) {
    }

    private static final class ArchivePreparationRejectedException extends IllegalStateException {
        private ArchivePreparationRejectedException() {
            super("Tool result archive occurrence is partial or inconsistent");
        }
    }
}
