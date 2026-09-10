package com.skillforge.server.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.core.engine.durability.DurableFrontier;
import com.skillforge.core.engine.durability.DurableToolAttemptState;
import com.skillforge.core.engine.durability.ExecutionClaimAck;
import com.skillforge.core.engine.durability.ExecutionClaimCommand;
import com.skillforge.core.engine.durability.FrozenJson;
import com.skillforge.core.engine.durability.IntentCommitAck;
import com.skillforge.core.engine.durability.IntentCommitCommand;
import com.skillforge.core.engine.durability.LoopDurabilityScope;
import com.skillforge.core.engine.durability.MessageSnapshot;
import com.skillforge.core.engine.durability.PersistedMessageOccurrence;
import com.skillforge.core.engine.durability.PersistedBlockOccurrence;
import com.skillforge.core.engine.durability.ReplaySafety;
import com.skillforge.core.engine.durability.ToolCallIntent;
import com.skillforge.core.engine.durability.ToolCallManifest;
import com.skillforge.core.engine.durability.ToolResultCommitAck;
import com.skillforge.core.engine.durability.ToolResultCommitCommand;
import com.skillforge.core.model.Message;
import com.skillforge.core.model.ToolUseBlock;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionMessageEntity;
import com.skillforge.server.entity.SessionToolAttemptEntity;
import com.skillforge.server.repository.SessionMessageRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SessionToolAttemptRepository;
import com.skillforge.server.session.persistence.DurableMessageBatchIntegrityException;
import com.skillforge.server.session.persistence.PersistedMessageCodec;
import com.skillforge.server.session.persistence.SessionOrderedMessageWriter;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Transaction owner for durable Tool attempt state transitions. */
@Service
public class SessionToolAttemptTransactionService {

    private static final String INTENT_COMMITTED = "INTENT_COMMITTED";
    private static final Set<String> OPEN_STATES = Set.of(
            "INTENT_COMMITTED",
            "EXECUTING",
            "WAITING_USER",
            "UNCERTAIN_PENDING_RESOLUTION");
    private static final Set<String> TERMINAL_STATES = Set.of(
            "RESULTS_COMMITTED", "RESOLVED_UNKNOWN");
    private static final Set<String> ARCHIVE_READY_STATES = Set.of("PREPARED", "RAW_FALLBACK");
    private static final List<String> ALL_ATTEMPT_STATES = List.of(
            "INTENT_COMMITTED",
            "EXECUTING",
            "WAITING_USER",
            "RESULTS_COMMITTED",
            "UNCERTAIN_PENDING_RESOLUTION",
            "RESOLVED_UNKNOWN");
    private static final Set<String> MANIFEST_FIELDS = Set.of(
            "schemaVersion", "calls", "replaySafety");
    private static final Set<String> CALL_FIELDS = Set.of(
            "providerOrdinal", "toolUseId", "toolName", "input", "replaySafety");
    private static final Set<String> RESULT_FIELDS = Set.of(
            "type", "tool_use_id", "content", "is_error");
    private static final Set<String> RESULT_FIELDS_WITH_ERROR_TYPE = Set.of(
            "type", "tool_use_id", "content", "is_error", "error_type");

    private final SessionRepository sessionRepository;
    private final SessionMessageRepository messageRepository;
    private final SessionToolAttemptRepository attemptRepository;
    private final SessionOrderedMessageWriter messageWriter;
    private final PersistedMessageCodec messageCodec;
    private final ObjectMapper objectMapper;
    private final ObjectWriter canonicalManifestWriter;
    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;

    public SessionToolAttemptTransactionService(
            SessionRepository sessionRepository,
            SessionMessageRepository messageRepository,
            SessionToolAttemptRepository attemptRepository,
            SessionOrderedMessageWriter messageWriter,
            PersistedMessageCodec messageCodec,
            ObjectMapper objectMapper,
            EntityManager entityManager,
            PlatformTransactionManager transactionManager) {
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository");
        this.messageRepository = Objects.requireNonNull(messageRepository, "messageRepository");
        this.attemptRepository = Objects.requireNonNull(attemptRepository, "attemptRepository");
        this.messageWriter = Objects.requireNonNull(messageWriter, "messageWriter");
        this.messageCodec = Objects.requireNonNull(messageCodec, "messageCodec");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.canonicalManifestWriter = objectMapper.writer()
                .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    /**
     * Persists an assistant Tool intent and its attempt atomically, before any execution side
     * effect. Lock order is always Session first, then attempt.
     */
    public IntentCommitAck commitIntent(IntentCommitCommand command) {
        try {
            IntentCommitAck acknowledgement = transactionTemplate.execute(
                    ignored -> commitIntentInTransaction(command, false));
            return Objects.requireNonNull(acknowledgement, "intent acknowledgement");
        } catch (DurableIntentRejectedException safeFailure) {
            throw safeFailure;
        } catch (RuntimeException persistenceOrProtocolFailure) {
            // TransactionTemplate completes the actual database commit before returning, so this
            // boundary also catches commit-phase failures. Never attach the original exception:
            // SQL diagnostics and protocol validation errors can contain transcript/tool data.
            throw new IllegalStateException("Durable intent persistence failed");
        }
    }

    /** Interactive participant that also recognizes the exact WAITING_USER ACK-loss retry. */
    IntentCommitAck commitInteractiveIntent(IntentCommitCommand command) {
        try {
            IntentCommitAck acknowledgement = transactionTemplate.execute(
                    ignored -> commitIntentInTransaction(command, true));
            return Objects.requireNonNull(acknowledgement, "interactive intent acknowledgement");
        } catch (DurableIntentRejectedException safeFailure) {
            throw safeFailure;
        } catch (RuntimeException persistenceOrProtocolFailure) {
            throw new IllegalStateException("Durable intent persistence failed");
        }
    }

    /** Claims an intent atomically before Tool execution becomes visible or dispatchable. */
    public ExecutionClaimAck claimExecution(ExecutionClaimCommand command) {
        try {
            ExecutionClaimAck acknowledgement = transactionTemplate.execute(
                    ignored -> claimExecutionInTransaction(command));
            return Objects.requireNonNull(acknowledgement, "execution claim acknowledgement");
        } catch (DurableExecutionClaimRejectedException safeFailure) {
            throw safeFailure;
        } catch (TransientDataAccessException | CannotCreateTransactionException retryable) {
            throw new DurableRecoveryRetryableException();
        } catch (RuntimeException persistenceOrProtocolFailure) {
            // This boundary encloses the real commit, including deferred constraints.
            // Discard the cause because it can contain SQL and Tool payload values.
            throw new IllegalStateException("Durable execution claim persistence failed");
        }
    }

    /** Atomically appends the complete provider-ordered Tool result vector and closes its attempt. */
    public ToolResultCommitAck commitResults(ToolResultCommitCommand command) {
        try {
            ToolResultCommitAck acknowledgement = transactionTemplate.execute(
                    ignored -> commitResultsInTransaction(command, null));
            return Objects.requireNonNull(acknowledgement, "Tool result acknowledgement");
        } catch (DurableToolResultRejectedException safeFailure) {
            throw safeFailure;
        } catch (RuntimeException persistenceOrProtocolFailure) {
            // TransactionTemplate encloses deferred constraints and the real commit. Never expose
            // the original exception because SQL diagnostics may contain Tool result payloads.
            throw new IllegalStateException("Durable Tool result persistence failed");
        }
    }

    /**
     * Interactive variant whose provider-visible result vector follows one filtered control row.
     * The caller owns the surrounding transaction that marks the control answered.
     */
    ToolResultCommitAck commitInteractiveResults(
            ToolResultCommitCommand command, long controlMessageId) {
        if (controlMessageId <= 0L) {
            throw new IllegalArgumentException("controlMessageId must be positive");
        }
        try {
            ToolResultCommitAck acknowledgement = transactionTemplate.execute(
                    ignored -> commitResultsInTransaction(command, controlMessageId));
            return Objects.requireNonNull(acknowledgement, "interactive result acknowledgement");
        } catch (DurableToolResultRejectedException safeFailure) {
            throw safeFailure;
        } catch (RuntimeException persistenceOrProtocolFailure) {
            throw new IllegalStateException("Durable Tool result persistence failed");
        }
    }

    /** Rehydrates the exact persisted assistant intent after a recovery execution claim wins. */
    public IntentCommitAck loadIntentForRecovery(
            LoopDurabilityScope recoveryScope,
            long attemptId) {
        try {
            IntentCommitAck acknowledgement = transactionTemplate.execute(
                    ignored -> loadIntentForRecoveryLocked(
                            recoveryScope, attemptId, DurableToolAttemptState.EXECUTING));
            return Objects.requireNonNull(acknowledgement, "recovered intent acknowledgement");
        } catch (TransientDataAccessException | CannotCreateTransactionException retryable) {
            throw new DurableRecoveryRetryableException();
        } catch (RuntimeException persistenceOrProtocolFailure) {
            throw new IllegalStateException("Durable intent recovery failed");
        }
    }

    /** Rehydrates the exact immutable assistant half of a durable WAITING_USER control. */
    IntentCommitAck loadWaitingIntentForRecovery(
            LoopDurabilityScope recoveryScope,
            long attemptId) {
        try {
            IntentCommitAck acknowledgement = transactionTemplate.execute(
                    ignored -> loadIntentForRecoveryLocked(
                            recoveryScope, attemptId, DurableToolAttemptState.WAITING_USER));
            return Objects.requireNonNull(acknowledgement, "waiting intent acknowledgement");
        } catch (TransientDataAccessException | CannotCreateTransactionException retryable) {
            throw new DurableRecoveryRetryableException();
        } catch (RuntimeException persistenceOrProtocolFailure) {
            throw new IllegalStateException("Durable waiting intent recovery failed");
        }
    }

    /**
     * Rehydrates a pristine WAITING_USER intent while its Session is deliberately parked.
     * The caller must already be inside the interactive-control transaction; this method
     * acquires the same Session-then-attempt locks but never installs execution authority.
     */
    IntentCommitAck loadParkedWaitingIntentLocked(
            String sessionId,
            long expectedUserId,
            long expectedHistoryEpoch,
            long attemptId) {
        SessionEntity session = sessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(SessionToolAttemptTransactionService::claimScopeFailure);
        if (!Objects.equals(session.getUserId(), expectedUserId)
                || session.getHistoryEpoch() != expectedHistoryEpoch
                || session.isRestorePreparing()
                || !"waiting_user".equals(session.getRuntimeStatus())
                || session.getActiveLoopId() != null
                || session.getLoopOwnerInstanceId() != null
                || session.getLoopLeaseUntil() != null) {
            throw claimScopeFailure();
        }
        SessionToolAttemptEntity attempt = attemptRepository.findBySessionIdAndIdForUpdate(
                        sessionId, attemptId)
                .orElseThrow(SessionToolAttemptTransactionService::integrityFailure);
        if (!DurableToolAttemptState.WAITING_USER.name().equals(attempt.getState())
                || attempt.getHistoryEpoch() != expectedHistoryEpoch
                || attempt.getExecutionLoopId() != null
                || attempt.getExecutionFence() != null
                || attempt.getExecutionOwnerInstanceId() != null
                || attempt.getExecutionGeneration() != 0L
                || attempt.getClaimRequestId() != null
                || attempt.getClaimedAt() != null
                || attempt.getExecutionLeaseUntil() != null
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
            throw integrityFailure();
        }
        return rehydrateIntent(sessionId, attempt);
    }

    /** Loads an EXECUTING/RESULTS_COMMITTED interactive intent for result construction/retry. */
    IntentCommitAck loadInteractiveIntentForResult(
            LoopDurabilityScope executionScope,
            long attemptId) {
        try {
            IntentCommitAck acknowledgement = transactionTemplate.execute(
                    ignored -> loadIntentForRecoveryLocked(executionScope, attemptId, null));
            return Objects.requireNonNull(acknowledgement, "interactive intent acknowledgement");
        } catch (TransientDataAccessException | CannotCreateTransactionException retryable) {
            throw new DurableRecoveryRetryableException();
        } catch (RuntimeException persistenceOrProtocolFailure) {
            throw new IllegalStateException("Durable interactive intent recovery failed");
        }
    }

    private IntentCommitAck loadIntentForRecoveryLocked(
            LoopDurabilityScope recoveryScope,
            long attemptId,
            DurableToolAttemptState expectedState) {
        Objects.requireNonNull(recoveryScope, "recoveryScope");
        SessionEntity session = sessionRepository.findByIdForUpdate(recoveryScope.sessionId())
                .orElseThrow(SessionToolAttemptTransactionService::claimScopeFailure);
        validateClaimIdentityScope(session, recoveryScope);
        SessionToolAttemptEntity attempt = attemptRepository.findBySessionIdAndIdForUpdate(
                        recoveryScope.sessionId(), attemptId)
                .orElseThrow(SessionToolAttemptTransactionService::integrityFailure);
        boolean interactiveResultState = expectedState == null
                && (DurableToolAttemptState.EXECUTING.name().equals(attempt.getState())
                    || DurableToolAttemptState.RESULTS_COMMITTED.name().equals(attempt.getState()));
        if ((!interactiveResultState
                && (expectedState == null || !expectedState.name().equals(attempt.getState())))
                || attempt.getHistoryEpoch() != recoveryScope.historyEpoch()) {
            throw integrityFailure();
        }
        if (expectedState != null
                && !DurableToolAttemptState.RESULTS_COMMITTED.name().equals(attempt.getState())) {
            validateClaimLiveLease(session, sessionRepository.currentDatabaseTime());
        }
        if ((expectedState == DurableToolAttemptState.EXECUTING || interactiveResultState)
                && (!recoveryScope.loopId().equals(attempt.getExecutionLoopId())
                    || !Objects.equals(recoveryScope.loopFence(), attempt.getExecutionFence())
                    || !recoveryScope.ownerInstanceId()
                            .equals(attempt.getExecutionOwnerInstanceId())
                    || attempt.getExecutionGeneration() <= 0L)) {
            throw integrityFailure();
        }
        if (expectedState == DurableToolAttemptState.WAITING_USER
                && (attempt.getExecutionLoopId() != null
                    || attempt.getExecutionFence() != null
                    || attempt.getExecutionOwnerInstanceId() != null
                    || attempt.getExecutionGeneration() != 0L
                    || attempt.getClaimRequestId() != null
                    || attempt.getClaimedAt() != null
                    || attempt.getExecutionLeaseUntil() != null)) {
            throw integrityFailure();
        }
        return rehydrateIntent(recoveryScope.sessionId(), attempt);
    }

    private IntentCommitAck rehydrateIntent(
            String sessionId, SessionToolAttemptEntity attempt) {
        SessionMessageEntity row = messageRepository.findById(attempt.getAssistantMessageId())
                .filter(value -> sessionId.equals(value.getSessionId()))
                .orElseThrow(SessionToolAttemptTransactionService::integrityFailure);
        if (row.getId() == null || row.getWriteBatchId() == null
                || row.getWriteBatchOrdinal() == null || row.getWriteBatchOrdinal() != 0
                || !"assistant".equals(row.getRole())) {
            throw integrityFailure();
        }
        PersistedMessageCodec.PersistedMessage decoded = messageCodec.decodeRow(
                new PersistedMessageCodec.EncodedRow(
                        row.getRole(), row.getContentJson(), row.getReasoningContent(),
                        row.getMsgType(), row.getMessageType(), row.getControlId(),
                        row.getAnsweredAt(), row.getMetadataJson(), row.getTraceId()));
        if (!decoded.metadata().isEmpty()
                || !attempt.getAssistantPayloadHash().equals(
                        sha256(messageCodec.writeMessage(decoded.message())))) {
            throw integrityFailure();
        }
        ToolCallManifest manifest = decodeCanonicalManifest(attempt.getManifestJson());
        if (!attempt.getManifestJson().equals(encodeManifest(manifest))
                || !attempt.getManifestHash().equals(sha256(attempt.getManifestJson()))) {
            throw integrityFailure();
        }
        validateAssistantManifest(MessageSnapshot.capture(decoded.message()), manifest);
        PersistedMessageOccurrence assistant = new PersistedMessageOccurrence(
                row.getId(), row.getSeqNo(), row.getWriteBatchId(), row.getWriteBatchOrdinal(),
                MessageSnapshot.capture(decoded.message()), row.getMsgType(), row.getMessageType(),
                row.getControlId(), row.getAnsweredAt(), decoded.metadata(), row.getTraceId());
        return new IntentCommitAck(
                attempt.getId(), attempt.getStepId(), assistant,
                new DurableFrontier(
                        attempt.getPreIntentMaxMessageId(), attempt.getPreIntentMaxSeq()),
                manifest, attempt.getAssistantPayloadHash(), attempt.getManifestHash(),
                ReplaySafety.valueOf(attempt.getReplaySafety()));
    }

    private ToolResultCommitAck commitResultsInTransaction(
            ToolResultCommitCommand command, Long expectedControlMessageId) {
        Objects.requireNonNull(command, "command");
        LoopDurabilityScope executor = command.executionScope();
        SessionEntity session = sessionRepository.findByIdForUpdate(executor.sessionId())
                .orElseThrow(SessionToolAttemptTransactionService::resultScopeFailure);
        Instant databaseNow = sessionRepository.currentDatabaseTime();
        validateResultIdentityScope(session, executor);

        SessionToolAttemptEntity attempt = attemptRepository.findBySessionIdAndIdForUpdate(
                        executor.sessionId(), command.attemptId())
                .orElseThrow(SessionToolAttemptTransactionService::resultIntegrityFailure);
        boolean committedRetry = "RESULTS_COMMITTED".equals(attempt.getState());
        if (!committedRetry) {
            validateResultLiveLease(session, databaseNow);
        }
        ToolCallManifest manifest = verifyResultIdentity(
                command, attempt, databaseNow, !committedRetry);
        List<PersistedMessageCodec.PersistedMessage> expectedResults =
                buildExpectedResults(command, manifest);
        String batchId = command.resultBatchId().toString();
        List<SessionMessageEntity> existingBatch = messageRepository
                .findBySessionIdAndWriteBatchIdOrderByWriteBatchOrdinalAsc(
                        executor.sessionId(), batchId);

        if ("RESULTS_COMMITTED".equals(attempt.getState())) {
            if (existingBatch.isEmpty()) throw resultIntegrityFailure();
            return verifyResultRetryAndBuildAck(
                    command, attempt, session, expectedResults, expectedControlMessageId);
        }
        if (!"EXECUTING".equals(attempt.getState()) || !existingBatch.isEmpty()
                || attempt.getResultBatchId() != null
                || attempt.getResultExecutionGeneration() != null
                || attempt.getResultExecutionFence() != null
                || !"NOT_STARTED".equals(attempt.getArchivePreparationState())
                || attempt.getArchivePreparedCount() != 0
                || attempt.getArchiveTotalCount() != 0) {
            throw resultIntegrityFailure();
        }

        DurableFrontier preResultFrontier = currentFrontier(executor.sessionId());
        if (!command.expectedPreResultFrontier().equals(preResultFrontier)) {
            throw resultScopeFailure();
        }
        verifyAssistantFrontier(
                command, attempt, preResultFrontier, expectedControlMessageId);

        List<PersistedMessageOccurrence> persistedResults;
        try {
            persistedResults = messageWriter.appendNewBatchLocked(
                    executor.sessionId(), batchId, expectedResults);
        } catch (DurableMessageBatchIntegrityException batchIntegrityFailure) {
            throw resultIntegrityFailure();
        }

        attempt.setState("RESULTS_COMMITTED");
        attempt.setResultBatchId(command.resultBatchId());
        attempt.setResultExecutionGeneration(command.executionGeneration());
        attempt.setResultExecutionFence(executor.loopFence());
        attempt.setArchivePreparationState("PENDING");
        attempt.setArchivePreparedCount(0);
        attempt.setArchiveTotalCount(persistedResults.size());
        session.setMessageCount(Math.toIntExact(messageRepository.countBySessionId(executor.sessionId())));
        SessionToolAttemptEntity saved = attemptRepository.saveAndFlush(attempt);
        entityManager.refresh(saved);
        return verifyResultRetryAndBuildAck(
                command, saved, session, expectedResults, expectedControlMessageId);
    }

    private ToolCallManifest verifyResultIdentity(
            ToolResultCommitCommand command,
            SessionToolAttemptEntity attempt,
            Instant databaseNow,
            boolean requireLiveExecutionLease) {
        LoopDurabilityScope executor = command.executionScope();
        if (!command.stepId().equals(attempt.getStepId())
                || attempt.getHistoryEpoch() != executor.historyEpoch()
                || !executor.loopId().equals(attempt.getExecutionLoopId())
                || !Objects.equals(executor.loopFence(), attempt.getExecutionFence())
                || !executor.ownerInstanceId().equals(attempt.getExecutionOwnerInstanceId())
                || attempt.getExecutionGeneration() != command.executionGeneration()
                || !command.claimRequestId().equals(attempt.getClaimRequestId())
                || (requireLiveExecutionLease && (attempt.getExecutionLeaseUntil() == null
                    || !attempt.getExecutionLeaseUntil().isAfter(databaseNow)))
                || !command.assistantPayloadHash().equals(attempt.getAssistantPayloadHash())
                || !command.manifestHash().equals(attempt.getManifestHash())
                || !attempt.getManifestHash().equals(sha256(attempt.getManifestJson()))) {
            throw resultIntegrityFailure();
        }
        ToolCallManifest manifest = decodeCanonicalManifest(attempt.getManifestJson());
        if (!attempt.getManifestJson().equals(encodeManifest(manifest))) {
            throw resultIntegrityFailure();
        }
        return manifest;
    }

    private List<PersistedMessageCodec.PersistedMessage> buildExpectedResults(
            ToolResultCommitCommand command, ToolCallManifest manifest) {
        if (command.results().size() != manifest.calls().size()) {
            throw resultIntegrityFailure();
        }
        List<PersistedMessageCodec.PersistedMessage> expected =
                new ArrayList<>(manifest.calls().size());
        for (int ordinal = 0; ordinal < manifest.calls().size(); ordinal++) {
            Message message = command.results().get(ordinal).toMessage();
            validateResultMessage(message, manifest.calls().get(ordinal));
            expected.add(new PersistedMessageCodec.PersistedMessage(
                    message,
                    "NORMAL",
                    "normal",
                    null,
                    null,
                    Collections.emptyMap(),
                    command.traceId()));
        }
        return List.copyOf(expected);
    }

    private static void validateResultMessage(Message result, ToolCallIntent call) {
        if (result.getRole() != Message.Role.USER || result.getReasoningContent() != null
                || !(result.getContent() instanceof List<?> blocks) || blocks.size() != 1
                || !(blocks.get(0) instanceof Map<?, ?> rawBlock)) {
            throw resultIntegrityFailure();
        }
        Map<String, Object> block = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawBlock.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw resultIntegrityFailure();
            block.put(key, entry.getValue());
        }
        Set<String> fields = block.keySet();
        if ((!fields.equals(RESULT_FIELDS) && !fields.equals(RESULT_FIELDS_WITH_ERROR_TYPE))
                || !"tool_result".equals(block.get("type"))
                || !call.toolUseId().equals(block.get("tool_use_id"))
                || !(block.get("content") instanceof String)
                || !(block.get("is_error") instanceof Boolean isError)) {
            throw resultIntegrityFailure();
        }
        if (fields.contains("error_type")
                && (!isError || !(block.get("error_type") instanceof String errorType)
                    || errorType.isBlank())) {
            throw resultIntegrityFailure();
        }
    }

    private void verifyAssistantFrontier(
            ToolResultCommitCommand command,
            SessionToolAttemptEntity attempt,
            DurableFrontier preResultFrontier,
            Long expectedControlMessageId) {
        SessionMessageEntity assistant = messageRepository.findById(attempt.getAssistantMessageId())
                .filter(row -> attempt.getSessionId().equals(row.getSessionId()))
                .orElseThrow(SessionToolAttemptTransactionService::resultIntegrityFailure);
        if (assistant.getId() == null
                || !"assistant".equals(assistant.getRole())
                || !attempt.getAssistantPayloadHash().equals(
                        sha256(messageCodec.writeMessage(decodeMessage(assistant))))) {
            throw resultIntegrityFailure();
        }
        if (expectedControlMessageId == null) {
            if (assistant.getId() != preResultFrontier.maxMessageId()
                    || assistant.getSeqNo() != preResultFrontier.maxSeq()) {
                throw resultIntegrityFailure();
            }
            return;
        }
        SessionMessageEntity control = messageRepository.findById(expectedControlMessageId)
                .filter(row -> attempt.getSessionId().equals(row.getSessionId()))
                .orElseThrow(SessionToolAttemptTransactionService::resultIntegrityFailure);
        if (control.getId() != preResultFrontier.maxMessageId()
                || control.getSeqNo() != preResultFrontier.maxSeq()
                || control.getSeqNo() != assistant.getSeqNo() + 1L
                || !"assistant".equals(control.getRole())
                || !"SYSTEM_EVENT".equals(control.getMsgType())
                || control.getControlId() == null
                || control.getAnsweredAt() == null) {
            throw resultIntegrityFailure();
        }
    }

    private Message decodeMessage(SessionMessageEntity row) {
        PersistedMessageCodec.PersistedMessage decoded = messageCodec.decodeRow(
                new PersistedMessageCodec.EncodedRow(
                        row.getRole(),
                        row.getContentJson(),
                        row.getReasoningContent(),
                        row.getMsgType(),
                        row.getMessageType(),
                        row.getControlId(),
                        row.getAnsweredAt(),
                        row.getMetadataJson(),
                        row.getTraceId()));
        return decoded.message();
    }

    private ToolResultCommitAck verifyResultRetryAndBuildAck(
            ToolResultCommitCommand command,
            SessionToolAttemptEntity attempt,
            SessionEntity session,
            List<PersistedMessageCodec.PersistedMessage> expectedResults,
            Long expectedControlMessageId) {
        LoopDurabilityScope executor = command.executionScope();
        if (!"RESULTS_COMMITTED".equals(attempt.getState())
                || !command.resultBatchId().equals(attempt.getResultBatchId())
                || !Objects.equals(command.executionGeneration(),
                        attempt.getResultExecutionGeneration())
                || !Objects.equals(executor.loopFence(), attempt.getResultExecutionFence())
                || !"PENDING".equals(attempt.getArchivePreparationState())
                || attempt.getArchivePreparedCount() != 0
                || attempt.getArchiveTotalCount() != expectedResults.size()) {
            throw resultIntegrityFailure();
        }
        List<PersistedMessageOccurrence> results;
        try {
            results = messageWriter.readExactBatch(
                    executor.sessionId(), command.resultBatchId().toString(), expectedResults);
        } catch (DurableMessageBatchIntegrityException batchIntegrityFailure) {
            throw resultIntegrityFailure();
        }
        PersistedMessageOccurrence first = results.get(0);
        if (first.seqNo() <= 0L) throw resultIntegrityFailure();
        SessionMessageEntity previous = messageRepository
                .findTopBySessionIdAndSeqNoLessThanOrderBySeqNoDesc(
                        executor.sessionId(), first.seqNo())
                .orElseThrow(SessionToolAttemptTransactionService::resultIntegrityFailure);
        DurableFrontier preResultFrontier = frontierOf(previous);
        if (!command.expectedPreResultFrontier().equals(preResultFrontier)
                || (expectedControlMessageId == null
                    && !Objects.equals(attempt.getAssistantMessageId(), previous.getId()))
                || (expectedControlMessageId != null
                    && !Objects.equals(expectedControlMessageId, previous.getId()))
                || previous.getSeqNo() + 1L != first.seqNo()) {
            throw resultIntegrityFailure();
        }
        PersistedMessageOccurrence last = results.get(results.size() - 1);
        List<PersistedBlockOccurrence> resultBlocks = results.stream()
                .map(result -> persistedBlockOccurrence(
                        executor.sessionId(), command.resultBatchId(), result))
                .toList();
        LoopDurabilityScope persistedScope = new LoopDurabilityScope(
                attempt.getSessionId(),
                session.getUserId(),
                attempt.getHistoryEpoch(),
                attempt.getExecutionLoopId(),
                attempt.getExecutionFence(),
                attempt.getExecutionOwnerInstanceId());
        return new ToolResultCommitAck(
                attempt.getId(),
                attempt.getStepId(),
                attempt.getResultBatchId(),
                persistedScope,
                attempt.getResultExecutionGeneration(),
                results,
                resultBlocks,
                preResultFrontier,
                new DurableFrontier(last.messageId(), last.seqNo()),
                attempt.getAssistantPayloadHash(),
                attempt.getManifestHash());
    }

    private static PersistedBlockOccurrence persistedBlockOccurrence(
            String sessionId, UUID resultBatchId, PersistedMessageOccurrence result) {
        Message message = result.message().toMessage();
        if (!(message.getContent() instanceof List<?> blocks) || blocks.size() != 1
                || !(blocks.get(0) instanceof Map<?, ?> block)
                || !(block.get("tool_use_id") instanceof String toolUseId)
                || !(block.get("content") instanceof String content)
                || !(block.get("is_error") instanceof Boolean isError)) {
            throw resultIntegrityFailure();
        }
        Object rawErrorType = block.get("error_type");
        if (rawErrorType != null && !(rawErrorType instanceof String)) {
            throw resultIntegrityFailure();
        }
        return new PersistedBlockOccurrence(
                result.messageId(),
                result.seqNo(),
                sessionId,
                resultBatchId,
                result.writeBatchOrdinal(),
                0,
                toolUseId,
                content,
                isError,
                (String) rawErrorType,
                result.traceId());
    }

    private ExecutionClaimAck claimExecutionInTransaction(ExecutionClaimCommand command) {
        Objects.requireNonNull(command, "command");
        LoopDurabilityScope claimant = command.claimant();
        if ((command.expectedState() != DurableToolAttemptState.INTENT_COMMITTED
                && command.expectedState() != DurableToolAttemptState.WAITING_USER
                && command.expectedState() != DurableToolAttemptState.EXECUTING)
                || command.expectedGeneration() < 0L) {
            throw claimConflict();
        }

        SessionEntity session = sessionRepository.findByIdForUpdate(claimant.sessionId())
                .orElseThrow(SessionToolAttemptTransactionService::claimScopeFailure);
        Instant databaseNow = sessionRepository.currentDatabaseTime();
        validateClaimIdentityScope(session, claimant);

        SessionToolAttemptEntity attempt = attemptRepository.findBySessionIdAndIdForUpdate(
                        claimant.sessionId(), command.attemptId())
                .orElseThrow(SessionToolAttemptTransactionService::claimConflict);
        if (!command.stepId().equals(attempt.getStepId())
                || attempt.getHistoryEpoch() != claimant.historyEpoch()) {
            throw claimConflict();
        }
        if (isExactClaimRetry(command, attempt)) {
            // An idempotent retry may recover the original ACK only while that ACK still
            // represents live execution authority. Returning an expired ACK would let a
            // delayed caller dispatch after another instance has legitimately fenced/taken
            // over the attempt.
            validateClaimLiveLease(session, databaseNow);
            if (!attempt.getExecutionLeaseUntil().isAfter(databaseNow)) {
                throw claimScopeFailure();
            }
            return buildExecutionClaimAck(attempt, session);
        }
        validateClaimLiveLease(session, databaseNow);
        if (!command.expectedState().name().equals(attempt.getState())
                || attempt.getExecutionGeneration() != command.expectedGeneration()
                || attempt.getResultBatchId() != null
                || attempt.getResultExecutionGeneration() != null
                || attempt.getResultExecutionFence() != null) {
            throw claimConflict();
        }
        DurableToolAttemptState claimedState = DurableToolAttemptState.EXECUTING;
        if (command.expectedState() == DurableToolAttemptState.INTENT_COMMITTED
                || command.expectedState() == DurableToolAttemptState.WAITING_USER) {
            boolean pristineIntent = attempt.getExecutionLoopId() == null
                    && attempt.getExecutionFence() == null
                    && attempt.getExecutionOwnerInstanceId() == null
                    && attempt.getClaimRequestId() == null
                    && attempt.getClaimedAt() == null
                    && attempt.getExecutionLeaseUntil() == null;
            boolean originOwner = claimant.loopId().equals(attempt.getOriginLoopId())
                    && claimant.loopFence() == attempt.getOriginFence();
            boolean takeover = claimant.loopFence() > attempt.getOriginFence();
            if (!pristineIntent || (!originOwner && !takeover)) {
                throw claimConflict();
            }
            // INTENT_COMMITTED and WAITING_USER have never held execution authority, so even a
            // mutating descriptor is safe to dispatch after a fenced takeover. Replay safety only
            // decides recovery once a prior EXECUTING generation may have produced effects.
        } else {
            boolean completePriorExecution = attempt.getExecutionLoopId() != null
                    && attempt.getExecutionFence() != null
                    && attempt.getExecutionOwnerInstanceId() != null
                    && attempt.getClaimRequestId() != null
                    && attempt.getClaimedAt() != null
                    && attempt.getExecutionLeaseUntil() != null;
            boolean expiredTakeover = completePriorExecution
                    && !attempt.getExecutionLeaseUntil().isAfter(databaseNow)
                    && claimant.loopFence() > attempt.getExecutionFence();
            if (!expiredTakeover) {
                throw claimConflict();
            }
            if (!isReplayable(attempt)) {
                claimedState = DurableToolAttemptState.UNCERTAIN_PENDING_RESOLUTION;
            }
        }

        attempt.setState(claimedState.name());
        attempt.setExecutionLoopId(claimant.loopId());
        attempt.setExecutionFence(claimant.loopFence());
        attempt.setExecutionOwnerInstanceId(claimant.ownerInstanceId());
        attempt.setExecutionGeneration(command.expectedGeneration() + 1L);
        attempt.setClaimRequestId(command.claimRequestId());
        attempt.setClaimedAt(databaseNow);
        attempt.setExecutionLeaseUntil(session.getLoopLeaseUntil());
        SessionToolAttemptEntity saved = attemptRepository.saveAndFlush(attempt);
        entityManager.refresh(saved);

        if (!claimedState.name().equals(saved.getState())
                || saved.getExecutionGeneration() != command.expectedGeneration() + 1L
                || !command.claimRequestId().equals(saved.getClaimRequestId())
                || saved.getClaimedAt() == null
                || saved.getExecutionLeaseUntil() == null
                || !saved.getExecutionLeaseUntil().isAfter(saved.getClaimedAt())) {
            throw claimConflict();
        }
        return buildExecutionClaimAck(saved, session);
    }

    private static boolean isExactClaimRetry(
            ExecutionClaimCommand command, SessionToolAttemptEntity attempt) {
        LoopDurabilityScope claimant = command.claimant();
        boolean acknowledgedState = DurableToolAttemptState.EXECUTING.name().equals(attempt.getState())
                || DurableToolAttemptState.UNCERTAIN_PENDING_RESOLUTION.name()
                        .equals(attempt.getState());
        return acknowledgedState
                && attempt.getExecutionGeneration() == command.expectedGeneration() + 1L
                && command.claimRequestId().equals(attempt.getClaimRequestId())
                && claimant.loopId().equals(attempt.getExecutionLoopId())
                && Objects.equals(claimant.loopFence(), attempt.getExecutionFence())
                && claimant.ownerInstanceId().equals(attempt.getExecutionOwnerInstanceId())
                && attempt.getClaimedAt() != null
                && attempt.getExecutionLeaseUntil() != null
                && attempt.getExecutionLeaseUntil().isAfter(attempt.getClaimedAt())
                && attempt.getResultBatchId() == null
                && attempt.getResultExecutionGeneration() == null
                && attempt.getResultExecutionFence() == null;
    }

    private static boolean isReplayable(SessionToolAttemptEntity attempt) {
        return ReplaySafety.READ_ONLY_REPLAYABLE.name().equals(attempt.getReplaySafety())
                || ReplaySafety.IDEMPOTENT_KEYED.name().equals(attempt.getReplaySafety());
    }

    private static ExecutionClaimAck buildExecutionClaimAck(
            SessionToolAttemptEntity attempt, SessionEntity session) {
        LoopDurabilityScope executionScope = new LoopDurabilityScope(
                attempt.getSessionId(),
                session.getUserId(),
                attempt.getHistoryEpoch(),
                attempt.getExecutionLoopId(),
                attempt.getExecutionFence(),
                attempt.getExecutionOwnerInstanceId());
        return new ExecutionClaimAck(
                attempt.getId(),
                attempt.getStepId(),
                attempt.getClaimRequestId(),
                DurableToolAttemptState.valueOf(attempt.getState()),
                executionScope,
                attempt.getExecutionGeneration(),
                attempt.getClaimedAt(),
                attempt.getExecutionLeaseUntil());
    }

    private IntentCommitAck commitIntentInTransaction(
            IntentCommitCommand command, boolean allowWaitingInteractiveRetry) {
        Objects.requireNonNull(command, "command");
        validateAssistantManifest(command.assistant(), command.manifest());
        LoopDurabilityScope origin = command.origin();

        SessionEntity session = sessionRepository.findByIdForUpdate(origin.sessionId())
                .orElseThrow(SessionToolAttemptTransactionService::scopeFailure);
        Instant databaseNow = sessionRepository.currentDatabaseTime();
        validateIntentIdentityScope(session, origin);

        String manifestJson = encodeManifest(command.manifest());
        String manifestHash = sha256(manifestJson);
        Message assistant = command.assistant().toMessage();
        String assistantPayloadHash = sha256(messageCodec.writeMessage(assistant));
        PersistedMessageCodec.PersistedMessage expectedAssistant =
                new PersistedMessageCodec.PersistedMessage(
                        assistant,
                        "NORMAL",
                        "normal",
                        null,
                        null,
                        Collections.emptyMap(),
                        command.traceId());

        SessionToolAttemptEntity existing = attemptRepository
                .findBySessionIdAndStepIdForUpdate(origin.sessionId(), command.stepId())
                .orElse(null);
        List<SessionMessageEntity> existingBatch = messageRepository
                .findBySessionIdAndWriteBatchIdOrderByWriteBatchOrdinalAsc(
                        origin.sessionId(), command.writeBatchId());
        boolean attemptPresent = existing != null;
        boolean batchPresent = !existingBatch.isEmpty();
        if (attemptPresent != batchPresent) {
            // A retry is valid only when both durable halves exist. Never adopt or repair an
            // orphan batch/attempt, and classify it before a changed tail can look like stale scope.
            throw integrityFailure();
        }
        if (existing != null) {
            return verifyRetryAndBuildAck(
                    command,
                    existing,
                    expectedAssistant,
                    assistantPayloadHash,
                    manifestJson,
                    manifestHash,
                    allowWaitingInteractiveRetry);
        }

        validateIntentLiveLease(session, databaseNow);

        DurableFrontier preIntentFrontier = currentFrontier(origin.sessionId());
        if (!command.expectedPreIntentFrontier().equals(preIntentFrontier)) {
            throw scopeFailure();
        }
        rejectBlockingAttempt(origin.sessionId());
        PersistedMessageOccurrence persistedAssistant = appendIntentBatch(
                origin.sessionId(), command.writeBatchId(), expectedAssistant).get(0);

        SessionToolAttemptEntity attempt = new SessionToolAttemptEntity();
        attempt.setSessionId(origin.sessionId());
        attempt.setStepId(command.stepId());
        attempt.setHistoryEpoch(origin.historyEpoch());
        attempt.setOriginLoopId(origin.loopId());
        attempt.setOriginFence(origin.loopFence());
        attempt.setAssistantMessageId(persistedAssistant.messageId());
        attempt.setAssistantPayloadHash(assistantPayloadHash);
        attempt.setPreIntentMaxMessageId(preIntentFrontier.maxMessageId());
        attempt.setPreIntentMaxSeq(preIntentFrontier.maxSeq());
        attempt.setManifestJson(manifestJson);
        attempt.setManifestHash(manifestHash);
        attempt.setReplaySafety(command.manifest().replaySafety().name());
        attempt.setState(INTENT_COMMITTED);

        session.setMessageCount(Math.toIntExact(messageRepository.countBySessionId(origin.sessionId())));
        SessionToolAttemptEntity saved = attemptRepository.saveAndFlush(attempt);
        entityManager.refresh(saved);
        return verifyRetryAndBuildAck(
                command,
                saved,
                expectedAssistant,
                assistantPayloadHash,
                manifestJson,
                manifestHash,
                allowWaitingInteractiveRetry);
    }

    private IntentCommitAck verifyRetryAndBuildAck(
            IntentCommitCommand command,
            SessionToolAttemptEntity attempt,
            PersistedMessageCodec.PersistedMessage expectedAssistant,
            String expectedAssistantHash,
            String expectedManifestJson,
            String expectedManifestHash,
            boolean allowWaitingInteractiveRetry) {
        LoopDurabilityScope origin = command.origin();
        boolean waitingInteractiveRetry = allowWaitingInteractiveRetry
                && DurableToolAttemptState.WAITING_USER.name().equals(attempt.getState());
        if ((!INTENT_COMMITTED.equals(attempt.getState()) && !waitingInteractiveRetry)
                || !origin.sessionId().equals(attempt.getSessionId())
                || !command.stepId().equals(attempt.getStepId())
                || origin.historyEpoch() != attempt.getHistoryEpoch()
                || !origin.loopId().equals(attempt.getOriginLoopId())
                || origin.loopFence() != attempt.getOriginFence()
                || attempt.getPreIntentMaxMessageId()
                        != command.expectedPreIntentFrontier().maxMessageId()
                || attempt.getPreIntentMaxSeq()
                        != command.expectedPreIntentFrontier().maxSeq()
                || !expectedAssistantHash.equals(attempt.getAssistantPayloadHash())
                || !expectedManifestJson.equals(attempt.getManifestJson())
                || !expectedManifestHash.equals(attempt.getManifestHash())
                || !command.manifest().replaySafety().name().equals(attempt.getReplaySafety())) {
            throw integrityFailure();
        }

        ToolCallManifest persistedManifest = decodeCanonicalManifest(attempt.getManifestJson());
        if (!expectedManifestJson.equals(encodeManifest(persistedManifest))
                || !expectedManifestHash.equals(sha256(attempt.getManifestJson()))) {
            throw integrityFailure();
        }
        List<PersistedMessageOccurrence> assistantBatch = readExactIntentBatch(
                origin.sessionId(), command.writeBatchId(), expectedAssistant);
        PersistedMessageOccurrence persistedAssistant = assistantBatch.get(0);
        if (!Objects.equals(attempt.getAssistantMessageId(), persistedAssistant.messageId())
                || !expectedAssistantHash.equals(
                        sha256(messageCodec.writeMessage(persistedAssistant.message().toMessage())))) {
            throw integrityFailure();
        }

        DurableFrontier persistedFrontier = derivePreIntentFrontier(
                origin.sessionId(), persistedAssistant);
        if (attempt.getPreIntentMaxMessageId() != persistedFrontier.maxMessageId()
                || attempt.getPreIntentMaxSeq() != persistedFrontier.maxSeq()
                || !command.expectedPreIntentFrontier().equals(persistedFrontier)) {
            throw integrityFailure();
        }
        DurableFrontier currentTail = currentFrontier(origin.sessionId());
        if ((!waitingInteractiveRetry
                && (currentTail.maxMessageId() != persistedAssistant.messageId()
                    || currentTail.maxSeq() != persistedAssistant.seqNo()))
                || (waitingInteractiveRetry
                    && currentTail.maxSeq() <= persistedAssistant.seqNo())) {
            throw integrityFailure();
        }
        return new IntentCommitAck(
                attempt.getId(),
                attempt.getStepId(),
                persistedAssistant,
                persistedFrontier,
                persistedManifest,
                attempt.getAssistantPayloadHash(),
                attempt.getManifestHash(),
                ReplaySafety.valueOf(attempt.getReplaySafety()));
    }

    private List<PersistedMessageOccurrence> appendIntentBatch(
            String sessionId,
            String writeBatchId,
            PersistedMessageCodec.PersistedMessage expectedAssistant) {
        try {
            return messageWriter.appendNewBatchLocked(
                    sessionId, writeBatchId, List.of(expectedAssistant));
        } catch (DurableMessageBatchIntegrityException batchIntegrityFailure) {
            // Translate only the writer's closed integrity signal. Scope, blocking, codec,
            // and persistence failures retain their distinct service-boundary handling.
            throw integrityFailure();
        }
    }

    private List<PersistedMessageOccurrence> readExactIntentBatch(
            String sessionId,
            String writeBatchId,
            PersistedMessageCodec.PersistedMessage expectedAssistant) {
        try {
            return messageWriter.readExactBatch(
                    sessionId, writeBatchId, List.of(expectedAssistant));
        } catch (DurableMessageBatchIntegrityException batchIntegrityFailure) {
            // Keep the public intent protocol independent from the lower-level writer wording.
            throw integrityFailure();
        }
    }

    private void rejectBlockingAttempt(String sessionId) {
        for (SessionToolAttemptEntity attempt : attemptRepository
                .findBySessionIdAndStateIn(sessionId, ALL_ATTEMPT_STATES)) {
            if (OPEN_STATES.contains(attempt.getState())
                    || (TERMINAL_STATES.contains(attempt.getState())
                    && !ARCHIVE_READY_STATES.contains(attempt.getArchivePreparationState()))) {
                throw new DurableIntentRejectedException(
                        "Session has a blocking durable Tool attempt");
            }
        }
    }

    private DurableFrontier currentFrontier(String sessionId) {
        return messageRepository.findTopBySessionIdOrderBySeqNoDesc(sessionId)
                .map(SessionToolAttemptTransactionService::frontierOf)
                .orElse(DurableFrontier.EMPTY);
    }

    private DurableFrontier derivePreIntentFrontier(
            String sessionId, PersistedMessageOccurrence assistant) {
        if (assistant.seqNo() == 0L) {
            if (messageRepository
                    .findTopBySessionIdAndSeqNoLessThanOrderBySeqNoDesc(sessionId, 0L)
                    .isPresent()) {
                throw integrityFailure();
            }
            return DurableFrontier.EMPTY;
        }
        SessionMessageEntity previous = messageRepository
                .findTopBySessionIdAndSeqNoLessThanOrderBySeqNoDesc(sessionId, assistant.seqNo())
                .orElseThrow(SessionToolAttemptTransactionService::integrityFailure);
        if (previous.getId() == null || previous.getSeqNo() != assistant.seqNo() - 1L) {
            throw integrityFailure();
        }
        return frontierOf(previous);
    }

    private static DurableFrontier frontierOf(SessionMessageEntity row) {
        if (row.getId() == null) throw integrityFailure();
        return new DurableFrontier(row.getId(), row.getSeqNo());
    }

    private static void validateIntentIdentityScope(
            SessionEntity session, LoopDurabilityScope origin) {
        if (!Objects.equals(session.getUserId(), origin.userId())
                || session.getHistoryEpoch() != origin.historyEpoch()
                || session.isRestorePreparing()
                || !origin.loopId().equals(session.getActiveLoopId())
                || session.getLoopFence() != origin.loopFence()
                || !origin.ownerInstanceId().equals(session.getLoopOwnerInstanceId())) {
            throw scopeFailure();
        }
    }

    private static void validateIntentLiveLease(SessionEntity session, Instant databaseNow) {
        if (session.getLoopLeaseUntil() == null
                || !session.getLoopLeaseUntil().isAfter(databaseNow)) {
            throw scopeFailure();
        }
    }

    private static void validateClaimIdentityScope(
            SessionEntity session, LoopDurabilityScope claimant) {
        if (!Objects.equals(session.getUserId(), claimant.userId())
                || session.getHistoryEpoch() != claimant.historyEpoch()
                || session.isRestorePreparing()
                || !claimant.loopId().equals(session.getActiveLoopId())
                || session.getLoopFence() != claimant.loopFence()
                || !claimant.ownerInstanceId().equals(session.getLoopOwnerInstanceId())) {
            throw claimScopeFailure();
        }
    }

    private static void validateClaimLiveLease(SessionEntity session, Instant databaseNow) {
        if (session.getLoopLeaseUntil() == null
                || !session.getLoopLeaseUntil().isAfter(databaseNow)) {
            throw claimScopeFailure();
        }
    }

    private static void validateResultIdentityScope(
            SessionEntity session, LoopDurabilityScope executor) {
        if (!Objects.equals(session.getUserId(), executor.userId())
                || session.getHistoryEpoch() != executor.historyEpoch()
                || session.isRestorePreparing()
                || !executor.loopId().equals(session.getActiveLoopId())
                || session.getLoopFence() != executor.loopFence()
                || !executor.ownerInstanceId().equals(session.getLoopOwnerInstanceId())) {
            throw resultScopeFailure();
        }
    }

    private static void validateResultLiveLease(
            SessionEntity session, Instant databaseNow) {
        if (session.getLoopLeaseUntil() == null
                || !session.getLoopLeaseUntil().isAfter(databaseNow)) {
            throw resultScopeFailure();
        }
    }

    private static void validateAssistantManifest(
            MessageSnapshot assistantSnapshot, ToolCallManifest manifest) {
        Message assistant = assistantSnapshot.toMessage();
        if (assistant.getRole() != Message.Role.ASSISTANT) {
            throw new IllegalArgumentException("assistant snapshot must have assistant role");
        }
        List<ToolUseBlock> blocks = assistant.getToolUseBlocks();
        if (blocks.size() != manifest.calls().size()) {
            throw new IllegalArgumentException("assistant and manifest Tool vectors differ");
        }
        for (int ordinal = 0; ordinal < blocks.size(); ordinal++) {
            ToolUseBlock block = blocks.get(ordinal);
            ToolCallIntent call = manifest.calls().get(ordinal);
            if (call.providerOrdinal() != ordinal
                    || !call.toolUseId().equals(block.getId())
                    || !call.toolName().equals(block.getName())
                    || !call.input().equals(FrozenJson.capture(block.getInput()))) {
                throw new IllegalArgumentException("assistant and manifest Tool vectors differ");
            }
        }
    }

    private String encodeManifest(ToolCallManifest manifest) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 1);
        List<Map<String, Object>> calls = new ArrayList<>(manifest.calls().size());
        for (ToolCallIntent call : manifest.calls()) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("providerOrdinal", call.providerOrdinal());
            value.put("toolUseId", call.toolUseId());
            value.put("toolName", call.toolName());
            value.put("input", call.input().toJavaValue());
            value.put("replaySafety", call.replaySafety().name());
            calls.add(value);
        }
        root.put("calls", calls);
        root.put("replaySafety", manifest.replaySafety().name());
        try {
            return canonicalManifestWriter.writeValueAsString(root);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Tool manifest could not be encoded");
        }
    }

    private ToolCallManifest decodeCanonicalManifest(String manifestJson) {
        try {
            JsonNode root = objectMapper.readTree(manifestJson);
            if (root == null || !root.isObject() || !hasExactFields(root, MANIFEST_FIELDS)
                    || !root.path("schemaVersion").isIntegralNumber()
                    || root.path("schemaVersion").intValue() != 1
                    || !root.path("calls").isArray()
                    || !root.path("replaySafety").isTextual()) {
                throw integrityFailure();
            }
            List<ToolCallIntent> calls = new ArrayList<>();
            for (JsonNode value : root.path("calls")) {
                if (!value.isObject() || !hasExactFields(value, CALL_FIELDS)
                        || !value.path("providerOrdinal").isIntegralNumber()
                        || !value.path("toolUseId").isTextual()
                        || !value.path("toolName").isTextual()
                        || !value.has("input")
                        || !value.path("replaySafety").isTextual()) {
                    throw integrityFailure();
                }
                calls.add(new ToolCallIntent(
                        value.path("providerOrdinal").intValue(),
                        value.path("toolUseId").textValue(),
                        value.path("toolName").textValue(),
                        FrozenJson.capture(objectMapper.convertValue(value.get("input"), Object.class)),
                        ReplaySafety.valueOf(value.path("replaySafety").textValue())));
            }
            ToolCallManifest decoded = new ToolCallManifest(
                    calls, ReplaySafety.valueOf(root.path("replaySafety").textValue()));
            if (!manifestJson.equals(encodeManifest(decoded))) {
                throw integrityFailure();
            }
            return decoded;
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw integrityFailure();
        }
    }

    private static boolean hasExactFields(JsonNode value, Set<String> expected) {
        Set<String> actual = new LinkedHashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        return actual.equals(expected);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private static DurableIntentRejectedException scopeFailure() {
        return new DurableIntentRejectedException(
                "Durable intent scope is no longer authoritative");
    }

    private static DurableIntentRejectedException integrityFailure() {
        return new DurableIntentRejectedException(
                "Durable intent is partial or inconsistent");
    }

    private static DurableExecutionClaimRejectedException claimScopeFailure() {
        return new DurableExecutionClaimRejectedException(
                "Durable execution claim scope is no longer authoritative");
    }

    private static DurableExecutionClaimRejectedException claimConflict() {
        return new DurableExecutionClaimRejectedException(
                "Durable execution claim conflicts with persisted attempt state");
    }

    private static DurableToolResultRejectedException resultScopeFailure() {
        return new DurableToolResultRejectedException(
                "Durable Tool result scope is no longer authoritative");
    }

    private static DurableToolResultRejectedException resultIntegrityFailure() {
        return new DurableToolResultRejectedException(
                "Durable Tool result vector is partial or inconsistent");
    }

    /** Closed, payload-free failures that are safe to preserve across the transaction facade. */
    private static final class DurableIntentRejectedException extends IllegalStateException {

        private DurableIntentRejectedException(String message) {
            super(message);
        }
    }

    /** Closed claim failures that are safe to preserve across the transaction facade. */
    private static final class DurableExecutionClaimRejectedException
            extends IllegalStateException {

        private DurableExecutionClaimRejectedException(String message) {
            super(message);
        }
    }

    /** Closed result failures that are safe to preserve across the transaction facade. */
    private static final class DurableToolResultRejectedException extends IllegalStateException {

        private DurableToolResultRejectedException(String message) {
            super(message);
        }
    }
}
