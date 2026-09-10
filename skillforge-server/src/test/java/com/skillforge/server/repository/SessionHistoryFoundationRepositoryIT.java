package com.skillforge.server.repository;

import com.skillforge.server.AbstractPostgresIT;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionMessageEntity;
import com.skillforge.server.entity.SessionMessageInboxEntity;
import com.skillforge.server.entity.SessionToolAttemptEntity;
import com.skillforge.server.entity.SessionToolAttemptResolutionAuditEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Session History foundation repositories")
class SessionHistoryFoundationRepositoryIT extends AbstractPostgresIT {

    @Autowired private SessionRepository sessionRepository;
    @Autowired private SessionMessageRepository messageRepository;
    @Autowired private SessionMessageInboxRepository inboxRepository;
    @Autowired private SessionToolAttemptRepository attemptRepository;
    @Autowired private SessionToolAttemptResolutionAuditRepository auditRepository;

    private String sessionId;
    private SessionMessageEntity assistantMessage;

    @BeforeEach
    void setUp() {
        sessionId = UUID.randomUUID().toString();
        SessionEntity session = new SessionEntity();
        session.setId(sessionId);
        session.setUserId(1L);
        session.setAgentId(10L);
        session.setStatus("active");
        session.setRuntimeStatus("idle");
        sessionRepository.save(session);

        SessionMessageEntity message = new SessionMessageEntity();
        message.setSessionId(sessionId);
        message.setSeqNo(0L);
        message.setRole("assistant");
        message.setMsgType("NORMAL");
        message.setMessageType("normal");
        message.setContentJson("[{\"type\":\"tool_use\",\"id\":\"toolu_1\"}]");
        assistantMessage = messageRepository.save(message);
    }

    @Test
    @DisplayName("inbox query returns database acceptance id order")
    void inboxQuery_ordersByDatabaseId() {
        SessionMessageInboxEntity first = newInbox("first");
        SessionMessageInboxEntity second = newInbox("second");
        inboxRepository.saveAll(List.of(first, second));

        List<SessionMessageInboxEntity> found = inboxRepository.findBySessionIdOrderByIdAsc(
                sessionId, PageRequest.of(0, 10));

        assertThat(found).extracting(SessionMessageInboxEntity::getInboxId)
                .containsExactly(first.getInboxId(), second.getInboxId());
        assertThat(inboxRepository.countBySessionId(sessionId)).isEqualTo(2L);
    }

    @Test
    @DisplayName("attempt lookup keeps immutable origin and mutable execution identity distinct")
    void attemptLookup_roundTripsOriginAndExecution() {
        SessionToolAttemptEntity attempt = newAttempt();
        attempt.setState("EXECUTING");
        attempt.setExecutionLoopId("execution-loop");
        attempt.setExecutionFence(9L);
        attempt.setExecutionOwnerInstanceId("instance-a");
        attempt.setExecutionGeneration(1L);
        attempt.setClaimRequestId(UUID.randomUUID());
        attempt.setClaimedAt(java.time.Instant.now());
        attempt.setExecutionLeaseUntil(java.time.Instant.now().plusSeconds(60));
        attempt = attemptRepository.saveAndFlush(attempt);

        SessionToolAttemptEntity found = attemptRepository
                .findBySessionIdAndStepId(sessionId, attempt.getStepId()).orElseThrow();

        assertThat(found.getOriginLoopId()).isEqualTo("origin-loop");
        assertThat(found.getOriginFence()).isEqualTo(4L);
        assertThat(found.getExecutionLoopId()).isEqualTo("execution-loop");
        assertThat(found.getExecutionFence()).isEqualTo(9L);
        assertThat(found.getExecutionGeneration()).isEqualTo(1L);
        assertThat(found.getPostActionState()).isEqualTo("NONE");
        assertThat(found.getArchivePreparationState()).isEqualTo("NOT_STARTED");
    }

    @Test
    @DisplayName("resolution audit survives deletion of its scalar attempt target")
    void resolutionAudit_survivesAttemptPruning() {
        SessionToolAttemptEntity attempt = attemptRepository.saveAndFlush(newAttempt());
        UUID resolutionRequestId = UUID.randomUUID();
        UUID resultBatchId = UUID.randomUUID();

        SessionToolAttemptResolutionAuditEntity audit = new SessionToolAttemptResolutionAuditEntity();
        audit.setResolutionRequestId(resolutionRequestId);
        audit.setSessionId(sessionId);
        audit.setAttemptId(attempt.getId());
        audit.setStepId(attempt.getStepId());
        audit.setHistoryEpoch(attempt.getHistoryEpoch());
        audit.setExecutionGeneration(2L);
        audit.setExecutionFence(12L);
        audit.setActorId(1L);
        audit.setActorAuthority("OWNER");
        audit.setReasonHash("d".repeat(64));
        audit.setAction("CONTINUE_CURRENT_TIMELINE");
        audit.setInboxDispositionsJson("[]");
        audit.setResultBatchId(resultBatchId);
        audit.setOutcomeState("RESOLVED_UNKNOWN");
        auditRepository.save(audit);

        attemptRepository.delete(attempt);
        attemptRepository.flush();

        SessionToolAttemptResolutionAuditEntity found = auditRepository
                .findByResolutionRequestId(resolutionRequestId).orElseThrow();
        assertThat(found.getAttemptId()).isEqualTo(attempt.getId());
        assertThat(found.getResultBatchId()).isEqualTo(resultBatchId);
    }

    private SessionMessageInboxEntity newInbox(String content) {
        SessionMessageInboxEntity inbox = new SessionMessageInboxEntity();
        inbox.setInboxId(UUID.randomUUID());
        inbox.setSessionId(sessionId);
        inbox.setUserId(1L);
        inbox.setMessageJson("{\"role\":\"user\",\"content\":\"" + content + "\"}");
        return inbox;
    }

    private SessionToolAttemptEntity newAttempt() {
        SessionToolAttemptEntity attempt = new SessionToolAttemptEntity();
        attempt.setSessionId(sessionId);
        attempt.setStepId(UUID.randomUUID());
        attempt.setHistoryEpoch(0L);
        attempt.setOriginLoopId("origin-loop");
        attempt.setOriginFence(4L);
        attempt.setAssistantMessageId(assistantMessage.getId());
        attempt.setAssistantPayloadHash("a".repeat(64));
        attempt.setPreIntentMaxMessageId(-1L);
        attempt.setPreIntentMaxSeq(-1L);
        attempt.setManifestJson("[]");
        attempt.setManifestHash("b".repeat(64));
        attempt.setReplaySafety("UNKNOWN");
        attempt.setState("INTENT_COMMITTED");
        return attempt;
    }
}
