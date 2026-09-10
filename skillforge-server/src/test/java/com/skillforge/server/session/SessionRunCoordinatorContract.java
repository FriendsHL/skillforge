package com.skillforge.server.session;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.skillforge.server.session.SessionRunCoordinator.ClaimDisposition.ACCEPTED;
import static com.skillforge.server.session.SessionRunCoordinator.ClaimDisposition.REJECTED;
import static com.skillforge.server.session.SessionRunCoordinator.ClaimDisposition.REPLAYED;
import static com.skillforge.server.session.SessionRunCoordinator.HandoffDisposition.HANDOFF_ACCEPTED;
import static com.skillforge.server.session.SessionRunCoordinator.HandoffDisposition.HANDOFF_REJECTED;
import static com.skillforge.server.session.SessionRunCoordinator.HandoffDisposition.HANDOFF_REPLAYED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reusable W-R6-1 oracle. Batch 2's JPA coordinator must extend this suite unchanged.
 */
abstract class SessionRunCoordinatorContract {

    protected abstract SessionRunCoordinator newCoordinator();

    protected void persistTranscriptEvidence(SessionRunCoordinator.ClaimIdentity claim) {
        // The in-memory contract has no transcript store. Persistence implementations override.
    }

    @Test
    void duplicateHttpRetry_returnsSameAckAndAcceptsExactlyOneClaim() throws Exception {
        SessionRunCoordinator coordinator = newCoordinator();
        SessionRunCoordinator.ClaimCommand command = command(
                UUID.fromString("00000000-0000-4000-8000-000000000001"), "loop-7", 7);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<SessionRunCoordinator.ClaimResult> retry = () -> {
            ready.countDown();
            start.await();
            return coordinator.claim(command);
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<SessionRunCoordinator.ClaimResult>> futures = List.of(
                    executor.submit(retry), executor.submit(retry));
            ready.await();
            start.countDown();
            List<SessionRunCoordinator.ClaimResult> results = List.of(
                    futures.get(0).get(), futures.get(1).get());

            assertThat(results).extracting(SessionRunCoordinator.ClaimResult::disposition)
                    .containsExactlyInAnyOrder(ACCEPTED, REPLAYED);
            assertThat(results.get(0).ack()).isEqualTo(results.get(1).ack());
            assertThat(results.get(0).ack().winner())
                    .isEqualTo(SessionRunCoordinator.ClaimIdentity.from(command));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void differentClaim_cannotReplaceWinningResolutionResultActionOrRunFence() {
        SessionRunCoordinator coordinator = newCoordinator();
        SessionRunCoordinator.ClaimCommand winner = command(
                UUID.fromString("00000000-0000-4000-8000-000000000001"), "loop-7", 7);
        SessionRunCoordinator.ClaimCommand competitor = command(
                UUID.fromString("00000000-0000-4000-8000-000000000002"), "loop-8", 8);

        SessionRunCoordinator.ClaimResult first = coordinator.claim(winner);
        SessionRunCoordinator.ClaimResult rejected = coordinator.claim(competitor);

        assertThat(first.disposition()).isEqualTo(ACCEPTED);
        assertThat(rejected.disposition()).isEqualTo(REJECTED);
        assertThat(rejected.ack()).isEqualTo(first.ack());
        assertThat(rejected.ack().winner().resolutionRequestId())
                .isEqualTo(winner.resolutionRequestId());
        assertThat(rejected.ack().winner().resultBatchId()).isEqualTo(winner.resultBatchId());
        assertThat(rejected.ack().winner().action())
                .isEqualTo(SessionRunCoordinator.ContinuationAction.CONTINUE_CURRENT_TIMELINE);
        assertThat(rejected.ack().winner().loopId()).isEqualTo("loop-7");
        assertThat(rejected.ack().winner().loopFence()).isEqualTo(7);
    }

    @Test
    void winningClaim_acceptsDrainAndTranscriptHandoffsOnlyOnceEach() {
        SessionRunCoordinator coordinator = newCoordinator();
        SessionRunCoordinator.ClaimResult claim = coordinator.claim(command(
                UUID.fromString("00000000-0000-4000-8000-000000000001"), "loop-7", 7));
        SessionRunCoordinator.ClaimIdentity winner = claim.ack().winner();

        assertThat(coordinator.acceptInboxDrain(winner).disposition()).isEqualTo(HANDOFF_ACCEPTED);
        assertThat(coordinator.acceptInboxDrain(winner).disposition()).isEqualTo(HANDOFF_REPLAYED);
        persistTranscriptEvidence(winner);
        assertThat(coordinator.acceptTranscriptContinuation(winner).disposition())
                .isEqualTo(HANDOFF_ACCEPTED);
        assertThat(coordinator.acceptTranscriptContinuation(winner).disposition())
                .isEqualTo(HANDOFF_REPLAYED);

        SessionRunCoordinator.ClaimIdentity loser = SessionRunCoordinator.ClaimIdentity.from(command(
                UUID.fromString("00000000-0000-4000-8000-000000000099"), "loop-99", 99));
        assertThat(coordinator.acceptInboxDrain(loser).disposition()).isEqualTo(HANDOFF_REJECTED);
        assertThat(coordinator.acceptTranscriptContinuation(loser).disposition())
                .isEqualTo(HANDOFF_REJECTED);
    }

    @Test
    void crashRecovery_isAuthorizedOnlyForWinningClaimRequestAndRunFence() {
        SessionRunCoordinator coordinator = newCoordinator();
        SessionRunCoordinator.ClaimIdentity winner = coordinator.claim(command(
                        UUID.fromString("00000000-0000-4000-8000-000000000001"), "loop-7", 7))
                .ack().winner();

        assertThat(coordinator.authorizeCrashRecovery(winner).disposition())
                .isEqualTo(SessionRunCoordinator.RecoveryDisposition.RECOVERY_AUTHORIZED);
        SessionRunCoordinator.ClaimIdentity staleFence = new SessionRunCoordinator.ClaimIdentity(
                winner.sessionId(), winner.attemptId(), winner.resolutionRequestId(),
                winner.resultBatchId(), winner.action(), winner.claimRequestId(),
                winner.loopId(), winner.loopFence() - 1);
        assertThat(coordinator.authorizeCrashRecovery(staleFence).disposition())
                .isEqualTo(SessionRunCoordinator.RecoveryDisposition.RECOVERY_REJECTED);
        SessionRunCoordinator.ClaimIdentity staleRequest = new SessionRunCoordinator.ClaimIdentity(
                winner.sessionId(), winner.attemptId(), winner.resolutionRequestId(),
                winner.resultBatchId(), winner.action(),
                UUID.fromString("00000000-0000-4000-8000-000000000088"),
                winner.loopId(), winner.loopFence());
        assertThat(coordinator.authorizeCrashRecovery(staleRequest).disposition())
                .isEqualTo(SessionRunCoordinator.RecoveryDisposition.RECOVERY_REJECTED);
        SessionRunCoordinator.ClaimIdentity staleLoop = new SessionRunCoordinator.ClaimIdentity(
                winner.sessionId(), winner.attemptId(), winner.resolutionRequestId(),
                winner.resultBatchId(), winner.action(), winner.claimRequestId(),
                "loop-stale", winner.loopFence());
        assertThat(coordinator.authorizeCrashRecovery(staleLoop).disposition())
                .isEqualTo(SessionRunCoordinator.RecoveryDisposition.RECOVERY_REJECTED);
    }

    private static SessionRunCoordinator.ClaimCommand command(
            UUID claimRequestId, String loopId, long loopFence) {
        return new SessionRunCoordinator.ClaimCommand(
                "session-1",
                41L,
                UUID.fromString("10000000-0000-4000-8000-000000000001"),
                UUID.fromString("20000000-0000-4000-8000-000000000001"),
                SessionRunCoordinator.ContinuationAction.CONTINUE_CURRENT_TIMELINE,
                claimRequestId,
                loopId,
                loopFence);
    }
}
