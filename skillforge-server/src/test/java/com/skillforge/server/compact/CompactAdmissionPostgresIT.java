package com.skillforge.server.compact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.core.model.Message;
import com.skillforge.server.AbstractPostgresIT;
import com.skillforge.server.config.SessionHistoryProperties;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionMessageEntity;
import com.skillforge.server.repository.SessionMessageInboxRepository;
import com.skillforge.server.repository.SessionMessageRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SessionSummaryRepository;
import com.skillforge.server.repository.SessionToolAttemptRepository;
import com.skillforge.server.service.CompactAdmissionService;
import com.skillforge.server.service.SessionService;
import com.skillforge.server.session.persistence.PersistedMessageCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** PostgreSQL gates for the Compact Phase-1/Phase-3 durable admission token. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CompactAdmissionPostgresIT extends AbstractPostgresIT {

    @Autowired private SessionRepository sessionRepository;
    @Autowired private SessionMessageRepository messageRepository;
    @Autowired private SessionSummaryRepository summaryRepository;
    @Autowired private SessionToolAttemptRepository attemptRepository;
    @Autowired private SessionMessageInboxRepository inboxRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<String> sessionIds = new ArrayList<>();
    private PersistedMessageCodec messageCodec;
    private CompactAdmissionService admissionService;
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .build();
        messageCodec = new PersistedMessageCodec(objectMapper);
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.getContextMessages(anyString())).thenAnswer(invocation ->
                loadModelMessages(invocation.getArgument(0)));
        SessionHistoryProperties properties = new SessionHistoryProperties();
        properties.setEnabled(true);
        admissionService = new CompactAdmissionService(
                properties,
                sessionRepository,
                messageRepository,
                summaryRepository,
                attemptRepository,
                inboxRepository,
                messageCodec,
                objectMapper,
                sessionService);
        transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void removeSessions() {
        for (String sessionId : sessionIds) {
            jdbcTemplate.update("DELETE FROM t_session WHERE id = ?", sessionId);
        }
    }

    @Test
    @DisplayName("a committed concurrent append makes the Phase-1 token stale on real PostgreSQL")
    void revalidate_concurrentCommittedAppend_rejectsStaleSnapshot() {
        String sessionId = createSession();
        CompactAdmissionService.AdmissionSnapshot snapshot = transactionTemplate.execute(
                ignored -> admissionService.capture(sessionId, "full", "engine-hard"));

        transactionTemplate.executeWithoutResult(ignored -> {
            SessionEntity locked = sessionRepository.findByIdForUpdate(sessionId).orElseThrow();
            appendRow(sessionId, 1L, "concurrent append");
            locked.setMessageCount(2);
            sessionRepository.save(locked);
        });

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(
                ignored -> admissionService.revalidate(snapshot)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Compact admission snapshot is stale");
    }

    @Test
    @DisplayName("the Phase-3 revalidation keeps the Session row locked until its transaction ends")
    void revalidate_phase3Transaction_serializesCompliantConcurrentWriter() throws Exception {
        String sessionId = createSession();
        CompactAdmissionService.AdmissionSnapshot snapshot = transactionTemplate.execute(
                ignored -> admissionService.capture(sessionId, "full", "engine-hard"));
        CountDownLatch revalidated = new CountDownLatch(1);
        CountDownLatch releasePhase3 = new CountDownLatch(1);
        CountDownLatch writerReady = new CountDownLatch(1);
        CountDownLatch writerAcquired = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> phase3 = null;
        Future<?> writer = null;
        try {
            phase3 = executor.submit(() -> transactionTemplate.executeWithoutResult(ignored -> {
                admissionService.revalidate(snapshot);
                revalidated.countDown();
                await(releasePhase3);
            }));
            assertThat(revalidated.await(10, TimeUnit.SECONDS)).isTrue();

            writer = executor.submit(() -> transactionTemplate.executeWithoutResult(ignored -> {
                writerReady.countDown();
                sessionRepository.findByIdForUpdate(sessionId).orElseThrow();
                writerAcquired.countDown();
            }));

            assertThat(writerReady.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(writerAcquired.await(250, TimeUnit.MILLISECONDS))
                    .as("a compliant writer must wait for the Phase-3 Session lock")
                    .isFalse();
            releasePhase3.countDown();
            phase3.get(10, TimeUnit.SECONDS);
            writer.get(10, TimeUnit.SECONDS);
            assertThat(writerAcquired.getCount()).isZero();
        } finally {
            releasePhase3.countDown();
            if (phase3 != null && !phase3.isDone()) phase3.cancel(true);
            if (writer != null && !writer.isDone()) writer.cancel(true);
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private String createSession() {
        String sessionId = UUID.randomUUID().toString();
        sessionIds.add(sessionId);
        SessionEntity session = new SessionEntity();
        session.setId(sessionId);
        session.setUserId(701L);
        session.setAgentId(1L);
        session.setTitle("compact-admission-postgres");
        session.setStatus("active");
        session.setRuntimeStatus("idle");
        session.setMessageCount(1);
        session.setHistoryEpoch(4L);
        session.setLoopFence(9L);
        sessionRepository.save(session);
        appendRow(sessionId, 0L, "durable prefix");
        return sessionId;
    }

    private void appendRow(String sessionId, long seqNo, String text) {
        PersistedMessageCodec.EncodedRow encoded = messageCodec.encodeRow(
                new PersistedMessageCodec.PersistedMessage(
                        Message.user(text), "NORMAL", "normal", null, java.util.Map.of()));
        SessionMessageEntity row = new SessionMessageEntity();
        row.setSessionId(sessionId);
        row.setSeqNo(seqNo);
        row.setRole(encoded.role());
        row.setContentJson(encoded.contentJson());
        row.setReasoningContent(encoded.reasoningContent());
        row.setMsgType(encoded.msgType());
        row.setMessageType(encoded.messageType());
        row.setMetadataJson(encoded.metadataJson());
        messageRepository.saveAndFlush(row);
    }

    private List<Message> loadModelMessages(String sessionId) {
        return messageRepository.findBySessionIdOrderBySeqNoAsc(
                        sessionId, PageRequest.of(0, 500))
                .stream()
                .map(row -> messageCodec.decodeRow(new PersistedMessageCodec.EncodedRow(
                        row.getRole(),
                        row.getContentJson(),
                        row.getReasoningContent(),
                        row.getMsgType(),
                        row.getMessageType(),
                        row.getControlId(),
                        row.getAnsweredAt(),
                        row.getMetadataJson(),
                        row.getTraceId())).message())
                .toList();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for Compact concurrency gate");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for Compact concurrency gate", interrupted);
        }
    }
}
