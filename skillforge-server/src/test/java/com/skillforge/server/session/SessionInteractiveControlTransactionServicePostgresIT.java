package com.skillforge.server.session;

import com.skillforge.core.engine.durability.DurableFrontier;
import com.skillforge.core.engine.durability.DurableToolAttemptState;
import com.skillforge.core.engine.durability.ExecutionClaimAck;
import com.skillforge.core.engine.durability.ExecutionClaimCommand;
import com.skillforge.core.engine.durability.FrozenJson;
import com.skillforge.core.engine.durability.IntentCommitCommand;
import com.skillforge.core.engine.durability.InteractiveStepPlanner;
import com.skillforge.core.engine.durability.LoopDurabilityScope;
import com.skillforge.core.engine.durability.MessageSnapshot;
import com.skillforge.core.engine.durability.ReplaySafety;
import com.skillforge.core.engine.durability.ToolCallIntent;
import com.skillforge.core.engine.durability.ToolCallManifest;
import com.skillforge.core.model.ContentBlock;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** PostgreSQL hard gates for durable WAITING_USER controls and full-vector answers. */
@Import({
        JacksonAutoConfiguration.class,
        PersistedMessageCodec.class,
        SessionOrderedMessageWriter.class,
        SessionLoopAdmissionService.class,
        SessionToolAttemptTransactionService.class,
        SessionInteractiveControlTransactionService.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SessionInteractiveControlTransactionServicePostgresIT extends AbstractPostgresIT {

    private static final long USER_ID = 2301L;
    private static final long HISTORY_EPOCH = 7L;

    @Autowired private SessionRepository sessionRepository;
    @Autowired private SessionLoopAdmissionService admissionService;
    @Autowired private SessionInteractiveControlTransactionService interactiveTransactions;
    @Autowired private JdbcTemplate jdbcTemplate;

    private String sessionIdToRemove;
    private String triggerName;
    private String functionName;

    @AfterEach
    void cleanFixture() {
        if (triggerName != null) {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + triggerName
                    + " ON t_session_message");
        }
        if (functionName != null) {
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + functionName + "()");
        }
        if (sessionIdToRemove != null) {
            jdbcTemplate.update("DELETE FROM t_session WHERE id = ?", sessionIdToRemove);
        }
    }

    @Test
    void commitWaitingIntent_atomicallyPersistsFullAssistantWaitingAttemptAndFilteredControl() {
        Fixture fixture = createFixture();

        SessionInteractiveControlTransactionService.InteractiveIntentAck ack =
                interactiveTransactions.commitWaitingIntent(fixture.intentCommand());

        List<Map<String, Object>> rows = messageRows(fixture.scope().sessionId());
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0))
                .containsEntry("seq_no", 0L)
                .containsEntry("role", "assistant")
                .containsEntry("msg_type", "NORMAL")
                .containsEntry("message_type", "normal")
                .containsEntry("write_batch_id", fixture.intentCommand().intent().writeBatchId())
                .containsEntry("write_batch_ordinal", 0);
        assertThat(rows.get(1))
                .containsEntry("seq_no", 1L)
                .containsEntry("role", "assistant")
                .containsEntry("msg_type", "SYSTEM_EVENT")
                .containsEntry("message_type", "ask_user")
                .containsEntry("control_id", fixture.intentCommand().controlId())
                .containsEntry("write_batch_ordinal", 0);
        assertThat(rows.get(1).get("write_batch_id"))
                .isEqualTo(ack.control().writeBatchId());
        assertThat(rows.get(1).get("answered_at")).isNull();

        Map<String, Object> attempt = attemptRow(ack.intent().attemptId());
        assertThat(attempt)
                .containsEntry("state", "WAITING_USER")
                .containsEntry("execution_generation", 0L)
                .containsEntry("assistant_message_id", ack.intent().assistant().messageId())
                .containsEntry("result_batch_id", null);
        assertThat(attempt.get("execution_loop_id")).isNull();
        assertThat(attempt.get("execution_fence")).isNull();
        assertThat(attempt.get("claim_request_id")).isNull();

        assertThat(ack.control().message().toMessage().getTextContent())
                .isEqualTo(fixture.intentCommand().displayText());
        assertThat(((Number) ack.control().metadata().get("attemptId")).longValue())
                .isEqualTo(ack.intent().attemptId());
        assertThat(ack.control().metadata())
                .containsEntry("stepId", fixture.intentCommand().intent().stepId().toString())
                .containsEntry("providerOrdinal", 1)
                .containsEntry("toolUseId", "ask-1")
                .containsEntry("toolName", "ask_user")
                .containsEntry("state", "pending")
                .containsEntry("payload", fixture.intentCommand().payload().toJavaValue());
        assertThat(ack.selectedControl().providerOrdinal()).isEqualTo(1);
        assertThat(ack.selectedControl().kind())
                .isEqualTo(InteractiveStepPlanner.CallKind.ASK_USER);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM t_session_message
                WHERE session_id = ? AND msg_type <> 'SYSTEM_EVENT'
                """, Long.class, fixture.scope().sessionId())).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT message_count FROM t_session WHERE id = ?",
                Integer.class,
                fixture.scope().sessionId())).isEqualTo(2);
    }

    @Test
    void commitWaitingIntent_controlWriteFailureRollsBackAssistantAndAttempt() {
        Fixture fixture = createFixture();
        installControlInsertFailureTrigger();

        Throwable failure = catchThrowable(
                () -> interactiveTransactions.commitWaitingIntent(fixture.intentCommand()));

        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Durable interactive intent persistence failed")
                .hasNoCause();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM t_session_message WHERE session_id = ?",
                Long.class,
                fixture.scope().sessionId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM t_session_tool_attempt WHERE session_id = ?",
                Long.class,
                fixture.scope().sessionId())).isZero();
    }

    @Test
    void commitWaitingIntent_ackLossRetryAfterLeaseExpiryReturnsSameAckWithoutDuplicates() {
        Fixture fixture = createFixture();
        SessionInteractiveControlTransactionService.InteractiveIntentAck first =
                interactiveTransactions.commitWaitingIntent(fixture.intentCommand());
        expireSessionLease(fixture.scope().sessionId());

        SessionInteractiveControlTransactionService.InteractiveIntentAck retry =
                interactiveTransactions.commitWaitingIntent(fixture.intentCommand());

        assertThat(retry).isEqualTo(first);
        assertThat(messageRows(fixture.scope().sessionId())).hasSize(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM t_session_tool_attempt WHERE session_id = ?",
                Long.class,
                fixture.scope().sessionId())).isOne();
    }

    @Test
    void loadWaitingControl_afterRecoveryFenceRebuildsExactControlWithoutExecutingAnything() {
        Fixture fixture = createFixture();
        SessionInteractiveControlTransactionService.InteractiveIntentAck committed =
                interactiveTransactions.commitWaitingIntent(fixture.intentCommand());
        LoopDurabilityScope recoveredScope = new LoopDurabilityScope(
                fixture.scope().sessionId(), USER_ID, HISTORY_EPOCH,
                UUID.randomUUID().toString(), fixture.scope().loopFence() + 1L,
                "interactive-recovery-owner");
        jdbcTemplate.update("""
                UPDATE t_session
                SET active_loop_id = ?, loop_fence = ?, loop_owner_instance_id = ?,
                    loop_lease_until = clock_timestamp() + interval '5 minutes'
                WHERE id = ?
                """, recoveredScope.loopId(), recoveredScope.loopFence(),
                recoveredScope.ownerInstanceId(), recoveredScope.sessionId());

        SessionInteractiveControlTransactionService.InteractiveIntentAck recovered =
                interactiveTransactions.loadWaitingControl(
                        recoveredScope, committed.intent().attemptId());

        assertThat(recovered).isEqualTo(committed);
        assertThat(attemptRow(committed.intent().attemptId()))
                .containsEntry("state", "WAITING_USER")
                .containsEntry("execution_generation", 0L)
                .containsEntry("result_batch_id", null);
        assertThat(messageRows(fixture.scope().sessionId())).hasSize(2);
    }

    @Test
    void loadParkedWaitingControl_isRepeatableWithoutClaimFenceOrTranscriptMutation() {
        Fixture fixture = createFixtureWithRecoverablePayload();
        SessionInteractiveControlTransactionService.InteractiveIntentAck committed =
                interactiveTransactions.commitWaitingIntent(fixture.intentCommand());
        admissionService.parkForManualContinuation(
                fixture.scope(), DurableToolAttemptState.WAITING_USER, null);
        Map<String, Object> sessionBefore = sessionAuthorityRow(fixture.scope().sessionId());
        Map<String, Object> attemptBefore = attemptRow(committed.intent().attemptId());
        List<Map<String, Object>> messagesBefore = messageRows(fixture.scope().sessionId());

        SessionInteractiveControlTransactionService.InteractiveIntentAck first =
                interactiveTransactions.loadParkedWaitingControl(
                        fixture.scope().sessionId(), USER_ID, HISTORY_EPOCH);
        SessionInteractiveControlTransactionService.InteractiveIntentAck second =
                interactiveTransactions.loadParkedWaitingControl(
                        fixture.scope().sessionId(), USER_ID, HISTORY_EPOCH);

        assertThat(first).isEqualTo(committed);
        assertThat(second).isEqualTo(committed);
        assertThat(sessionAuthorityRow(fixture.scope().sessionId())).isEqualTo(sessionBefore);
        assertThat(attemptRow(committed.intent().attemptId())).isEqualTo(attemptBefore);
        assertThat(messageRows(fixture.scope().sessionId())).isEqualTo(messagesBefore);
    }

    @Test
    void loadParkedWaitingControl_corruptPayloadFailsClosedWithoutChangingAuthority() {
        Fixture fixture = createFixtureWithRecoverablePayload();
        SessionInteractiveControlTransactionService.InteractiveIntentAck committed =
                interactiveTransactions.commitWaitingIntent(fixture.intentCommand());
        admissionService.parkForManualContinuation(
                fixture.scope(), DurableToolAttemptState.WAITING_USER, null);
        jdbcTemplate.update("""
                UPDATE t_session_message
                SET metadata_json = jsonb_set(
                    metadata_json::jsonb, '{payload,toolUseId}', '"wrong"'::jsonb)::text
                WHERE id = ?
                """, committed.control().messageId());
        Map<String, Object> sessionBefore = sessionAuthorityRow(fixture.scope().sessionId());
        Map<String, Object> attemptBefore = attemptRow(committed.intent().attemptId());

        Throwable failure = catchThrowable(() ->
                interactiveTransactions.loadParkedWaitingControl(
                        fixture.scope().sessionId(), USER_ID, HISTORY_EPOCH));

        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Durable interactive control is partial or inconsistent")
                .hasNoCause();
        assertThat(sessionAuthorityRow(fixture.scope().sessionId())).isEqualTo(sessionBefore);
        assertThat(attemptRow(committed.intent().attemptId())).isEqualTo(attemptBefore);
        assertThat(messageRows(fixture.scope().sessionId())).hasSize(2);
    }

    @Test
    void recordParkedWaitingControlRecoveryFailure_changesOnlySafeRuntimeFailureFields() {
        Fixture fixture = createFixtureWithRecoverablePayload();
        SessionInteractiveControlTransactionService.InteractiveIntentAck committed =
                interactiveTransactions.commitWaitingIntent(fixture.intentCommand());
        admissionService.parkForManualContinuation(
                fixture.scope(), DurableToolAttemptState.WAITING_USER, null);
        long fenceBefore = ((Number) sessionAuthorityRow(
                fixture.scope().sessionId()).get("loop_fence")).longValue();

        interactiveTransactions.recordParkedWaitingControlRecoveryFailure(
                fixture.scope().sessionId(), USER_ID, HISTORY_EPOCH);

        Map<String, Object> session = sessionAuthorityRow(fixture.scope().sessionId());
        assertThat(session)
                .containsEntry("runtime_status", "error")
                .containsEntry("runtime_failure_source", "harness")
                .containsEntry("runtime_failure_code", "WAITING_CONTROL_RECOVERY_FAILED")
                .containsEntry("runtime_retryable", false)
                .containsEntry("runtime_side_effects", "none")
                .containsEntry("runtime_error",
                        "The pending interactive control could not be reconstructed.")
                .containsEntry("loop_fence", fenceBefore);
        assertThat(session.get("active_loop_id")).isNull();
        assertThat(session.get("loop_owner_instance_id")).isNull();
        assertThat(session.get("loop_lease_until")).isNull();
        assertThat(attemptRow(committed.intent().attemptId()))
                .containsEntry("state", "WAITING_USER")
                .containsEntry("execution_generation", 0L)
                .containsEntry("result_batch_id", null);
        assertThat(messageRows(fixture.scope().sessionId())).hasSize(2);
    }

    @Test
    void answerClaimAndCommit_closesOriginalVectorAndAckLossRetryIsIdempotent() {
        Fixture fixture = createFixture();
        SessionInteractiveControlTransactionService.InteractiveIntentAck waiting =
                interactiveTransactions.commitWaitingIntent(fixture.intentCommand());
        UUID claimRequestId = UUID.randomUUID();
        ExecutionClaimCommand claimCommand = new ExecutionClaimCommand(
                fixture.scope(),
                waiting.intent().attemptId(),
                waiting.intent().stepId(),
                claimRequestId,
                DurableToolAttemptState.WAITING_USER,
                0L);

        SessionInteractiveControlTransactionService.InteractiveAnswerClaimAck firstClaim =
                interactiveTransactions.claimAnswerForDispatch(
                        claimCommand, fixture.intentCommand().controlId());
        ExecutionClaimAck claim = firstClaim.execution();
        expireSessionLease(fixture.scope().sessionId());
        SessionInteractiveControlTransactionService.InteractiveAnswerClaimAck retryClaim =
                interactiveTransactions.claimAnswerForDispatch(
                        claimCommand, fixture.intentCommand().controlId());
        ExecutionClaimAck claimRetry = retryClaim.execution();

        assertThat(claimRetry).isEqualTo(claim);
        assertThat(firstClaim.dispatchGranted()).isTrue();
        assertThat(retryClaim.dispatchGranted()).isFalse();
        assertThat(firstClaim.control()).isEqualTo(waiting.control());
        assertThat(retryClaim.control()).isEqualTo(waiting.control());
        assertThat(firstClaim.selectedControl()).isEqualTo(waiting.selectedControl());
        assertThat(retryClaim.selectedControl()).isEqualTo(waiting.selectedControl());
        assertThat(firstClaim.selectedControl().call().input().toJavaValue())
                .isEqualTo(Map.of("question", "Choose"));
        assertThat(claim.state()).isEqualTo(DurableToolAttemptState.EXECUTING);
        assertThat(claim.executionGeneration()).isEqualTo(1L);
        renewSessionLease(fixture.scope().sessionId());
        UUID resultBatchId = UUID.randomUUID();
        SessionInteractiveControlTransactionService.InteractiveResultCommand resultCommand =
                new SessionInteractiveControlTransactionService.InteractiveResultCommand(
                        claim,
                        fixture.intentCommand().controlId(),
                        resultBatchId,
                        MessageSnapshot.capture(Message.toolResult(
                                "ask-1", "User answered: option-b", false)),
                        SessionInteractiveControlTransactionService.ResolutionKind.ANSWERED,
                        "option-b",
                        "card",
                        "interactive-answer-trace");

        SessionInteractiveControlTransactionService.InteractiveResultAck first =
                interactiveTransactions.commitAnswerResults(resultCommand);
        expireSessionLease(fixture.scope().sessionId());
        SessionInteractiveControlTransactionService.InteractiveResultAck retry =
                interactiveTransactions.commitAnswerResults(resultCommand);

        assertThat(retry).isEqualTo(first);
        assertThat(first.results().results()).hasSize(3);
        assertResult(first.results().results().get(0).message().toMessage(),
                "normal-0", true, InteractiveStepPlanner.ABORTED_RESULT_ERROR_TYPE);
        assertResult(first.results().results().get(1).message().toMessage(),
                "ask-1", false, null);
        assertResult(first.results().results().get(2).message().toMessage(),
                "normal-2", true, InteractiveStepPlanner.ABORTED_RESULT_ERROR_TYPE);
        assertThat(first.control().answeredAt()).isNotNull();
        assertThat(first.control().metadata())
                .containsEntry("state", "answered")
                .containsEntry("answer", "option-b")
                .containsEntry("answerMode", "card");
        assertThat(attemptRow(waiting.intent().attemptId()))
                .containsEntry("state", "RESULTS_COMMITTED")
                .containsEntry("execution_generation", 1L)
                .containsEntry("result_batch_id", resultBatchId)
                .containsEntry("archive_total_count", 3);
        assertThat(messageRows(fixture.scope().sessionId())).hasSize(5);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM t_session_message
                WHERE session_id = ? AND write_batch_id = ?
                """, Long.class, fixture.scope().sessionId(), resultBatchId.toString()))
                .isEqualTo(3L);
    }

    @Test
    void parkedWaitingControl_manualContinuationClaimFeedsAnswerGenerationClaim() {
        Fixture fixture = createFixture();
        SessionInteractiveControlTransactionService.InteractiveIntentAck waiting =
                interactiveTransactions.commitWaitingIntent(fixture.intentCommand());
        admissionService.parkForManualContinuation(
                fixture.scope(), DurableToolAttemptState.WAITING_USER, null);
        String answerLoopId = UUID.randomUUID().toString();

        SessionLoopAdmissionService.ManualContinuationClaimAck continuation =
                admissionService.claimManualContinuation(
                        fixture.scope().sessionId(), USER_ID, HISTORY_EPOCH, answerLoopId);
        UUID claimRequestId = UUID.randomUUID();
        ExecutionClaimCommand claimCommand = new ExecutionClaimCommand(
                continuation.scope(),
                continuation.attemptId(),
                continuation.stepId(),
                claimRequestId,
                DurableToolAttemptState.WAITING_USER,
                0L);
        ExecutionClaimAck answerClaim = interactiveTransactions.claimAnswer(
                claimCommand, fixture.intentCommand().controlId());
        SessionLoopAdmissionService.ManualContinuationClaimAck executingRetry =
                admissionService.claimManualContinuation(
                        fixture.scope().sessionId(), USER_ID, HISTORY_EPOCH, answerLoopId);
        var resultCommand = new SessionInteractiveControlTransactionService.InteractiveResultCommand(
                answerClaim,
                fixture.intentCommand().controlId(),
                UUID.randomUUID(),
                MessageSnapshot.capture(Message.toolResult(
                        "ask-1", "User answered: option-a", false)),
                SessionInteractiveControlTransactionService.ResolutionKind.ANSWERED,
                "option-a",
                "card",
                "manual-continuation-result-trace");
        SessionInteractiveControlTransactionService.InteractiveResultAck firstResult =
                interactiveTransactions.commitAnswerResults(resultCommand);
        SessionLoopAdmissionService.ManualContinuationClaimAck resultCommittedRetry =
                admissionService.claimManualContinuation(
                        fixture.scope().sessionId(), USER_ID, HISTORY_EPOCH, answerLoopId);
        ExecutionClaimAck closedClaimRetry = interactiveTransactions.claimAnswer(
                claimCommand, fixture.intentCommand().controlId());
        SessionInteractiveControlTransactionService.InteractiveAnswerClaimAck closedDispatchRetry =
                interactiveTransactions.claimAnswerForDispatch(
                        claimCommand, fixture.intentCommand().controlId());
        var closedResultCommand = new SessionInteractiveControlTransactionService.InteractiveResultCommand(
                closedClaimRetry,
                resultCommand.controlId(),
                resultCommand.resultBatchId(),
                resultCommand.selectedResult(),
                resultCommand.resolutionKind(),
                resultCommand.answer(),
                resultCommand.answerMode(),
                resultCommand.traceId());
        SessionInteractiveControlTransactionService.InteractiveResultAck closedResultRetry =
                interactiveTransactions.commitAnswerResults(closedResultCommand);
        SessionInteractiveControlTransactionService.InteractiveResultAck readback =
                interactiveTransactions.loadCommittedAnswerResults(
                        closedClaimRetry, fixture.intentCommand().controlId());

        assertThat(answerClaim.executionScope()).isEqualTo(continuation.scope());
        assertThat(answerClaim.attemptId()).isEqualTo(waiting.intent().attemptId());
        assertThat(answerClaim.stepId()).isEqualTo(waiting.intent().stepId());
        assertThat(answerClaim.state()).isEqualTo(DurableToolAttemptState.EXECUTING);
        assertThat(answerClaim.executionGeneration()).isEqualTo(1L);
        assertThat(executingRetry).isEqualTo(continuation);
        assertThat(resultCommittedRetry).isEqualTo(continuation);
        assertThat(closedClaimRetry.state())
                .isEqualTo(DurableToolAttemptState.RESULTS_COMMITTED);
        assertThat(closedClaimRetry.attemptId()).isEqualTo(answerClaim.attemptId());
        assertThat(closedClaimRetry.stepId()).isEqualTo(answerClaim.stepId());
        assertThat(closedClaimRetry.claimRequestId()).isEqualTo(answerClaim.claimRequestId());
        assertThat(closedClaimRetry.executionScope()).isEqualTo(answerClaim.executionScope());
        assertThat(closedClaimRetry.executionGeneration())
                .isEqualTo(answerClaim.executionGeneration());
        assertThat(closedClaimRetry.claimedAt()).isEqualTo(answerClaim.claimedAt());
        assertThat(closedClaimRetry.executionLeaseUntil())
                .isEqualTo(answerClaim.executionLeaseUntil());
        assertThat(closedDispatchRetry.execution()).isEqualTo(closedClaimRetry);
        assertThat(closedDispatchRetry.dispatchGranted()).isFalse();
        assertThat(closedDispatchRetry.control()).isEqualTo(firstResult.control());
        assertThat(closedDispatchRetry.selectedControl()).isEqualTo(firstResult.selectedControl());
        assertThat(closedResultRetry).isEqualTo(firstResult);
        assertThat(readback).isEqualTo(firstResult);
        assertThat(messageRows(fixture.scope().sessionId())).hasSize(5);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT loop_fence FROM t_session WHERE id = ?",
                Long.class,
                fixture.scope().sessionId())).isEqualTo(continuation.scope().loopFence());
    }

    @Test
    void concurrentExactAnswerClaims_grantDispatchOnlyOnce() throws Exception {
        Fixture fixture = createFixture();
        SessionInteractiveControlTransactionService.InteractiveIntentAck waiting =
                interactiveTransactions.commitWaitingIntent(fixture.intentCommand());
        ExecutionClaimCommand command = new ExecutionClaimCommand(
                fixture.scope(), waiting.intent().attemptId(), waiting.intent().stepId(),
                UUID.randomUUID(), DurableToolAttemptState.WAITING_USER, 0L);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<SessionInteractiveControlTransactionService.InteractiveAnswerClaimAck> first =
                    callers.submit(() -> {
                        ready.countDown();
                        start.await();
                        return interactiveTransactions.claimAnswerForDispatch(
                                command, fixture.intentCommand().controlId());
                    });
            Future<SessionInteractiveControlTransactionService.InteractiveAnswerClaimAck> second =
                    callers.submit(() -> {
                        ready.countDown();
                        start.await();
                        return interactiveTransactions.claimAnswerForDispatch(
                                command, fixture.intentCommand().controlId());
                    });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<SessionInteractiveControlTransactionService.InteractiveAnswerClaimAck> claims =
                    List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));

            assertThat(claims).extracting(
                            SessionInteractiveControlTransactionService.InteractiveAnswerClaimAck
                                    ::dispatchGranted)
                    .containsExactlyInAnyOrder(true, false);
            assertThat(claims.get(0).execution()).isEqualTo(claims.get(1).execution());
            assertThat(attemptRow(waiting.intent().attemptId()))
                    .containsEntry("state", "EXECUTING")
                    .containsEntry("execution_generation", 1L);
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void loadCommittedAnswerResults_missingResultFailsClosed() {
        CommittedFixture committed = commitFixtureAnswer();
        jdbcTemplate.update("""
                DELETE FROM t_session_message
                WHERE session_id = ? AND write_batch_id = ? AND write_batch_ordinal = 2
                """, committed.fixture().scope().sessionId(),
                committed.result().results().resultBatchId().toString());

        Throwable failure = catchThrowable(() -> interactiveTransactions.loadCommittedAnswerResults(
                committed.closedExecution(), committed.fixture().intentCommand().controlId()));

        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Durable interactive control is partial or inconsistent")
                .hasNoCause();
    }

    @Test
    void loadCommittedAnswerResults_corruptResultIdentityFailsClosed() {
        CommittedFixture committed = commitFixtureAnswer();
        jdbcTemplate.update("""
                UPDATE t_session_message SET write_batch_ordinal = 99
                WHERE session_id = ? AND write_batch_id = ? AND write_batch_ordinal = 1
                """, committed.fixture().scope().sessionId(),
                committed.result().results().resultBatchId().toString());

        Throwable failure = catchThrowable(() -> interactiveTransactions.loadCommittedAnswerResults(
                committed.closedExecution(), committed.fixture().intentCommand().controlId()));

        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Durable interactive control is partial or inconsistent")
                .hasNoCause();
    }

    @Test
    void committedAnswerRetry_differentAnswerOrDecisionConflictsWithoutRewritingResult() {
        Fixture fixture = createFixture();
        SessionInteractiveControlTransactionService.InteractiveIntentAck waiting =
                interactiveTransactions.commitWaitingIntent(fixture.intentCommand());
        ExecutionClaimCommand claimCommand = new ExecutionClaimCommand(
                fixture.scope(), waiting.intent().attemptId(), waiting.intent().stepId(),
                UUID.randomUUID(), DurableToolAttemptState.WAITING_USER, 0L);
        ExecutionClaimAck claim = interactiveTransactions.claimAnswer(
                claimCommand, fixture.intentCommand().controlId());
        UUID resultBatchId = UUID.randomUUID();
        MessageSnapshot originalResult = MessageSnapshot.capture(
                Message.toolResult("ask-1", "User answered: option-a", false));
        var original = new SessionInteractiveControlTransactionService.InteractiveResultCommand(
                claim,
                fixture.intentCommand().controlId(),
                resultBatchId,
                originalResult,
                SessionInteractiveControlTransactionService.ResolutionKind.ANSWERED,
                "option-a",
                "card",
                "closed-answer-trace");
        SessionInteractiveControlTransactionService.InteractiveResultAck committed =
                interactiveTransactions.commitAnswerResults(original);

        Throwable differentControlFailure = catchThrowable(
                () -> interactiveTransactions.claimAnswer(claimCommand, "different-control"));
        ExecutionClaimCommand differentClaimIdentity = new ExecutionClaimCommand(
                claimCommand.claimant(),
                claimCommand.attemptId(),
                claimCommand.stepId(),
                UUID.randomUUID(),
                claimCommand.expectedState(),
                claimCommand.expectedGeneration());
        Throwable differentClaimFailure = catchThrowable(
                () -> interactiveTransactions.claimAnswer(
                        differentClaimIdentity, fixture.intentCommand().controlId()));

        var differentAnswer = new SessionInteractiveControlTransactionService.InteractiveResultCommand(
                claim,
                original.controlId(),
                original.resultBatchId(),
                original.selectedResult(),
                original.resolutionKind(),
                "option-b",
                original.answerMode(),
                original.traceId());
        var differentDecision = new SessionInteractiveControlTransactionService.InteractiveResultCommand(
                claim,
                original.controlId(),
                original.resultBatchId(),
                original.selectedResult(),
                SessionInteractiveControlTransactionService.ResolutionKind.DENIED,
                original.answer(),
                original.answerMode(),
                original.traceId());

        Throwable answerFailure = catchThrowable(
                () -> interactiveTransactions.commitAnswerResults(differentAnswer));
        Throwable decisionFailure = catchThrowable(
                () -> interactiveTransactions.commitAnswerResults(differentDecision));

        assertThat(differentControlFailure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Durable interactive control is partial or inconsistent")
                .hasNoCause();
        assertThat(differentClaimFailure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Durable interactive control is partial or inconsistent")
                .hasNoCause();
        assertThat(answerFailure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Durable interactive control is partial or inconsistent")
                .hasNoCause();
        assertThat(decisionFailure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Durable interactive control is partial or inconsistent")
                .hasNoCause();
        assertThat(messageRows(fixture.scope().sessionId())).hasSize(5);
        assertThat(interactiveTransactions.commitAnswerResults(original)).isEqualTo(committed);
    }

    @Test
    void answerWithWrongControlIdFailsClosedWithoutClaimOrResults() {
        Fixture fixture = createFixture();
        SessionInteractiveControlTransactionService.InteractiveIntentAck waiting =
                interactiveTransactions.commitWaitingIntent(fixture.intentCommand());
        ExecutionClaimCommand claimCommand = new ExecutionClaimCommand(
                fixture.scope(), waiting.intent().attemptId(), waiting.intent().stepId(),
                UUID.randomUUID(), DurableToolAttemptState.WAITING_USER, 0L);

        Throwable failure = catchThrowable(
                () -> interactiveTransactions.claimAnswer(claimCommand, "wrong-control"));

        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Durable interactive control is partial or inconsistent")
                .hasNoCause();
        assertThat(attemptRow(waiting.intent().attemptId()))
                .containsEntry("state", "WAITING_USER")
                .containsEntry("execution_generation", 0L)
                .containsEntry("result_batch_id", null);
        assertThat(messageRows(fixture.scope().sessionId())).hasSize(2);
    }

    @Test
    void commitAnswerResults_resultWriteFailureRollsBackControlResolutionAndAttemptClose() {
        Fixture fixture = createFixture();
        SessionInteractiveControlTransactionService.InteractiveIntentAck waiting =
                interactiveTransactions.commitWaitingIntent(fixture.intentCommand());
        ExecutionClaimAck claim = interactiveTransactions.claimAnswer(
                new ExecutionClaimCommand(
                        fixture.scope(), waiting.intent().attemptId(), waiting.intent().stepId(),
                        UUID.randomUUID(), DurableToolAttemptState.WAITING_USER, 0L),
                fixture.intentCommand().controlId());
        UUID resultBatchId = UUID.randomUUID();
        installResultInsertFailureTrigger();
        var command = new SessionInteractiveControlTransactionService.InteractiveResultCommand(
                claim,
                fixture.intentCommand().controlId(),
                resultBatchId,
                MessageSnapshot.capture(Message.toolResult(
                        "ask-1", "User answered: option-a", false)),
                SessionInteractiveControlTransactionService.ResolutionKind.ANSWERED,
                "option-a",
                "card",
                "interactive-answer-trace");

        Throwable failure = catchThrowable(
                () -> interactiveTransactions.commitAnswerResults(command));

        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Durable interactive result persistence failed")
                .hasNoCause();
        assertThat(attemptRow(waiting.intent().attemptId()))
                .containsEntry("state", "EXECUTING")
                .containsEntry("execution_generation", 1L)
                .containsEntry("result_batch_id", null);
        Map<String, Object> control = messageRows(fixture.scope().sessionId()).get(1);
        assertThat(control.get("answered_at")).isNull();
        assertThat(String.valueOf(control.get("metadata_json")))
                .contains("\"state\":\"pending\"")
                .contains("\"answer\":null")
                .contains("\"answerMode\":null");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM t_session_message
                WHERE session_id = ? AND write_batch_id = ?
                """, Long.class, fixture.scope().sessionId(), resultBatchId.toString()))
                .isZero();
        assertThat(messageRows(fixture.scope().sessionId())).hasSize(2);
    }

    private Fixture createFixture() {
        String sessionId = UUID.randomUUID().toString();
        sessionIdToRemove = sessionId;
        LoopDurabilityScope scope = new LoopDurabilityScope(
                sessionId, USER_ID, HISTORY_EPOCH,
                UUID.randomUUID().toString(), 11L, "interactive-owner");
        SessionEntity session = new SessionEntity();
        session.setId(sessionId);
        session.setUserId(USER_ID);
        session.setAgentId(2302L);
        session.setStatus("active");
        session.setRuntimeStatus("running");
        session.setRuntimeStep("Waiting");
        session.setHistoryEpoch(HISTORY_EPOCH);
        session.setActiveLoopId(scope.loopId());
        session.setLoopFence(scope.loopFence());
        session.setLoopOwnerInstanceId(scope.ownerInstanceId());
        session.setLoopLeaseUntil(Instant.now().plusSeconds(300));
        sessionRepository.saveAndFlush(session);

        ToolCallManifest manifest = manifest();
        InteractiveStepPlanner.InteractiveStepPlan plan = new InteractiveStepPlanner()
                .plan(manifest, call -> "ask_user".equals(call.toolName())
                        ? InteractiveStepPlanner.CallKind.ASK_USER
                        : InteractiveStepPlanner.CallKind.NORMAL)
                .orElseThrow();
        Message assistant = new Message();
        assistant.setRole(Message.Role.ASSISTANT);
        assistant.setReasoningContent("preserved reasoning");
        assistant.setContent(List.of(
                ContentBlock.toolUse("normal-0", "ReadProbe", Map.of("path", "a")),
                ContentBlock.toolUse("ask-1", "ask_user", Map.of("question", "Choose")),
                ContentBlock.toolUse("normal-2", "WriteProbe", Map.of("path", "b"))));
        IntentCommitCommand intent = new IntentCommitCommand(
                scope,
                UUID.randomUUID(),
                UUID.randomUUID().toString(),
                MessageSnapshot.capture(assistant),
                manifest,
                DurableFrontier.EMPTY,
                "interactive-intent-trace");
        var command = new SessionInteractiveControlTransactionService.InteractiveIntentCommand(
                intent,
                plan,
                UUID.randomUUID().toString(),
                "Choose one option",
                FrozenJson.capture(Map.of(
                        "question", "Choose one option",
                        "options", List.of("option-a", "option-b"),
                        "allowOther", false)));
        return new Fixture(scope, command);
    }

    private Fixture createFixtureWithRecoverablePayload() {
        Fixture base = createFixture();
        var selected = base.intentCommand().plan().selectedControl();
        String controlId = base.intentCommand().controlId();
        var command = new SessionInteractiveControlTransactionService.InteractiveIntentCommand(
                base.intentCommand().intent(),
                base.intentCommand().plan(),
                controlId,
                base.intentCommand().displayText(),
                FrozenJson.capture(Map.of(
                        "controlId", controlId,
                        "interactionKind", "ask_user",
                        "toolUseId", selected.call().toolUseId(),
                        "toolName", selected.call().toolName(),
                        "question", "Choose one option",
                        "context", "Pick the safest option",
                        "options", List.of(
                                Map.of("label", "option-a", "description", "First"),
                                Map.of("label", "option-b", "description", "Second")),
                        "allowOther", false,
                        "extra", Map.of())));
        return new Fixture(base.scope(), command);
    }

    private CommittedFixture commitFixtureAnswer() {
        Fixture fixture = createFixture();
        SessionInteractiveControlTransactionService.InteractiveIntentAck waiting =
                interactiveTransactions.commitWaitingIntent(fixture.intentCommand());
        ExecutionClaimCommand claimCommand = new ExecutionClaimCommand(
                fixture.scope(), waiting.intent().attemptId(), waiting.intent().stepId(),
                UUID.randomUUID(), DurableToolAttemptState.WAITING_USER, 0L);
        ExecutionClaimAck execution = interactiveTransactions.claimAnswer(
                claimCommand, fixture.intentCommand().controlId());
        var resultCommand = new SessionInteractiveControlTransactionService.InteractiveResultCommand(
                execution,
                fixture.intentCommand().controlId(),
                UUID.randomUUID(),
                MessageSnapshot.capture(Message.toolResult(
                        "ask-1", "User answered: option-a", false)),
                SessionInteractiveControlTransactionService.ResolutionKind.ANSWERED,
                "option-a",
                "card",
                "committed-readback-trace");
        SessionInteractiveControlTransactionService.InteractiveResultAck result =
                interactiveTransactions.commitAnswerResults(resultCommand);
        ExecutionClaimAck closed = interactiveTransactions.claimAnswer(
                claimCommand, fixture.intentCommand().controlId());
        return new CommittedFixture(fixture, closed, result);
    }

    private static ToolCallManifest manifest() {
        return new ToolCallManifest(List.of(
                new ToolCallIntent(0, "normal-0", "ReadProbe",
                        FrozenJson.capture(Map.of("path", "a")),
                        ReplaySafety.READ_ONLY_REPLAYABLE),
                new ToolCallIntent(1, "ask-1", "ask_user",
                        FrozenJson.capture(Map.of("question", "Choose")),
                        ReplaySafety.READ_ONLY_REPLAYABLE),
                new ToolCallIntent(2, "normal-2", "WriteProbe",
                        FrozenJson.capture(Map.of("path", "b")),
                        ReplaySafety.MUTATING)),
                ReplaySafety.MUTATING);
    }

    private List<Map<String, Object>> messageRows(String sessionId) {
        return jdbcTemplate.queryForList("""
                SELECT id, seq_no, role, msg_type, message_type, control_id, answered_at,
                       write_batch_id, write_batch_ordinal, metadata_json
                FROM t_session_message WHERE session_id = ? ORDER BY seq_no
                """, sessionId);
    }

    private Map<String, Object> attemptRow(long attemptId) {
        return jdbcTemplate.queryForMap("""
                SELECT state, assistant_message_id, execution_loop_id, execution_fence,
                       execution_generation, claim_request_id, result_batch_id,
                       archive_total_count
                FROM t_session_tool_attempt WHERE id = ?
                """, attemptId);
    }

    private Map<String, Object> sessionAuthorityRow(String sessionId) {
        return jdbcTemplate.queryForMap("""
                SELECT runtime_status, runtime_step, active_loop_id, loop_fence,
                       loop_owner_instance_id, loop_lease_until, history_epoch,
                       runtime_failure_source, runtime_failure_code, runtime_retryable,
                       runtime_side_effects, runtime_error
                FROM t_session WHERE id = ?
                """, sessionId);
    }

    private void expireSessionLease(String sessionId) {
        jdbcTemplate.update("""
                UPDATE t_session SET loop_lease_until = clock_timestamp() - interval '1 second'
                WHERE id = ?
                """, sessionId);
    }

    private void renewSessionLease(String sessionId) {
        jdbcTemplate.update("""
                UPDATE t_session SET loop_lease_until = clock_timestamp() + interval '5 minutes'
                WHERE id = ?
                """, sessionId);
    }

    private void installControlInsertFailureTrigger() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        functionName = "fail_interactive_control_" + suffix;
        triggerName = "trg_fail_interactive_control_" + suffix;
        jdbcTemplate.execute("""
                CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.msg_type = 'SYSTEM_EVENT' THEN
                        RAISE EXCEPTION 'forced_interactive_control_failure';
                    END IF;
                    RETURN NEW;
                END
                $$
                """.formatted(functionName));
        jdbcTemplate.execute("""
                CREATE TRIGGER %s BEFORE INSERT ON t_session_message
                FOR EACH ROW EXECUTE FUNCTION %s()
                """.formatted(triggerName, functionName));
    }

    private void installResultInsertFailureTrigger() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        functionName = "fail_interactive_result_" + suffix;
        triggerName = "trg_fail_interactive_result_" + suffix;
        jdbcTemplate.execute("""
                CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.role = 'user' AND NEW.msg_type = 'NORMAL' THEN
                        RAISE EXCEPTION 'forced_interactive_result_failure';
                    END IF;
                    RETURN NEW;
                END
                $$
                """.formatted(functionName));
        jdbcTemplate.execute("""
                CREATE TRIGGER %s BEFORE INSERT ON t_session_message
                FOR EACH ROW EXECUTE FUNCTION %s()
                """.formatted(triggerName, functionName));
    }

    @SuppressWarnings("unchecked")
    private static void assertResult(
            Message message, String toolUseId, boolean isError, String errorType) {
        assertThat(message.getContent()).isInstanceOf(List.class);
        Map<String, Object> block = (Map<String, Object>) ((List<?>) message.getContent()).get(0);
        assertThat(block)
                .containsEntry("type", "tool_result")
                .containsEntry("tool_use_id", toolUseId)
                .containsEntry("is_error", isError);
        if (errorType == null) {
            assertThat(block).doesNotContainKey("error_type");
        } else {
            assertThat(block).containsEntry("error_type", errorType);
        }
    }

    private record Fixture(
            LoopDurabilityScope scope,
            SessionInteractiveControlTransactionService.InteractiveIntentCommand intentCommand) {
    }

    private record CommittedFixture(
            Fixture fixture,
            ExecutionClaimAck closedExecution,
            SessionInteractiveControlTransactionService.InteractiveResultAck result) {
    }
}
