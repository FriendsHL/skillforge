package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.Message;
import com.skillforge.server.config.SessionMessageStoreProperties;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionMessageEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.SessionMessageRepository;
import com.skillforge.server.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("SessionService write-batch identity preservation")
class SessionMessageBatchIdentityPreservationTest {

    private static final String SESSION_ID = "batch-identity-session";
    private static final String WRITE_BATCH_ID = "11111111-1111-4111-8111-111111111111";
    private static final String OLD_BATCH_ID = "22222222-2222-4222-8222-222222222222";
    private static final String NEW_BATCH_ID = "33333333-3333-4333-8333-333333333333";

    private SessionRepository sessionRepository;
    private SessionMessageRepository messageRepository;
    private SessionService sessionService;
    private SessionEntity session;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(SessionRepository.class);
        messageRepository = mock(SessionMessageRepository.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        session = new SessionEntity();
        session.setId(SESSION_ID);
        session.setUserId(1L);
        session.setAgentId(1L);
        session.setMessagesJson("[]");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any(SessionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.findNonNullTraceIdProjections(SESSION_ID)).thenReturn(List.of());
        when(messageRepository.findTopBySessionIdOrderBySeqNoDesc(SESSION_ID)).thenReturn(Optional.empty());
        when(messageRepository.countBySessionId(SESSION_ID)).thenReturn(1L);

        sessionService = new SessionService(
                sessionRepository,
                messageRepository,
                mock(AgentRepository.class),
                new SessionMessageStoreProperties(),
                new ObjectMapper(),
                transactionManager);
    }

    @Test
    @DisplayName("appendRowsOnce and toStoredMessages round-trip the complete pair without changing message shape")
    void appendAndLoad_completePair_roundTrips() {
        Message message = Message.assistant("answer");
        message.setReasoningContent("reasoning");
        SessionService.AppendMessage append = new SessionService.AppendMessage(
                message,
                SessionService.MSG_TYPE_NORMAL,
                SessionService.MESSAGE_TYPE_NORMAL,
                null,
                null,
                Map.of("source", "intent"),
                "trace-1",
                WRITE_BATCH_ID,
                0);

        sessionService.appendMessages(SESSION_ID, List.of(append));

        ArgumentCaptor<List<SessionMessageEntity>> savedRows = ArgumentCaptor.forClass(List.class);
        verify(messageRepository).saveAll(savedRows.capture());
        SessionMessageEntity row = savedRows.getValue().get(0);
        assertThat(row.getWriteBatchId()).isEqualTo(WRITE_BATCH_ID);
        assertThat(row.getWriteBatchOrdinal()).isZero();
        assertThat(row.getReasoningContent()).isEqualTo("reasoning");

        when(messageRepository.findBySessionIdOrderBySeqNoAsc(eq(SESSION_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(row)));
        SessionService.StoredMessage loaded = sessionService.getFullHistoryRecords(SESSION_ID).get(0);
        assertThat(loaded.writeBatchId()).isEqualTo(WRITE_BATCH_ID);
        assertThat(loaded.writeBatchOrdinal()).isZero();
        assertThat(loaded.traceId()).isEqualTo("trace-1");
        assertThat(loaded.metadata()).containsEntry("source", "intent");
        assertThat(loaded.message().getContent()).isEqualTo(message.getContent());
        assertThat(loaded.message().getReasoningContent()).isEqualTo("reasoning");
    }

    @Test
    @DisplayName("rewrite fills a null caller pair from the old row at the same seq")
    void rewrite_nullCallerPair_preservesOldPair() {
        when(messageRepository.findWriteBatchIdentityProjections(SESSION_ID))
                .thenReturn(List.of(batchIdentity(41L, 0L, OLD_BATCH_ID, 3)));

        sessionService.rewriteMessages(SESSION_ID, List.of(new SessionService.AppendMessage(
                Message.user("rewritten"), SessionService.MSG_TYPE_NORMAL, Collections.emptyMap())));

        SessionMessageEntity rewritten = captureOnlySavedRow();
        assertThat(rewritten.getWriteBatchId()).isEqualTo(OLD_BATCH_ID);
        assertThat(rewritten.getWriteBatchOrdinal()).isEqualTo(3);
    }

    @Test
    @DisplayName("caller-provided complete pair wins over the rewrite snapshot")
    void rewrite_explicitCallerPair_wins() {
        when(messageRepository.findWriteBatchIdentityProjections(SESSION_ID))
                .thenReturn(List.of(batchIdentity(41L, 0L, OLD_BATCH_ID, 3)));

        sessionService.rewriteMessages(SESSION_ID, List.of(new SessionService.AppendMessage(
                Message.user("rewritten"),
                SessionService.MSG_TYPE_NORMAL,
                SessionService.MESSAGE_TYPE_NORMAL,
                null, null, Collections.emptyMap(), null,
                NEW_BATCH_ID, 7)));

        SessionMessageEntity rewritten = captureOnlySavedRow();
        assertThat(rewritten.getWriteBatchId()).isEqualTo(NEW_BATCH_ID);
        assertThat(rewritten.getWriteBatchOrdinal()).isEqualTo(7);
    }

    @Test
    @DisplayName("rewrite with no old pair remains pair-null")
    void rewrite_noOldPair_remainsNull() {
        when(messageRepository.findWriteBatchIdentityProjections(SESSION_ID)).thenReturn(List.of());

        sessionService.rewriteMessages(SESSION_ID, List.of(new SessionService.AppendMessage(
                Message.user("rewritten"), SessionService.MSG_TYPE_NORMAL, Collections.emptyMap())));

        SessionMessageEntity rewritten = captureOnlySavedRow();
        assertThat(rewritten.getWriteBatchId()).isNull();
        assertThat(rewritten.getWriteBatchOrdinal()).isNull();
    }

    @Test
    @DisplayName("corrupt half-pair projection aborts before destructive rewrite")
    void rewrite_corruptHalfPair_failsClosedBeforeDelete() {
        when(messageRepository.findWriteBatchIdentityProjections(SESSION_ID))
                .thenReturn(List.of(batchIdentity(41L, 0L, OLD_BATCH_ID, null)));

        assertThatThrownBy(() -> sessionService.rewriteMessages(SESSION_ID, List.of(
                new SessionService.AppendMessage(
                        Message.user("rewritten"), SessionService.MSG_TYPE_NORMAL, Collections.emptyMap()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must both be null or both be non-null");
        verify(messageRepository, never()).deleteBySessionId(SESSION_ID);
    }

    private SessionMessageEntity captureOnlySavedRow() {
        ArgumentCaptor<List<SessionMessageEntity>> savedRows = ArgumentCaptor.forClass(List.class);
        verify(messageRepository).saveAll(savedRows.capture());
        assertThat(savedRows.getValue()).hasSize(1);
        return savedRows.getValue().get(0);
    }

    private static SessionMessageRepository.WriteBatchIdentityView batchIdentity(
            Long id, long seqNo, String batchId, Integer ordinal) {
        return new SessionMessageRepository.WriteBatchIdentityView() {
            @Override public Long getId() { return id; }
            @Override public long getSeqNo() { return seqNo; }
            @Override public String getWriteBatchId() { return batchId; }
            @Override public Integer getWriteBatchOrdinal() { return ordinal; }
        };
    }
}
