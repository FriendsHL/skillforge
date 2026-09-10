package com.skillforge.core.engine.durability;

/**
 * Core-side boundary for durable conversation transitions.
 *
 * <p>The disabled singleton is an explicit compatibility mode. The engine must not call
 * {@link #commitIntent(IntentCommitCommand)} while it is disabled; doing so is a programming
 * error rather than permission to fabricate a persistence acknowledgement.
 */
public interface ConversationDurabilitySink {

    boolean enabled();

    IntentCommitAck commitIntent(IntentCommitCommand command);

    /**
     * Atomically commits the complete assistant Tool vector and its provider-filtered control.
     * No Tool scheduling, confirmation delivery, or control visibility may happen first.
     */
    default InteractiveIntentAck commitInteractiveIntent(InteractiveIntentCommand command) {
        throw new IllegalStateException("Durable interactive intent persistence is not configured");
    }

    /**
     * Claims a persisted attempt before any Tool dispatch. The default is deliberately
     * fail-closed so an enabled legacy implementation can never fabricate execution authority.
     */
    default ExecutionClaimAck claimExecution(ExecutionClaimCommand command) {
        throw new IllegalStateException("Durable execution claim is not configured");
    }

    /**
     * Commits the complete ordered Tool result vector before any result becomes visible or the
     * loop can continue. Enabled legacy implementations fail closed by default.
     */
    default ToolResultCommitAck commitResults(ToolResultCommitCommand command) {
        throw new IllegalStateException("Durable Tool result persistence is not configured");
    }

    /** Ensures occurrence archive preparation, or records bounded raw fallback, before continuation. */
    default ArchivePreparationAck prepareResultArchive(ArchivePreparationCommand command) {
        throw new IllegalStateException("Durable Tool result archive preparation is not configured");
    }

    /**
     * Runs result visibility while the persisted execution/coordinator fence is held and current.
     * Implementations must keep takeover excluded until {@code visibilityAction} returns.
     */
    default void withResultVisibilityAuthority(
            ArchivePreparationCommand command,
            Runnable visibilityAction) {
        throw new IllegalStateException("Durable Tool result visibility fencing is not configured");
    }

    /**
     * Commits one bounded page of queued conversational USER rows after every Tool pair is closed.
     * Callers must keep draining until {@link QueuedUserDrainAck#remainingCount()} is zero before
     * building the next Provider request.
     */
    default QueuedUserDrainAck drainQueuedUsers(
            LoopDurabilityScope scope,
            DurableFrontier expectedPreDrainFrontier) {
        // Compatibility for core-only durable sinks that have no queued-input producer. The
        // server master feature wires JpaConversationDurabilitySink, which overrides this method;
        // ChatService separately fails closed if its inbox service is absent.
        return new QueuedUserDrainAck(
                scope, expectedPreDrainFrontier, java.util.List.of(),
                expectedPreDrainFrontier, 0L);
    }

    static ConversationDurabilitySink disabled() {
        return DisabledConversationDurabilitySink.INSTANCE;
    }

    enum DisabledConversationDurabilitySink implements ConversationDurabilitySink {
        INSTANCE;

        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public IntentCommitAck commitIntent(IntentCommitCommand command) {
            throw new IllegalStateException("Conversation durability is disabled");
        }
    }
}
