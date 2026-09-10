package com.skillforge.server.session;

import com.skillforge.core.engine.durability.ArchivePreparationCommand;
import com.skillforge.core.engine.durability.LoopDurabilityScope;
import com.skillforge.server.repository.SessionMessageRepository;
import com.skillforge.server.repository.SessionMessageInboxRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SessionToolAttemptRepository;
import com.skillforge.server.session.persistence.PersistedMessageCodec;
import com.skillforge.server.session.persistence.SessionOrderedMessageWriter;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Keeps infrastructure outages retryable instead of turning them into protocol corruption. */
class DurableTransientFailureClassificationTest {

    @Test
    void archivePrepareAndFallbackOutageRemainsRetryable() {
        PlatformTransactionManager transactions = unavailableTransactions();
        OccurrenceArchivePreparation preparation = new OccurrenceArchivePreparation(
                mock(SessionRepository.class),
                mock(SessionMessageRepository.class),
                mock(SessionToolAttemptRepository.class),
                mock(PersistedMessageCodec.class),
                mock(ToolResultOccurrenceArchiveWriter.class),
                mock(EntityManager.class),
                transactions);

        assertThatThrownBy(() -> preparation.ensurePrepared(
                mock(ArchivePreparationCommand.class)))
                .isInstanceOf(DurableRecoveryRetryableException.class)
                .hasNoCause();
        verify(transactions, times(4)).getTransaction(any());
    }

    @Test
    void completionOutageRemainsRetryable() {
        SessionDurableCompletionReconciler reconciler = new SessionDurableCompletionReconciler(
                mock(SessionRepository.class),
                mock(SessionMessageRepository.class),
                mock(SessionToolAttemptRepository.class),
                mock(SessionMessageInboxRepository.class),
                mock(SessionOrderedMessageWriter.class),
                unavailableTransactions());

        assertThatThrownBy(() -> reconciler.reconcile(
                mock(LoopDurabilityScope.class), null, null, null, null))
                .isInstanceOf(DurableRecoveryRetryableException.class)
                .hasNoCause();
    }

    @Test
    void heartbeatRenewalOutageRemainsRetryable() {
        SessionLoopAdmissionService admission = new SessionLoopAdmissionService(
                mock(SessionRepository.class),
                mock(SessionMessageRepository.class),
                mock(SessionToolAttemptRepository.class),
                mock(SessionOrderedMessageWriter.class),
                unavailableTransactions(), new com.fasterxml.jackson.databind.ObjectMapper());

        assertThatThrownBy(() -> admission.renew(mock(LoopDurabilityScope.class)))
                .isInstanceOf(DurableRecoveryRetryableException.class)
                .hasNoCause();
    }

    private static PlatformTransactionManager unavailableTransactions() {
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenThrow(
                new CannotCreateTransactionException("database unavailable"));
        return transactions;
    }
}
