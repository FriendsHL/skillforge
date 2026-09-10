package com.skillforge.server.session;

import com.skillforge.core.engine.durability.MessageSnapshot;
import com.skillforge.core.model.Message;
import com.skillforge.server.AbstractPostgresIT;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.session.persistence.PersistedMessageCodec;
import com.skillforge.server.session.persistence.SessionOrderedMessageWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** PostgreSQL gates for atomic loop claim plus exact first USER persistence. */
@Import({
        JacksonAutoConfiguration.class,
        PersistedMessageCodec.class,
        SessionOrderedMessageWriter.class,
        SessionLoopAdmissionService.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SessionLoopAdmissionServicePostgresIT extends AbstractPostgresIT {

    @Autowired private SessionRepository sessionRepository;
    @Autowired private SessionLoopAdmissionService admissionService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private com.skillforge.server.repository.SessionMessageRepository messageRepository;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private String sessionIdToRemove;

    @AfterEach
    void cleanFixture() {
        if (sessionIdToRemove != null) {
            jdbcTemplate.update("DELETE FROM t_session WHERE id = ?", sessionIdToRemove);
        }
    }

    @Test
    void legacyOnlyAdmissionFailsWithoutCreatingRowsOrClaimingLoop() {
        String sessionId = createSession();
        jdbcTemplate.update("UPDATE t_session SET messages_json = ? WHERE id = ?",
                "[{\"role\":\"user\",\"content\":\"old exact fact\"}]", sessionId);

        Throwable failure = catchThrowable(() -> admissionService.admit(
                sessionId, 1201L, UUID.randomUUID(), UUID.randomUUID().toString(),
                MessageSnapshot.capture(Message.user("continue")), "trace"));

        assertThat(failure).isInstanceOf(IllegalStateException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM t_session_message WHERE session_id = ?",
                Long.class, sessionId)).isZero();
        assertThat(jdbcTemplate.queryForMap(
                "SELECT active_loop_id, loop_fence FROM t_session WHERE id = ?", sessionId))
                .containsEntry("active_loop_id", null)
                .containsEntry("loop_fence", 0L);
    }

    @Test
    void verifiedLegacyPrefixAllowsAppendButMixedRowsAreRejected() {
        String sessionId = createSession();
        SessionLoopAdmissionService.AdmissionAck first = admissionService.admit(
                sessionId, 1201L, UUID.randomUUID(), UUID.randomUUID().toString(),
                MessageSnapshot.capture(Message.user("old exact fact")), "trace");
        admissionService.release(first.scope());
        jdbcTemplate.update("UPDATE t_session SET messages_json = ? WHERE id = ?",
                "[{\"role\":\"user\",\"content\":\"old exact fact\"}]", sessionId);
        SessionLoopAdmissionService.AdmissionAck second = admissionService.admit(
                sessionId, 1201L, UUID.randomUUID(), UUID.randomUUID().toString(),
                MessageSnapshot.capture(Message.user("continue")), "trace-2");
        assertThat(second.userMessage().seqNo()).isEqualTo(1L);
        admissionService.release(second.scope());
        jdbcTemplate.update("UPDATE t_session SET messages_json = ? WHERE id = ?",
                "[{\"role\":\"user\",\"content\":\"missing legacy fact\"}]", sessionId);

        Throwable failure = catchThrowable(() -> admissionService.admit(
                sessionId, 1201L, UUID.randomUUID(), UUID.randomUUID().toString(),
                MessageSnapshot.capture(Message.user("must not append")), "trace-3"));
        assertThat(failure).isInstanceOf(IllegalStateException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM t_session_message WHERE session_id = ?",
                Long.class, sessionId)).isEqualTo(2L);
    }

    @Test
    void pruneThenRestoreMirrorRefreshStillAllowsDurableAdmission() {
        String sessionId = createSession();
        String raw = "[{\"type\":\"text\",\"text\":\"preserve text\"},"
                + "{\"type\":\"tool_result\",\"tool_use_id\":\"call\",\"content\":\"exact raw output\"}]";
        jdbcTemplate.update("""
                INSERT INTO t_session_message
                    (session_id, seq_no, role, content_json, msg_type, message_type, created_at)
                VALUES (?, 0, 'user', ?, 'NORMAL', 'normal', CURRENT_TIMESTAMP)
                """, sessionId, raw);
        com.skillforge.server.service.SessionService sessionService =
                new com.skillforge.server.service.SessionService(sessionRepository, messageRepository,
                        org.mockito.Mockito.mock(com.skillforge.server.repository.AgentRepository.class),
                        new com.skillforge.server.config.SessionMessageStoreProperties(), objectMapper,
                        transactionManager);
        assertThat(sessionService.pruneToolOutputs(sessionId, 1)).isEqualTo(1);
        // Execute the same mirror-refresh step used after checkpoint restore suffix pruning.
        new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .executeWithoutResult(ignored -> {
                    SessionEntity locked = sessionRepository.findByIdForUpdate(sessionId).orElseThrow();
                    messageRepository.deleteBySessionIdAndSeqNoGreaterThan(sessionId, 0L);
                    locked.setHistoryEpoch(locked.getHistoryEpoch() + 1L);
                    org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                            sessionService, "refreshLegacyMessageMirrorFromRows", locked);
                    sessionRepository.saveAndFlush(locked);
                });
        String mirror = sessionRepository.findById(sessionId).orElseThrow().getMessagesJson();
        assertThat(mirror).contains("[TOOL OUTPUT PRUNED]", "preserve text")
                .doesNotContain("exact raw output");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT content_json FROM t_session_message WHERE session_id = ?", String.class, sessionId))
                .isEqualTo(raw);
        SessionLoopAdmissionService.AdmissionAck admitted = admissionService.admit(
                sessionId, 1201L, UUID.randomUUID(), UUID.randomUUID().toString(),
                MessageSnapshot.capture(Message.user("continue after restore")), "trace");
        assertThat(admitted.userMessage().seqNo()).isEqualTo(1L);
    }

    @Test
    void claimAndUserAppendAreAtomic_andAckLossRetryReturnsSameIdentity() {
        String sessionId = createSession();
        UUID requestId = UUID.randomUUID();
        String loopId = UUID.randomUUID().toString();
        MessageSnapshot user = MessageSnapshot.capture(Message.user("exact user turn"));

        SessionLoopAdmissionService.AdmissionAck first = admissionService.admit(
                sessionId, 1201L, requestId, loopId, user, "user-trace");
        SessionLoopAdmissionService.AdmissionAck retry = admissionService.admit(
                sessionId, 1201L, requestId, loopId, user, "user-trace");

        assertThat(retry).isEqualTo(first);
        assertThat(first.scope().sessionId()).isEqualTo(sessionId);
        assertThat(first.scope().historyEpoch()).isEqualTo(3L);
        assertThat(first.scope().loopId()).isEqualTo(loopId);
        assertThat(first.scope().loopFence()).isEqualTo(1L);
        assertThat(first.scope().ownerInstanceId()).isNotBlank();
        assertThat(first.userMessage().writeBatchId()).isEqualTo(requestId.toString());
        assertThat(first.userMessage().writeBatchOrdinal()).isZero();
        assertThat(first.frontier().maxMessageId()).isEqualTo(first.userMessage().messageId());
        assertThat(first.frontier().maxSeq()).isEqualTo(first.userMessage().seqNo());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM t_session_message WHERE session_id = ?",
                Long.class, sessionId)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT active_loop_id, loop_fence, loop_owner_instance_id,
                       loop_lease_until, runtime_status, runtime_step, message_count
                FROM t_session WHERE id = ?
                """, sessionId))
                .containsEntry("active_loop_id", loopId)
                .containsEntry("loop_fence", 1L)
                .containsEntry("loop_owner_instance_id", first.scope().ownerInstanceId())
                .containsEntry("runtime_status", "running")
                .containsEntry("runtime_step", "Starting")
                .containsEntry("message_count", 1);
    }

    @Test
    void differentAdmissionDuringLiveLeaseIsRejected_withoutAppendingASecondUser() {
        String sessionId = createSession();
        admissionService.admit(
                sessionId, 1201L, UUID.randomUUID(), UUID.randomUUID().toString(),
                MessageSnapshot.capture(Message.user("first")), "trace-1");

        Throwable failure = catchThrowable(() -> admissionService.admit(
                sessionId, 1201L, UUID.randomUUID(), UUID.randomUUID().toString(),
                MessageSnapshot.capture(Message.user("second")), "trace-2"));

        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Durable loop admission failed")
                .hasNoCause();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM t_session_message WHERE session_id = ?",
                Long.class, sessionId)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT content_json FROM t_session_message WHERE session_id = ?",
                String.class, sessionId)).isEqualTo("\"first\"");
    }

    @Test
    void currentOwnerCanRenewExpiredLease_withoutChangingFenceOrTranscript() {
        String sessionId = createSession();
        SessionLoopAdmissionService.AdmissionAck admitted = admissionService.admit(
                sessionId, 1201L, UUID.randomUUID(), UUID.randomUUID().toString(),
                MessageSnapshot.capture(Message.user("first")), "trace-1");
        jdbcTemplate.update(
                "UPDATE t_session SET loop_lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second' "
                        + "WHERE id = ?",
                sessionId);

        SessionLoopAdmissionService.LeaseAck renewed =
                admissionService.renew(admitted.scope());

        assertThat(renewed.scope()).isEqualTo(admitted.scope());
        assertThat(renewed.leaseUntil()).isAfter(renewed.databaseTime());
        assertThat(jdbcTemplate.queryForMap("""
                SELECT active_loop_id, loop_fence, loop_owner_instance_id
                FROM t_session WHERE id = ?
                """, sessionId))
                .containsEntry("active_loop_id", admitted.scope().loopId())
                .containsEntry("loop_fence", admitted.scope().loopFence())
                .containsEntry("loop_owner_instance_id", admitted.scope().ownerInstanceId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM t_session_message WHERE session_id = ?",
                Long.class, sessionId)).isEqualTo(1L);
    }

    @Test
    void expiredLoopCanBeClaimedForRecovery_withoutAppendingOrRewritingTranscript() {
        String sessionId = createSession();
        SessionLoopAdmissionService.AdmissionAck admitted = admissionService.admit(
                sessionId, 1201L, UUID.randomUUID(), UUID.randomUUID().toString(),
                MessageSnapshot.capture(Message.user("persisted before crash")), "trace-1");
        jdbcTemplate.update("""
                UPDATE t_session
                SET loop_owner_instance_id = 'dead-instance',
                    loop_lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE id = ?
                """, sessionId);
        String recoveryLoopId = UUID.randomUUID().toString();

        SessionLoopAdmissionService.RecoveryAdmissionAck recovered =
                admissionService.claimRecovery(sessionId, 1201L, recoveryLoopId);

        assertThat(recovered.attemptId()).isNull();
        assertThat(recovered.scope().loopId()).isEqualTo(recoveryLoopId);
        assertThat(recovered.scope().loopFence()).isEqualTo(admitted.scope().loopFence() + 1L);
        assertThat(recovered.scope().ownerInstanceId()).isNotEqualTo("dead-instance");
        assertThat(jdbcTemplate.queryForMap("""
                SELECT active_loop_id, loop_fence, loop_owner_instance_id,
                       runtime_status, runtime_step
                FROM t_session WHERE id = ?
                """, sessionId))
                .containsEntry("active_loop_id", recoveryLoopId)
                .containsEntry("loop_fence", recovered.scope().loopFence())
                .containsEntry("loop_owner_instance_id", recovered.scope().ownerInstanceId())
                .containsEntry("runtime_status", "running")
                .containsEntry("runtime_step", "Recovering");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM t_session_message WHERE session_id = ?",
                Long.class, sessionId)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT content_json FROM t_session_message WHERE session_id = ?",
                String.class, sessionId)).isEqualTo("\"persisted before crash\"");
    }

    @Test
    void nonUserOrToolResultAdmissionPayloadIsRejectedBeforeSessionMutation() {
        String sessionId = createSession();

        Throwable failure = catchThrowable(() -> admissionService.admit(
                sessionId, 1201L, UUID.randomUUID(), UUID.randomUUID().toString(),
                MessageSnapshot.capture(Message.toolResult("tool-1", "illegal", false)),
                "trace-illegal"));

        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Durable loop admission failed")
                .hasNoCause();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM t_session_message WHERE session_id = ?",
                Long.class, sessionId)).isZero();
        assertThat(jdbcTemplate.queryForMap("""
                SELECT active_loop_id, loop_fence, loop_owner_instance_id,
                       loop_lease_until, runtime_status
                FROM t_session WHERE id = ?
                """, sessionId))
                .containsEntry("active_loop_id", null)
                .containsEntry("loop_fence", 0L)
                .containsEntry("loop_owner_instance_id", null)
                .containsEntry("loop_lease_until", null)
                .containsEntry("runtime_status", "idle");
    }

    private String createSession() {
        String sessionId = UUID.randomUUID().toString();
        sessionIdToRemove = sessionId;
        SessionEntity session = new SessionEntity();
        session.setId(sessionId);
        session.setUserId(1201L);
        session.setAgentId(1202L);
        session.setStatus("active");
        session.setRuntimeStatus("idle");
        session.setHistoryEpoch(3L);
        sessionRepository.saveAndFlush(session);
        return sessionId;
    }
}
