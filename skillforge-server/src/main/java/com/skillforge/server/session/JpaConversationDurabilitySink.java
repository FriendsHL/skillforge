package com.skillforge.server.session;

import com.skillforge.core.engine.durability.ArchivePreparationAck;
import com.skillforge.core.engine.durability.ArchivePreparationCommand;
import com.skillforge.core.engine.durability.ConversationDurabilitySink;
import com.skillforge.core.engine.durability.ExecutionClaimAck;
import com.skillforge.core.engine.durability.ExecutionClaimCommand;
import com.skillforge.core.engine.durability.IntentCommitAck;
import com.skillforge.core.engine.durability.IntentCommitCommand;
import com.skillforge.core.engine.durability.InteractiveIntentAck;
import com.skillforge.core.engine.durability.InteractiveIntentCommand;
import com.skillforge.core.engine.durability.ToolResultCommitAck;
import com.skillforge.core.engine.durability.ToolResultCommitCommand;
import com.skillforge.core.engine.durability.QueuedUserDrainAck;
import com.skillforge.core.engine.durability.QueuedUserDrainItem;
import com.skillforge.core.engine.durability.DurableFrontier;
import com.skillforge.core.engine.durability.LoopDurabilityScope;
import com.skillforge.server.config.SessionHistoryProperties;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.function.Supplier;

/** Production adapter from the core durability protocol to PostgreSQL transactions. */
@Service
public class JpaConversationDurabilitySink implements ConversationDurabilitySink {

    private final SessionHistoryProperties properties;
    private final SessionToolAttemptTransactionService attemptTransactions;
    private final SessionInteractiveControlTransactionService interactiveTransactions;
    private final OccurrenceArchivePreparation archivePreparation;
    private final SessionQueuedUserInboxService queuedUserInbox;

    public JpaConversationDurabilitySink(
            SessionHistoryProperties properties,
            SessionToolAttemptTransactionService attemptTransactions,
            SessionInteractiveControlTransactionService interactiveTransactions,
            OccurrenceArchivePreparation archivePreparation,
            SessionQueuedUserInboxService queuedUserInbox) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.attemptTransactions = Objects.requireNonNull(
                attemptTransactions, "attemptTransactions");
        this.interactiveTransactions = Objects.requireNonNull(
                interactiveTransactions, "interactiveTransactions");
        this.archivePreparation = Objects.requireNonNull(
                archivePreparation, "archivePreparation");
        this.queuedUserInbox = Objects.requireNonNull(
                queuedUserInbox, "queuedUserInbox");
    }

    @Override
    public boolean enabled() {
        return properties.isEnabled();
    }

    @Override
    public IntentCommitAck commitIntent(IntentCommitCommand command) {
        requireEnabled();
        return exactRetry(() -> attemptTransactions.commitIntent(command));
    }

    @Override
    public InteractiveIntentAck commitInteractiveIntent(InteractiveIntentCommand command) {
        requireEnabled();
        SessionInteractiveControlTransactionService.InteractiveIntentAck ack = exactRetry(
                () -> interactiveTransactions.commitWaitingIntent(
                        new SessionInteractiveControlTransactionService.InteractiveIntentCommand(
                                command.intent(), command.plan(), command.controlId(),
                                command.displayText(), command.payload())));
        return new InteractiveIntentAck(ack.intent(), ack.control(), ack.selectedControl());
    }

    @Override
    public ExecutionClaimAck claimExecution(ExecutionClaimCommand command) {
        requireEnabled();
        return exactRetry(() -> attemptTransactions.claimExecution(command));
    }

    @Override
    public ToolResultCommitAck commitResults(ToolResultCommitCommand command) {
        requireEnabled();
        return exactRetry(() -> attemptTransactions.commitResults(command));
    }

    @Override
    public ArchivePreparationAck prepareResultArchive(ArchivePreparationCommand command) {
        requireEnabled();
        return archivePreparation.ensurePrepared(command);
    }

    @Override
    public void withResultVisibilityAuthority(
            ArchivePreparationCommand command,
            Runnable visibilityAction) {
        requireEnabled();
        archivePreparation.withResultVisibilityAuthority(command, visibilityAction);
    }

    @Override
    public QueuedUserDrainAck drainQueuedUsers(
            LoopDurabilityScope scope,
            DurableFrontier expectedPreDrainFrontier) {
        requireEnabled();
        SessionQueuedUserInboxService.DrainAck ack = exactRetry(
                () -> queuedUserInbox.drain(scope, expectedPreDrainFrontier));
        return new QueuedUserDrainAck(
                ack.scope(),
                ack.preDrainFrontier(),
                ack.items().stream()
                        .map(item -> new QueuedUserDrainItem(
                                item.inboxId(), item.message()))
                        .toList(),
                ack.postDrainFrontier(),
                ack.remainingCount());
    }

    private void requireEnabled() {
        if (!enabled()) {
            throw new IllegalStateException("Conversation durability is disabled");
        }
    }

    private static <T> T exactRetry(Supplier<T> operation) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return operation.get();
            } catch (IllegalStateException failure) {
                if (attempt == 2) throw failure;
            }
        }
        throw new IllegalStateException("Unreachable durable retry state");
    }
}
