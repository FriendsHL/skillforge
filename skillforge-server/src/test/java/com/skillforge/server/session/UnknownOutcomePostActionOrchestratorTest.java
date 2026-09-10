package com.skillforge.server.session;

import com.skillforge.core.engine.durability.ArchivePreparationAck;
import com.skillforge.core.engine.durability.ArchivePreparationCommand;
import com.skillforge.server.service.ChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.skillforge.server.session.SessionRunCoordinator.ClaimDisposition.ACCEPTED;
import static com.skillforge.server.session.SessionRunCoordinator.HandoffDisposition.HANDOFF_ACCEPTED;
import static com.skillforge.server.session.SessionRunCoordinator.HandoffDisposition.HANDOFF_REJECTED;
import static com.skillforge.server.session.SessionRunCoordinator.RecoveryDisposition.RECOVERY_AUTHORIZED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UnknownOutcomePostActionOrchestratorTest {

    private UnknownOutcomeResolutionService resolutionService;
    private UnknownOutcomePostActionTransactionService transactions;
    private SessionRunCoordinator runCoordinator;
    private OccurrenceArchivePreparation archivePreparation;
    private ChatService chatService;
    private UnknownOutcomePostActionOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        resolutionService = mock(UnknownOutcomeResolutionService.class);
        transactions = mock(UnknownOutcomePostActionTransactionService.class);
        runCoordinator = mock(SessionRunCoordinator.class);
        archivePreparation = mock(OccurrenceArchivePreparation.class);
        chatService = mock(ChatService.class);
        orchestrator = new UnknownOutcomePostActionOrchestrator(
                resolutionService, transactions, runCoordinator, archivePreparation, chatService);
    }

    @Test
    void resolve_prepareRestore_returnsCommittedAckWithoutClaimDrainOrProvider() {
        UnknownOutcomeResolutionAck ack = resolutionAck(
                UnknownOutcomeResolutionRequest.PostAction.PREPARE_RESTORE);
        UnknownOutcomeResolutionActor actor = UnknownOutcomeResolutionActor.authenticated(
                19L, Set.of(UnknownOutcomeResolutionService.ADMIN_AUTHORITY));
        UnknownOutcomeResolutionRequest request = request(
                UnknownOutcomeResolutionRequest.PostAction.PREPARE_RESTORE);
        when(resolutionService.resolve("session-1", 41L, actor, request)).thenReturn(ack);

        assertThat(orchestrator.resolve("session-1", 41L, actor, request)).isSameAs(ack);

        verify(transactions, never()).findRecoverable(anyString(), anyLong());
        verify(runCoordinator, never()).claim(any());
        verify(chatService, never()).continueResolvedUnknownAsync(any());
    }

    @Test
    void continue_claimsThenBroadcastsBeforeArchiveThenAcceptsDrainBeforeProvider() {
        UnknownOutcomePostActionTransactionService.PostActionCandidate candidate = pending();
        SessionRunCoordinator.ClaimCommand command = candidate.claimCommand();
        SessionRunCoordinator.ClaimIdentity winner = SessionRunCoordinator.ClaimIdentity.from(command);
        ResolvedUnknownContinuationRun run = run(winner);
        ArchivePreparationCommand archive = archiveCommand();
        when(transactions.findRecoverable("session-1", 41L))
                .thenReturn(Optional.of(candidate));
        when(runCoordinator.claim(command))
                .thenReturn(new SessionRunCoordinator.ClaimResult(
                        ACCEPTED, new SessionRunCoordinator.ClaimAck(winner)));
        when(runCoordinator.acceptTranscriptContinuation(winner))
                .thenReturn(new SessionRunCoordinator.HandoffResult(HANDOFF_REJECTED));
        when(runCoordinator.authorizeCrashRecovery(winner))
                .thenReturn(new SessionRunCoordinator.RecoveryResult(RECOVERY_AUTHORIZED));
        when(transactions.loadClaimedRun(winner)).thenReturn(run);
        when(archivePreparation.loadResolvedUnknownPostActionArchive(run.scope(), winner))
                .thenReturn(archive);
        when(archivePreparation.ensurePrepared(archive))
                .thenReturn(mock(ArchivePreparationAck.class));
        when(runCoordinator.acceptInboxDrain(winner))
                .thenReturn(new SessionRunCoordinator.HandoffResult(HANDOFF_ACCEPTED));

        assertThat(orchestrator.continueIfRecoverable("session-1", 41L)).isTrue();

        InOrder order = inOrder(
                runCoordinator, transactions, chatService, archivePreparation);
        order.verify(runCoordinator).claim(command);
        order.verify(runCoordinator).acceptTranscriptContinuation(winner);
        order.verify(runCoordinator).authorizeCrashRecovery(winner);
        order.verify(transactions).loadClaimedRun(winner);
        order.verify(archivePreparation).loadResolvedUnknownPostActionArchive(run.scope(), winner);
        order.verify(chatService).republishResolvedUnknownResults(
                archive.resultBlocks());
        order.verify(archivePreparation).ensurePrepared(archive);
        order.verify(runCoordinator).acceptInboxDrain(winner);
        order.verify(transactions).loadClaimedRun(winner);
        order.verify(chatService).continueResolvedUnknownAsync(run);
    }

    @Test
    void claimedCrash_withNoTranscript_recoversPersistedWinnerWithoutCreatingAnotherClaim() {
        SessionRunCoordinator.ClaimIdentity winner = SessionRunCoordinator.ClaimIdentity.from(
                pending().claimCommand());
        UnknownOutcomePostActionTransactionService.PostActionCandidate claimed =
                UnknownOutcomePostActionTransactionService.PostActionCandidate.claimed(
                        "session-1", 41L, 19L, 3L, UUID.randomUUID(), 1L, winner);
        ResolvedUnknownContinuationRun run = run(winner);
        ArchivePreparationCommand archive = archiveCommand();
        when(transactions.findRecoverable("session-1", 41L))
                .thenReturn(Optional.of(claimed));
        when(runCoordinator.acceptTranscriptContinuation(winner))
                .thenReturn(new SessionRunCoordinator.HandoffResult(HANDOFF_REJECTED));
        when(runCoordinator.authorizeCrashRecovery(winner))
                .thenReturn(new SessionRunCoordinator.RecoveryResult(RECOVERY_AUTHORIZED));
        when(transactions.loadClaimedRun(winner)).thenReturn(run);
        when(archivePreparation.loadResolvedUnknownPostActionArchive(run.scope(), winner))
                .thenReturn(archive);
        when(archivePreparation.ensurePrepared(archive))
                .thenReturn(mock(ArchivePreparationAck.class));
        when(runCoordinator.acceptInboxDrain(winner))
                .thenReturn(new SessionRunCoordinator.HandoffResult(HANDOFF_ACCEPTED));

        assertThat(orchestrator.continueIfRecoverable("session-1", 41L)).isTrue();

        verify(runCoordinator, never()).claim(any());
        verify(chatService).continueResolvedUnknownAsync(run);
    }

    @Test
    void claimedCrash_afterTranscriptPersistence_completesMarkerWithoutProviderOrDrain() {
        SessionRunCoordinator.ClaimIdentity winner = SessionRunCoordinator.ClaimIdentity.from(
                pending().claimCommand());
        UnknownOutcomePostActionTransactionService.PostActionCandidate claimed =
                UnknownOutcomePostActionTransactionService.PostActionCandidate.claimed(
                        "session-1", 41L, 19L, 3L, UUID.randomUUID(), 1L, winner);
        when(transactions.findRecoverable("session-1", 41L))
                .thenReturn(Optional.of(claimed));
        when(runCoordinator.acceptTranscriptContinuation(winner))
                .thenReturn(new SessionRunCoordinator.HandoffResult(HANDOFF_ACCEPTED));

        assertThat(orchestrator.continueIfRecoverable("session-1", 41L)).isTrue();

        verify(runCoordinator, never()).authorizeCrashRecovery(any());
        verify(archivePreparation, never()).ensurePrepared(any());
        verify(chatService, never()).continueResolvedUnknownAsync(any());
    }

    private static UnknownOutcomePostActionTransactionService.PostActionCandidate pending() {
        return UnknownOutcomePostActionTransactionService.PostActionCandidate.pending(
                "session-1", 41L, 19L, 3L, UUID.randomUUID(), 1L,
                UUID.fromString("10000000-0000-4000-8000-000000000001"),
                UUID.fromString("20000000-0000-4000-8000-000000000001"), 7L);
    }

    private static ResolvedUnknownContinuationRun run(
            SessionRunCoordinator.ClaimIdentity winner) {
        return mock(ResolvedUnknownContinuationRun.class, invocation -> switch (
                invocation.getMethod().getName()) {
            case "claim" -> winner;
            default -> org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
    }

    private static ArchivePreparationCommand archiveCommand() {
        ArchivePreparationCommand command = mock(ArchivePreparationCommand.class);
        return command;
    }

    private static UnknownOutcomeResolutionAck resolutionAck(
            UnknownOutcomeResolutionRequest.PostAction action) {
        return new UnknownOutcomeResolutionAck(
                UUID.randomUUID(), "session-1", 41L, UUID.randomUUID(), 3L, 1L, 6L,
                UnknownOutcomeResolutionAck.ActorAuthority.ADMIN, action, UUID.randomUUID(),
                "RESOLVED_UNKNOWN", List.of(),
                action == UnknownOutcomeResolutionRequest.PostAction.CONTINUE_CURRENT_TIMELINE
                        ? "PENDING" : "NONE",
                action == UnknownOutcomeResolutionRequest.PostAction.PREPARE_RESTORE,
                9L, Instant.now());
    }

    private static UnknownOutcomeResolutionRequest request(
            UnknownOutcomeResolutionRequest.PostAction action) {
        return new UnknownOutcomeResolutionRequest(
                UUID.randomUUID(), 3L, 1L, 6L, action, "operator decision", List.of());
    }
}
