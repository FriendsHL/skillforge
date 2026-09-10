package com.skillforge.server.session;

import com.skillforge.server.AbstractPostgresIT;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionMessageEntity;
import com.skillforge.server.entity.SessionToolAttemptEntity;
import com.skillforge.server.repository.SessionMessageRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SessionToolAttemptRepository;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** PostgreSQL gates for claiming a parked WAITING_USER Session before answering it. */
@Import({
        JacksonAutoConfiguration.class,
        PersistedMessageCodec.class,
        SessionOrderedMessageWriter.class,
        SessionLoopAdmissionService.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SessionManualContinuationClaimPostgresIT extends AbstractPostgresIT {

    private static final long USER_ID = 2_701L;
    private static final long HISTORY_EPOCH = 9L;
    private static final long PARKED_FENCE = 17L;

    @Autowired private SessionRepository sessionRepository;
    @Autowired private SessionMessageRepository messageRepository;
    @Autowired private SessionToolAttemptRepository attemptRepository;
    @Autowired private SessionLoopAdmissionService admissionService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private String sessionIdToRemove;
    private String triggerName;
    private String functionName;

    @AfterEach
    void cleanFixture() {
        if (triggerName != null) {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + triggerName + " ON t_session");
        }
        if (functionName != null) {
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + functionName + "()");
        }
        if (sessionIdToRemove != null) {
            jdbcTemplate.update("DELETE FROM t_session WHERE id = ?", sessionIdToRemove);
        }
    }

    @Test
    void claimManualContinuation_parkedWaitingAttemptReturnsScopeAndExactRetryDoesNotBumpFence() {
        WaitingFixture fixture = createWaitingFixture();
        String loopId = UUID.randomUUID().toString();

        SessionLoopAdmissionService.ManualContinuationClaimAck first =
                admissionService.claimManualContinuation(
                        fixture.sessionId(), USER_ID, HISTORY_EPOCH, loopId);
        SessionLoopAdmissionService.ManualContinuationClaimAck retry =
                admissionService.claimManualContinuation(
                        fixture.sessionId(), USER_ID, HISTORY_EPOCH, loopId);

        assertThat(retry).isEqualTo(first);
        assertThat(first.scope().sessionId()).isEqualTo(fixture.sessionId());
        assertThat(first.scope().userId()).isEqualTo(USER_ID);
        assertThat(first.scope().historyEpoch()).isEqualTo(HISTORY_EPOCH);
        assertThat(first.scope().loopId()).isEqualTo(loopId);
        assertThat(first.scope().loopFence()).isEqualTo(PARKED_FENCE + 1L);
        assertThat(first.scope().ownerInstanceId()).isNotBlank();
        assertThat(first.attemptId()).isEqualTo(fixture.attemptId());
        assertThat(first.stepId()).isEqualTo(fixture.stepId());
        assertThat(jdbcTemplate.queryForMap("""
                SELECT active_loop_id, loop_fence, loop_owner_instance_id,
                       loop_lease_until, runtime_status, runtime_step
                FROM t_session WHERE id = ?
                """, fixture.sessionId()))
                .containsEntry("active_loop_id", loopId)
                .containsEntry("loop_fence", PARKED_FENCE + 1L)
                .containsEntry("loop_owner_instance_id", first.scope().ownerInstanceId())
                .containsEntry("runtime_status", "running")
                .containsEntry("runtime_step", "Answering interactive control");
        assertThat(jdbcTemplate.queryForMap("""
                SELECT state, execution_generation, execution_loop_id, execution_fence,
                       execution_owner_instance_id, claim_request_id
                FROM t_session_tool_attempt WHERE id = ?
                """, fixture.attemptId()))
                .containsEntry("state", "WAITING_USER")
                .containsEntry("execution_generation", 0L)
                .containsEntry("execution_loop_id", null)
                .containsEntry("execution_fence", null)
                .containsEntry("execution_owner_instance_id", null)
                .containsEntry("claim_request_id", null);
    }

    @Test
    void claimManualContinuation_crossUserIsRejectedWithoutMutatingParkedState() {
        WaitingFixture fixture = createWaitingFixture();

        Throwable failure = catchThrowable(() -> admissionService.claimManualContinuation(
                fixture.sessionId(), USER_ID + 1L, HISTORY_EPOCH,
                UUID.randomUUID().toString()));

        assertThat(failure)
                .isInstanceOf(DurableRecoveryFailureException.class)
                .hasNoCause();
        assertParked(fixture);
    }

    @Test
    void claimManualContinuation_staleHistoryEpochIsRejectedWithoutMutatingParkedState() {
        WaitingFixture fixture = createWaitingFixture();

        Throwable failure = catchThrowable(() -> admissionService.claimManualContinuation(
                fixture.sessionId(), USER_ID, HISTORY_EPOCH - 1L,
                UUID.randomUUID().toString()));

        assertThat(failure)
                .isInstanceOf(DurableRecoveryFailureException.class)
                .hasNoCause();
        assertParked(fixture);
    }

    @Test
    void claimManualContinuation_sameLoopOwnedByAnotherInstanceIsRejected() {
        WaitingFixture fixture = createWaitingFixture();
        String loopId = UUID.randomUUID().toString();
        SessionLoopAdmissionService.ManualContinuationClaimAck claimed =
                admissionService.claimManualContinuation(
                        fixture.sessionId(), USER_ID, HISTORY_EPOCH, loopId);
        jdbcTemplate.update(
                "UPDATE t_session SET loop_owner_instance_id = 'other-instance' WHERE id = ?",
                fixture.sessionId());

        Throwable failure = catchThrowable(() -> admissionService.claimManualContinuation(
                fixture.sessionId(), USER_ID, HISTORY_EPOCH, loopId));

        assertThat(failure)
                .isInstanceOf(DurableRecoveryNotReadyException.class)
                .hasNoCause();
        assertThat(jdbcTemplate.queryForMap("""
                SELECT active_loop_id, loop_fence, loop_owner_instance_id
                FROM t_session WHERE id = ?
                """, fixture.sessionId()))
                .containsEntry("active_loop_id", loopId)
                .containsEntry("loop_fence", claimed.scope().loopFence())
                .containsEntry("loop_owner_instance_id", "other-instance");
    }

    @Test
    void claimManualContinuation_wrongAttemptStateOrRestoreBarrierIsRejected() {
        WaitingFixture fixture = createWaitingFixture();
        jdbcTemplate.update(
                "UPDATE t_session_tool_attempt SET state = 'INTENT_COMMITTED' WHERE id = ?",
                fixture.attemptId());

        Throwable wrongState = catchThrowable(() -> admissionService.claimManualContinuation(
                fixture.sessionId(), USER_ID, HISTORY_EPOCH, UUID.randomUUID().toString()));

        assertThat(wrongState)
                .isInstanceOf(DurableRecoveryFailureException.class)
                .hasNoCause();
        assertParked(fixture);

        jdbcTemplate.update(
                "UPDATE t_session_tool_attempt SET state = 'WAITING_USER' WHERE id = ?",
                fixture.attemptId());
        jdbcTemplate.update(
                "UPDATE t_session SET restore_preparing = TRUE WHERE id = ?",
                fixture.sessionId());

        Throwable restoreBarrier = catchThrowable(() -> admissionService.claimManualContinuation(
                fixture.sessionId(), USER_ID, HISTORY_EPOCH, UUID.randomUUID().toString()));

        assertThat(restoreBarrier)
                .isInstanceOf(DurableRecoveryFailureException.class)
                .hasNoCause();
        assertParked(fixture);
    }

    @Test
    void claimManualContinuation_twoDifferentClaimantsOnlyOneWins() throws Exception {
        WaitingFixture fixture = createWaitingFixture();
        String firstLoop = UUID.randomUUID().toString();
        String secondLoop = UUID.randomUUID().toString();
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = executor.submit(
                    () -> claimAfter(start, fixture, firstLoop));
            Future<Object> second = executor.submit(
                    () -> claimAfter(start, fixture, secondLoop));
            start.countDown();

            List<Object> outcomes = List.of(first.get(), second.get());
            assertThat(outcomes.stream()
                    .filter(SessionLoopAdmissionService.ManualContinuationClaimAck.class::isInstance)
                    .toList())
                    .hasSize(1);
            assertThat(outcomes.stream()
                    .filter(DurableRecoveryNotReadyException.class::isInstance)
                    .toList())
                    .hasSize(1);
        } finally {
            executor.shutdownNow();
        }

        Map<String, Object> claimed = jdbcTemplate.queryForMap("""
                SELECT active_loop_id, loop_fence FROM t_session WHERE id = ?
                """, fixture.sessionId());
        assertThat(claimed.get("active_loop_id")).isIn(firstLoop, secondLoop);
        assertThat(claimed).containsEntry("loop_fence", PARKED_FENCE + 1L);
    }

    @Test
    void claimManualContinuation_transientDatabaseFailureRollsBackAndRemainsRetryable() {
        WaitingFixture fixture = createWaitingFixture();
        installTransientClaimFailureTrigger();

        Throwable failure = catchThrowable(() -> admissionService.claimManualContinuation(
                fixture.sessionId(), USER_ID, HISTORY_EPOCH, UUID.randomUUID().toString()));

        assertThat(failure)
                .isInstanceOf(DurableRecoveryRetryableException.class)
                .hasNoCause();
        assertParked(fixture);
    }

    private Object claimAfter(
            CountDownLatch start, WaitingFixture fixture, String loopId) throws InterruptedException {
        start.await();
        try {
            return admissionService.claimManualContinuation(
                    fixture.sessionId(), USER_ID, HISTORY_EPOCH, loopId);
        } catch (RuntimeException failure) {
            return failure;
        }
    }

    private WaitingFixture createWaitingFixture() {
        String sessionId = UUID.randomUUID().toString();
        sessionIdToRemove = sessionId;
        SessionEntity session = new SessionEntity();
        session.setId(sessionId);
        session.setUserId(USER_ID);
        session.setAgentId(2_702L);
        session.setStatus("active");
        session.setRuntimeStatus("waiting_user");
        session.setRuntimeStep("waiting_control");
        session.setHistoryEpoch(HISTORY_EPOCH);
        session.setLoopFence(PARKED_FENCE);
        sessionRepository.saveAndFlush(session);

        SessionMessageEntity assistant = new SessionMessageEntity();
        assistant.setSessionId(sessionId);
        assistant.setSeqNo(0L);
        assistant.setRole("assistant");
        assistant.setMsgType("NORMAL");
        assistant.setMessageType("normal");
        assistant.setContentJson("\"waiting intent\"");
        assistant.setMetadataJson("{}");
        assistant.setWriteBatchId(UUID.randomUUID().toString());
        assistant.setWriteBatchOrdinal(0);
        SessionMessageEntity persistedAssistant = messageRepository.saveAndFlush(assistant);

        UUID stepId = UUID.randomUUID();
        SessionToolAttemptEntity attempt = new SessionToolAttemptEntity();
        attempt.setSessionId(sessionId);
        attempt.setStepId(stepId);
        attempt.setHistoryEpoch(HISTORY_EPOCH);
        attempt.setOriginLoopId(UUID.randomUUID().toString());
        attempt.setOriginFence(PARKED_FENCE);
        attempt.setAssistantMessageId(persistedAssistant.getId());
        attempt.setAssistantPayloadHash("a".repeat(64));
        attempt.setPreIntentMaxMessageId(-1L);
        attempt.setPreIntentMaxSeq(-1L);
        attempt.setManifestJson("{}");
        attempt.setManifestHash("b".repeat(64));
        attempt.setReplaySafety("READ_ONLY_REPLAYABLE");
        attempt.setState("WAITING_USER");
        SessionToolAttemptEntity persistedAttempt = attemptRepository.saveAndFlush(attempt);
        return new WaitingFixture(sessionId, persistedAttempt.getId(), stepId);
    }

    private void assertParked(WaitingFixture fixture) {
        assertThat(jdbcTemplate.queryForMap("""
                SELECT active_loop_id, loop_fence, loop_owner_instance_id,
                       loop_lease_until, runtime_status
                FROM t_session WHERE id = ?
                """, fixture.sessionId()))
                .containsEntry("active_loop_id", null)
                .containsEntry("loop_fence", PARKED_FENCE)
                .containsEntry("loop_owner_instance_id", null)
                .containsEntry("loop_lease_until", null)
                .containsEntry("runtime_status", "waiting_user");
        assertThat(jdbcTemplate.queryForMap("""
                SELECT state, execution_generation, execution_loop_id, execution_fence,
                       execution_owner_instance_id, claim_request_id
                FROM t_session_tool_attempt WHERE id = ?
                """, fixture.attemptId()))
                .containsEntry("execution_generation", 0L)
                .containsEntry("execution_loop_id", null)
                .containsEntry("execution_fence", null)
                .containsEntry("execution_owner_instance_id", null)
                .containsEntry("claim_request_id", null);
    }

    private void installTransientClaimFailureTrigger() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        functionName = "fail_manual_continuation_" + suffix;
        triggerName = "trg_fail_manual_continuation_" + suffix;
        jdbcTemplate.execute("""
                CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.active_loop_id IS NOT NULL AND OLD.active_loop_id IS NULL THEN
                        RAISE EXCEPTION 'forced_manual_continuation_retry'
                            USING ERRCODE = '40001';
                    END IF;
                    RETURN NEW;
                END
                $$
                """.formatted(functionName));
        jdbcTemplate.execute("""
                CREATE TRIGGER %s BEFORE UPDATE ON t_session
                FOR EACH ROW EXECUTE FUNCTION %s()
                """.formatted(triggerName, functionName));
    }

    private record WaitingFixture(String sessionId, long attemptId, UUID stepId) {
    }
}
