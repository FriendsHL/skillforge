package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.Message;
import com.skillforge.server.AbstractPostgresIT;
import com.skillforge.server.config.SessionMessageStoreProperties;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.SessionMessageRepository;
import com.skillforge.server.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SessionService write-batch identity PostgreSQL rewrite")
class SessionMessageBatchIdentityPreservationIT extends AbstractPostgresIT {

    private static final String OLD_BATCH_ID = "22222222-2222-4222-8222-222222222222";
    private static final String NEW_BATCH_ID = "33333333-3333-4333-8333-333333333333";

    @Autowired private SessionRepository sessionRepository;
    @Autowired private SessionMessageRepository messageRepository;
    @Autowired private AgentRepository agentRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    private SessionService sessionService;

    @BeforeEach
    void setUp() {
        messageRepository.deleteAll();
        sessionRepository.deleteAll();
        sessionService = new SessionService(
                sessionRepository,
                messageRepository,
                agentRepository,
                new SessionMessageStoreProperties(),
                new ObjectMapper(),
                transactionManager);
    }

    @Test
    @DisplayName("rewrite preserves the old complete pair and persists it after reload")
    void rewrite_nullCallerPair_preservesPersistedPair() {
        String sessionId = newSession();
        sessionService.appendMessages(sessionId, List.of(explicit("original", OLD_BATCH_ID, 0)));

        sessionService.rewriteMessages(sessionId, List.of(new SessionService.AppendMessage(
                Message.user("rewritten"), SessionService.MSG_TYPE_NORMAL, Collections.emptyMap())));

        SessionService.StoredMessage stored = sessionService.getFullHistoryRecords(sessionId).get(0);
        assertThat(stored.message().getTextContent()).isEqualTo("rewritten");
        assertThat(stored.writeBatchId()).isEqualTo(OLD_BATCH_ID);
        assertThat(stored.writeBatchOrdinal()).isZero();
    }

    @Test
    @DisplayName("explicit rewrite pair wins and sessions without an old pair remain pair-null")
    void rewrite_explicitPairWins_andNoOldPairIsNoOp() {
        String explicitSession = newSession();
        sessionService.appendMessages(explicitSession, List.of(explicit("original", OLD_BATCH_ID, 0)));
        sessionService.rewriteMessages(explicitSession, List.of(explicit("rewritten", NEW_BATCH_ID, 8)));

        SessionService.StoredMessage explicit = sessionService.getFullHistoryRecords(explicitSession).get(0);
        assertThat(explicit.writeBatchId()).isEqualTo(NEW_BATCH_ID);
        assertThat(explicit.writeBatchOrdinal()).isEqualTo(8);

        String legacySession = newSession();
        sessionService.rewriteMessages(legacySession, List.of(new SessionService.AppendMessage(
                Message.user("legacy"), SessionService.MSG_TYPE_NORMAL, Collections.emptyMap())));
        SessionService.StoredMessage legacy = sessionService.getFullHistoryRecords(legacySession).get(0);
        assertThat(legacy.writeBatchId()).isNull();
        assertThat(legacy.writeBatchOrdinal()).isNull();
    }

    private String newSession() {
        SessionEntity session = new SessionEntity();
        session.setId(UUID.randomUUID().toString());
        session.setUserId(1L);
        session.setAgentId(1L);
        session.setTitle("write-batch-identity-it");
        session.setStatus("active");
        session.setRuntimeStatus("idle");
        return sessionRepository.save(session).getId();
    }

    private static SessionService.AppendMessage explicit(
            String text, String batchId, int ordinal) {
        return new SessionService.AppendMessage(
                Message.user(text),
                SessionService.MSG_TYPE_NORMAL,
                SessionService.MESSAGE_TYPE_NORMAL,
                null,
                null,
                Collections.emptyMap(),
                null,
                batchId,
                ordinal);
    }
}
