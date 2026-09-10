package com.skillforge.server.session;

/** Runs the frozen W-R6-1 oracle against a deterministic reference implementation. */
class InMemorySessionRunCoordinatorContractTest extends SessionRunCoordinatorContract {

    @Override
    protected SessionRunCoordinator newCoordinator() {
        return new ReferenceSessionRunCoordinator();
    }

    private static final class ReferenceSessionRunCoordinator implements SessionRunCoordinator {
        private ClaimAck winningAck;
        private boolean inboxDrainAccepted;
        private boolean transcriptAccepted;

        @Override
        public synchronized ClaimResult claim(ClaimCommand command) {
            ClaimIdentity requested = ClaimIdentity.from(command);
            if (winningAck == null) {
                winningAck = new ClaimAck(requested);
                return new ClaimResult(ClaimDisposition.ACCEPTED, winningAck);
            }
            if (winningAck.winner().equals(requested)) {
                return new ClaimResult(ClaimDisposition.REPLAYED, winningAck);
            }
            return new ClaimResult(ClaimDisposition.REJECTED, winningAck);
        }

        @Override
        public synchronized HandoffResult acceptInboxDrain(ClaimIdentity claim) {
            if (!isWinner(claim)) return new HandoffResult(HandoffDisposition.HANDOFF_REJECTED);
            if (inboxDrainAccepted) return new HandoffResult(HandoffDisposition.HANDOFF_REPLAYED);
            inboxDrainAccepted = true;
            return new HandoffResult(HandoffDisposition.HANDOFF_ACCEPTED);
        }

        @Override
        public synchronized HandoffResult acceptTranscriptContinuation(ClaimIdentity claim) {
            if (!isWinner(claim)) return new HandoffResult(HandoffDisposition.HANDOFF_REJECTED);
            if (transcriptAccepted) return new HandoffResult(HandoffDisposition.HANDOFF_REPLAYED);
            transcriptAccepted = true;
            return new HandoffResult(HandoffDisposition.HANDOFF_ACCEPTED);
        }

        @Override
        public synchronized RecoveryResult authorizeCrashRecovery(ClaimIdentity claim) {
            return new RecoveryResult(isWinner(claim)
                    ? RecoveryDisposition.RECOVERY_AUTHORIZED
                    : RecoveryDisposition.RECOVERY_REJECTED);
        }

        private boolean isWinner(ClaimIdentity claim) {
            return winningAck != null && winningAck.winner().equals(claim);
        }
    }
}
