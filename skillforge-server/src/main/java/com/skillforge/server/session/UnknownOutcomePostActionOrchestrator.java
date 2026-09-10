package com.skillforge.server.session;

import com.skillforge.server.exception.RetryBusyException;
import com.skillforge.server.service.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import static com.skillforge.server.session.SessionRunCoordinator.ClaimDisposition.REJECTED;
import static com.skillforge.server.session.SessionRunCoordinator.HandoffDisposition.HANDOFF_REJECTED;
import static com.skillforge.server.session.SessionRunCoordinator.RecoveryDisposition.RECOVERY_REJECTED;

/**
 * Post-commit owner of the resolved-unknown continuation sequence.
 *
 * <p>Every collaborator method is a short transaction or an external action; this class never
 * holds a database transaction while broadcasting or scheduling Provider work.
 */
@Service
public class UnknownOutcomePostActionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(
            UnknownOutcomePostActionOrchestrator.class);

    private final UnknownOutcomeResolutionService resolutionService;
    private final UnknownOutcomePostActionTransactionService transactions;
    private final SessionRunCoordinator runCoordinator;
    private final OccurrenceArchivePreparation archivePreparation;
    private final ChatService chatService;

    public UnknownOutcomePostActionOrchestrator(
            UnknownOutcomeResolutionService resolutionService,
            UnknownOutcomePostActionTransactionService transactions,
            SessionRunCoordinator runCoordinator,
            OccurrenceArchivePreparation archivePreparation,
            ChatService chatService) {
        this.resolutionService = Objects.requireNonNull(resolutionService, "resolutionService");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.runCoordinator = Objects.requireNonNull(runCoordinator, "runCoordinator");
        this.archivePreparation = Objects.requireNonNull(
                archivePreparation, "archivePreparation");
        this.chatService = Objects.requireNonNull(chatService, "chatService");
    }

    /** Commits the human decision first; follow-up failure never erases its durable ACK. */
    public UnknownOutcomeResolutionAck resolve(
            String sessionId,
            long attemptId,
            UnknownOutcomeResolutionActor actor,
            UnknownOutcomeResolutionRequest request) {
        UnknownOutcomeResolutionAck acknowledgement = resolutionService.resolve(
                sessionId, attemptId, actor, request);
        if (acknowledgement.action()
                == UnknownOutcomeResolutionRequest.PostAction.CONTINUE_CURRENT_TIMELINE) {
            try {
                continueIfRecoverable(sessionId, attemptId);
            } catch (RetryBusyException busy) {
                log.debug("Resolved unknown continuation is already local: sessionId={}",
                        sessionId);
            } catch (RuntimeException deferred) {
                // The resolution and PENDING claim source are already durable. The poller retries
                // by identity; do not turn a post-commit scheduling failure into a false rollback.
                log.info("Resolved unknown continuation deferred: sessionId={}", sessionId);
            }
        }
        return acknowledgement;
    }

    /** Recovers a bounded batch and returns Session IDs owned by the post-action protocol. */
    public Set<String> recoverPendingContinuations() {
        Set<String> ownedSessions = new HashSet<>();
        for (UnknownOutcomePostActionTransactionService.PostActionLocator locator
                : transactions.findRecoverable()) {
            ownedSessions.add(locator.sessionId());
            try {
                continueIfRecoverable(locator.sessionId(), locator.attemptId());
            } catch (RetryBusyException busy) {
                log.debug("Resolved unknown continuation is already local: sessionId={}",
                        locator.sessionId());
            } catch (RuntimeException deferredOrRejected) {
                log.info("Resolved unknown continuation recovery deferred: sessionId={}",
                        locator.sessionId());
            }
        }
        return Set.copyOf(ownedSessions);
    }

    boolean continueIfRecoverable(String sessionId, long attemptId) {
        return transactions.findRecoverable(sessionId, attemptId)
                .map(this::continueCandidate)
                .orElse(false);
    }

    private boolean continueCandidate(
            UnknownOutcomePostActionTransactionService.PostActionCandidate candidate) {
        SessionRunCoordinator.ClaimIdentity winner;
        if ("PENDING".equals(candidate.state())) {
            SessionRunCoordinator.ClaimResult claim = runCoordinator.claim(
                    candidate.claimCommand());
            if (claim.disposition() == REJECTED) return false;
            winner = claim.ack().winner();
        } else {
            winner = candidate.claimedIdentity();
        }

        // A crash may occur after an assistant/intention row was persisted but before the old
        // post-action marker was advanced. Evidence-aware acceptance closes that marker without
        // issuing another Provider request.
        if (runCoordinator.acceptTranscriptContinuation(winner).disposition()
                != HANDOFF_REJECTED) {
            return true;
        }
        if (runCoordinator.authorizeCrashRecovery(winner).disposition()
                == RECOVERY_REJECTED) {
            return false;
        }

        ResolvedUnknownContinuationRun run = transactions.loadClaimedRun(winner);
        var archiveCommand = archivePreparation.loadResolvedUnknownPostActionArchive(
                run.scope(), winner);

        // Visibility is deliberately outside every database transaction. Repeated visibility
        // events are harmless; the committed occurrence identity remains canonical.
        chatService.republishResolvedUnknownResults(archiveCommand.resultBlocks());
        archivePreparation.ensurePrepared(archiveCommand);

        if (runCoordinator.acceptInboxDrain(winner).disposition() == HANDOFF_REJECTED) {
            return false;
        }
        // Refresh the frontier after an ACK-loss retry: some or all queued rows may already have
        // drained, and the Engine must not append them twice to its in-memory transcript.
        ResolvedUnknownContinuationRun refreshed = transactions.loadClaimedRun(winner);
        chatService.continueResolvedUnknownAsync(refreshed);
        return true;
    }
}
