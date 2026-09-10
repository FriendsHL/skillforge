package com.skillforge.server.session;

import com.skillforge.core.engine.durability.ArchivePreparationAck;
import com.skillforge.core.engine.durability.DurableFrontier;
import com.skillforge.core.engine.durability.DurableToolAttemptState;
import com.skillforge.core.engine.durability.ExecutionClaimAck;
import com.skillforge.core.engine.durability.ExecutionClaimCommand;
import com.skillforge.core.engine.durability.IntentCommitAck;
import com.skillforge.core.engine.durability.RecoveredToolAttempt;
import com.skillforge.server.entity.SessionMessageEntity;
import com.skillforge.server.repository.SessionMessageRepository;
import com.skillforge.server.runtime.RuntimeFailureFact;
import org.springframework.stereotype.Service;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Claims an expired run and reconstructs the exact safe continuation boundary. */
@Service
public class DurableSessionRecoveryCoordinator {

    private final SessionLoopAdmissionService admissionService;
    private final SessionToolAttemptTransactionService attemptTransactions;
    private final SessionInteractiveControlTransactionService interactiveTransactions;
    private final OccurrenceArchivePreparation archivePreparation;
    private final SessionMessageRepository messageRepository;

    public DurableSessionRecoveryCoordinator(
            SessionLoopAdmissionService admissionService,
            SessionToolAttemptTransactionService attemptTransactions,
            SessionInteractiveControlTransactionService interactiveTransactions,
            OccurrenceArchivePreparation archivePreparation,
            SessionMessageRepository messageRepository) {
        this.admissionService = Objects.requireNonNull(admissionService, "admissionService");
        this.attemptTransactions = Objects.requireNonNull(
                attemptTransactions, "attemptTransactions");
        this.interactiveTransactions = Objects.requireNonNull(
                interactiveTransactions, "interactiveTransactions");
        this.archivePreparation = Objects.requireNonNull(
                archivePreparation, "archivePreparation");
        this.messageRepository = Objects.requireNonNull(messageRepository, "messageRepository");
    }

    public RecoveryPlan recover(String sessionId, long userId) {
        // Stable within one Session so an ACK-loss retry on the same server can
        // recover the scope it just installed instead of waiting for its own lease.
        String loopId = stableRecoveryLoopId(sessionId);
        SessionLoopAdmissionService.RecoveryAdmissionAck admission =
                admissionService.claimRecovery(sessionId, userId, loopId);
        try {
            DurableFrontier current = currentFrontier(sessionId);
            if (admission.attemptId() == null) {
                return new RecoveryPlan(
                        RecoveryDisposition.PROVIDER_CONTINUE,
                        admission,
                        current,
                        null,
                        null,
                        null,
                        null);
            }

            return switch (admission.state()) {
                case INTENT_COMMITTED, EXECUTING -> recoverExecution(admission);
                case RESULTS_COMMITTED -> recoverCommittedResults(admission, current);
                case WAITING_USER -> recoverWaitingControl(admission, current);
                case UNCERTAIN_PENDING_RESOLUTION, RESOLVED_UNKNOWN -> new RecoveryPlan(
                        admission.state() == DurableToolAttemptState.RESOLVED_UNKNOWN
                                ? RecoveryDisposition.MANUAL_CONTINUATION
                                : RecoveryDisposition.UNCERTAIN_PENDING_RESOLUTION,
                        admission,
                        current,
                        null,
                        null,
                        null,
                        null);
            };
        } catch (DurableRecoveryRetryableException
                 | TransientDataAccessException
                 | CannotCreateTransactionException retryable) {
            throw new DurableRecoveryRetryableException();
        } catch (RuntimeException reconstructionFailure) {
            RuntimeFailureFact failure = new RuntimeFailureFact(
                    "harness",
                    "DURABLE_RECOVERY_RECONSTRUCTION_FAILED",
                    false,
                    "possible",
                    "The durable recovery boundary could not be reconstructed.");
            admissionService.parkRecoveryFailure(admission.scope(), failure);
            throw new DurableRecoveryFailureException();
        }
    }

    private RecoveryPlan recoverExecution(
            SessionLoopAdmissionService.RecoveryAdmissionAck admission) {
        ExecutionClaimCommand command = new ExecutionClaimCommand(
                admission.scope(),
                admission.attemptId(),
                admission.stepId(),
                recoveryClaimRequestId(admission),
                admission.state(),
                admission.attemptAlreadyClaimedByScope()
                        ? admission.executionGeneration() - 1L
                        : admission.executionGeneration());
        ExecutionClaimAck claim = exactClaim(command);
        if (claim.state() == DurableToolAttemptState.UNCERTAIN_PENDING_RESOLUTION) {
            return new RecoveryPlan(
                    RecoveryDisposition.UNCERTAIN_PENDING_RESOLUTION,
                    admission,
                    currentFrontier(admission.scope().sessionId()),
                    null,
                    null,
                    null,
                    null);
        }
        IntentCommitAck intent = attemptTransactions.loadIntentForRecovery(
                admission.scope(), admission.attemptId());
        DurableFrontier assistantFrontier = new DurableFrontier(
                intent.assistant().messageId(), intent.assistant().seqNo());
        return new RecoveryPlan(
                RecoveryDisposition.TOOL_REPLAY,
                admission,
                assistantFrontier,
                new RecoveredToolAttempt(intent, claim),
                null,
                null,
                null);
    }

    private RecoveryPlan recoverWaitingControl(
            SessionLoopAdmissionService.RecoveryAdmissionAck admission,
            DurableFrontier current) {
        SessionInteractiveControlTransactionService.InteractiveIntentAck waitingControl =
                interactiveTransactions.loadWaitingControl(
                        admission.scope(), admission.attemptId());
        DurableFrontier controlFrontier = new DurableFrontier(
                waitingControl.control().messageId(), waitingControl.control().seqNo());
        if (!controlFrontier.equals(current)) {
            throw new IllegalStateException(
                    "Recovered interactive control frontier is inconsistent");
        }
        return new RecoveryPlan(
                RecoveryDisposition.WAITING_USER,
                admission,
                controlFrontier,
                null,
                waitingControl,
                null,
                null);
    }

    private RecoveryPlan recoverCommittedResults(
            SessionLoopAdmissionService.RecoveryAdmissionAck admission,
            DurableFrontier current) {
        OccurrenceArchivePreparation.RecoveredArchive recoveredArchive =
                archivePreparation.ensurePreparedForRecoveryWithCommand(
                        admission.scope(), admission.attemptId());
        ArchivePreparationAck archive = recoveredArchive.acknowledgement();
        var last = archive.resultBlocks().stream()
                .max(java.util.Comparator.comparingLong(
                        com.skillforge.core.engine.durability.PersistedBlockOccurrence::seqNo)
                        .thenComparingLong(
                                com.skillforge.core.engine.durability.PersistedBlockOccurrence::messageId))
                .orElseThrow(() -> new IllegalStateException(
                        "Recovered Tool result vector is empty"));
        DurableFrontier resultFrontier = new DurableFrontier(last.messageId(), last.seqNo());
        if (!resultFrontier.equals(current)) {
            throw new IllegalStateException("Recovered Tool result frontier is inconsistent");
        }
        return new RecoveryPlan(
                RecoveryDisposition.PROVIDER_CONTINUE,
                admission,
                resultFrontier,
                null,
                null,
                recoveredArchive.command(),
                archive);
    }

    private ExecutionClaimAck exactClaim(ExecutionClaimCommand command) {
        boolean onlyRetryableFailures = true;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return attemptTransactions.claimExecution(command);
            } catch (DurableRecoveryRetryableException retryable) {
                // Keep the stable command identity for the bounded retry.
            } catch (IllegalStateException failure) {
                // Retry the byte-identical command; the transaction service owns
                // exact ACK reconstruction and integrity classification.
                onlyRetryableFailures = false;
            }
        }
        if (onlyRetryableFailures) throw new DurableRecoveryRetryableException();
        throw new IllegalStateException("Durable recovery execution claim failed");
    }

    private static UUID recoveryClaimRequestId(
            SessionLoopAdmissionService.RecoveryAdmissionAck admission) {
        String identity = "skillforge:durable-recovery-claim:"
                + admission.scope().sessionId() + ":"
                + admission.attemptId() + ":"
                + admission.scope().loopFence();
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }

    /** Stable authority identity shared by restart recovery and manual interactive continuation. */
    public static String stableRecoveryLoopId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        return UUID.nameUUIDFromBytes(
                ("skillforge:durable-recovery:" + sessionId)
                        .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private DurableFrontier currentFrontier(String sessionId) {
        return messageRepository.findTopBySessionIdOrderBySeqNoDesc(sessionId)
                .map(DurableSessionRecoveryCoordinator::frontierOf)
                .orElse(DurableFrontier.EMPTY);
    }

    private static DurableFrontier frontierOf(SessionMessageEntity row) {
        if (row.getId() == null) throw new IllegalStateException("Message identity is missing");
        return new DurableFrontier(row.getId(), row.getSeqNo());
    }

    public enum RecoveryDisposition {
        TOOL_REPLAY,
        PROVIDER_CONTINUE,
        WAITING_USER,
        UNCERTAIN_PENDING_RESOLUTION,
        MANUAL_CONTINUATION
    }

    public record RecoveryPlan(
            RecoveryDisposition disposition,
            SessionLoopAdmissionService.RecoveryAdmissionAck admission,
            DurableFrontier frontier,
            RecoveredToolAttempt recoveredToolAttempt,
            SessionInteractiveControlTransactionService.InteractiveIntentAck
                    recoveredWaitingControl,
            com.skillforge.core.engine.durability.ArchivePreparationCommand
                    archiveVisibilityCommand,
            ArchivePreparationAck archivePreparation,
            SessionRunCoordinator.ClaimIdentity postActionClaim) {

        public RecoveryPlan(
                RecoveryDisposition disposition,
                SessionLoopAdmissionService.RecoveryAdmissionAck admission,
                DurableFrontier frontier,
                RecoveredToolAttempt recoveredToolAttempt,
                SessionInteractiveControlTransactionService.InteractiveIntentAck
                        recoveredWaitingControl,
                com.skillforge.core.engine.durability.ArchivePreparationCommand
                        archiveVisibilityCommand,
                ArchivePreparationAck archivePreparation) {
            this(disposition, admission, frontier, recoveredToolAttempt,
                    recoveredWaitingControl, archiveVisibilityCommand, archivePreparation, null);
        }

        public RecoveryPlan(
                RecoveryDisposition disposition,
                SessionLoopAdmissionService.RecoveryAdmissionAck admission,
                DurableFrontier frontier,
                RecoveredToolAttempt recoveredToolAttempt,
                SessionInteractiveControlTransactionService.InteractiveIntentAck
                        recoveredWaitingControl,
                ArchivePreparationAck archivePreparation) {
            this(disposition, admission, frontier, recoveredToolAttempt,
                    recoveredWaitingControl, null, archivePreparation, null);
        }

        public static RecoveryPlan resolvedUnknownContinuation(
                ResolvedUnknownContinuationRun run) {
            Objects.requireNonNull(run, "run");
            SessionLoopAdmissionService.RecoveryAdmissionAck admission =
                    new SessionLoopAdmissionService.RecoveryAdmissionAck(
                            run.scope(), run.attemptId(), run.stepId(),
                            DurableToolAttemptState.RESOLVED_UNKNOWN,
                            run.executionGeneration(), true, run.leaseUntil());
            return new RecoveryPlan(
                    RecoveryDisposition.PROVIDER_CONTINUE, admission, run.frontier(),
                    null, null, null, null, run.claim());
        }

        public RecoveryPlan {
            Objects.requireNonNull(disposition, "disposition");
            Objects.requireNonNull(admission, "admission");
            Objects.requireNonNull(frontier, "frontier");
            if ((disposition == RecoveryDisposition.TOOL_REPLAY)
                    != (recoveredToolAttempt != null)) {
                throw new IllegalArgumentException(
                        "TOOL_REPLAY must carry exactly one recovered attempt");
            }
            if ((disposition == RecoveryDisposition.WAITING_USER)
                    != (recoveredWaitingControl != null)) {
                throw new IllegalArgumentException(
                        "WAITING_USER must carry exactly one recovered control");
            }
            if ((archiveVisibilityCommand != null) != (archivePreparation != null)) {
                throw new IllegalArgumentException(
                        "Recovered archive command and acknowledgement must travel together");
            }
            if (postActionClaim != null
                    && (disposition != RecoveryDisposition.PROVIDER_CONTINUE
                            || stateOf(admission) != DurableToolAttemptState.RESOLVED_UNKNOWN
                            || postActionClaim.attemptId() != admission.attemptId()
                            || !postActionClaim.sessionId().equals(
                                    admission.scope().sessionId())
                            || !postActionClaim.loopId().equals(admission.scope().loopId())
                            || postActionClaim.loopFence()
                                    != admission.scope().loopFence())) {
                throw new IllegalArgumentException(
                        "post-action claim must match its resolved continuation scope");
            }
        }

        private static DurableToolAttemptState stateOf(
                SessionLoopAdmissionService.RecoveryAdmissionAck admission) {
            return admission.state();
        }
    }
}
