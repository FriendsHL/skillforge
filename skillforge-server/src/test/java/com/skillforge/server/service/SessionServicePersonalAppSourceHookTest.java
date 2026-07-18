package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.server.config.SessionMessageStoreProperties;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionMessageEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.ChatAttachmentRepository;
import com.skillforge.server.repository.SessionMessageRepository;
import com.skillforge.server.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionServicePersonalAppSourceHookTest {

    private static final String SESSION_ID = "session-source-hook";
    private static final String ARTIFACT_ID = "artifact-source-hook";

    private SessionRepository sessionRepository;
    private SessionMessageRepository messageRepository;
    private ChatAttachmentRepository attachmentRepository;
    private ObjectMapper objectMapper;
    private SessionService sessionService;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(SessionRepository.class);
        messageRepository = mock(SessionMessageRepository.class);
        attachmentRepository = mock(ChatAttachmentRepository.class);
        objectMapper = new ObjectMapper();
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        sessionService = new SessionService(
                sessionRepository,
                messageRepository,
                mock(AgentRepository.class),
                new SessionMessageStoreProperties(),
                objectMapper,
                transactionManager);
        sessionService.setPersonalAppSourceLinkReconciler(
                new PersonalAppSourceLinkReconciler(attachmentRepository));
    }

    @Test
    void appendPersistsRowsBeforeBindingLatestAssistantSequence() {
        SessionMessageEntity existing = new SessionMessageEntity();
        existing.setSeqNo(4L);
        when(messageRepository.findTopBySessionIdOrderBySeqNoDesc(SESSION_ID))
                .thenReturn(Optional.of(existing));
        Message typedRef = assistantRef();
        Message mapRef = assistantMapRef();

        long lastSeq = sessionService.appendNormalMessages(
                SESSION_ID, List.of(Message.user("build"), typedRef, mapRef));

        assertThat(lastSeq).isEqualTo(7L);
        InOrder order = inOrder(messageRepository, attachmentRepository);
        order.verify(messageRepository).saveAll(anyList());
        order.verify(attachmentRepository)
                .bindPersonalAppSourceMessage(SESSION_ID, ARTIFACT_ID, 7L);
        ArgumentCaptor<List<SessionMessageEntity>> rows = ArgumentCaptor.forClass(List.class);
        verify(messageRepository).saveAll(rows.capture());
        assertThat(rows.getValue()).extracting(SessionMessageEntity::getSeqNo)
                .containsExactly(5L, 6L, 7L);
    }

    @Test
    void rewriteClearsBeforeDeleteThenRemapsAndRestoredAwayRefStaysCleared() throws Exception {
        SessionEntity session = new SessionEntity();
        session.setId(SESSION_ID);
        session.setUserId(1L);
        session.setAgentId(1L);
        session.setMessagesJson("[]");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(messageRepository.findNonNullTraceIdProjections(SESSION_ID)).thenReturn(List.of());
        when(messageRepository.findTopBySessionIdOrderBySeqNoDesc(SESSION_ID))
                .thenReturn(Optional.empty());
        when(messageRepository.countBySessionId(SESSION_ID)).thenReturn(1L);
        Message ref = assistantRef();

        sessionService.rewriteMessages(SESSION_ID, List.of(append(ref)));

        InOrder remapOrder = inOrder(attachmentRepository, messageRepository);
        remapOrder.verify(attachmentRepository).clearPersonalAppSourceMessages(SESSION_ID);
        remapOrder.verify(messageRepository).deleteBySessionId(SESSION_ID);
        remapOrder.verify(messageRepository).saveAll(anyList());
        remapOrder.verify(attachmentRepository)
                .bindPersonalAppSourceMessage(SESSION_ID, ARTIFACT_ID, 0L);
        ArgumentCaptor<List<SessionMessageEntity>> remappedRows = ArgumentCaptor.forClass(List.class);
        verify(messageRepository).saveAll(remappedRows.capture());
        assertThat(remappedRows.getValue().get(0).getContentJson())
                .isEqualTo(objectMapper.writeValueAsString(ref.getContent()));

        clearInvocations(attachmentRepository, messageRepository, sessionRepository);
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(messageRepository.findNonNullTraceIdProjections(SESSION_ID)).thenReturn(List.of());
        when(messageRepository.findTopBySessionIdOrderBySeqNoDesc(SESSION_ID))
                .thenReturn(Optional.empty());
        when(messageRepository.countBySessionId(SESSION_ID)).thenReturn(1L);

        sessionService.rewriteMessages(
                SESSION_ID, List.of(append(Message.user("restored before artifact"))));

        InOrder restoreOrder = inOrder(attachmentRepository, messageRepository);
        restoreOrder.verify(attachmentRepository).clearPersonalAppSourceMessages(SESSION_ID);
        restoreOrder.verify(messageRepository).deleteBySessionId(SESSION_ID);
        restoreOrder.verify(messageRepository).saveAll(anyList());
        verify(attachmentRepository, never())
                .bindPersonalAppSourceMessage(any(), any(), anyLong());
    }

    private static SessionService.AppendMessage append(Message message) {
        return new SessionService.AppendMessage(
                message, SessionService.MSG_TYPE_NORMAL, Map.of());
    }

    private static Message assistantRef() {
        Message message = new Message();
        message.setRole(Message.Role.ASSISTANT);
        message.setContent(List.of(ContentBlock.interactiveArtifactRef(
                ARTIFACT_ID, "app.html", "App", 1)));
        return message;
    }

    private static Message assistantMapRef() {
        Message message = new Message();
        message.setRole(Message.Role.ASSISTANT);
        message.setContent(List.of(Map.of(
                "type", "interactive_artifact_ref",
                "attachmentId", ARTIFACT_ID)));
        return message;
    }
}
