package com.skillforge.server.session;

import com.skillforge.core.engine.durability.DurableFrontier;
import com.skillforge.core.engine.durability.DurableToolAttemptState;
import com.skillforge.core.engine.durability.ExecutionClaimAck;
import com.skillforge.core.engine.durability.ExecutionClaimCommand;
import com.skillforge.core.engine.durability.FrozenJson;
import com.skillforge.core.engine.durability.IntentCommitAck;
import com.skillforge.core.engine.durability.IntentCommitCommand;
import com.skillforge.core.engine.durability.InteractiveStepPlanner;
import com.skillforge.core.engine.durability.LoopDurabilityScope;
import com.skillforge.core.engine.durability.MessageSnapshot;
import com.skillforge.core.engine.durability.ReplaySafety;
import com.skillforge.core.engine.durability.ToolCallIntent;
import com.skillforge.core.engine.durability.ToolCallManifest;
import com.skillforge.core.engine.durability.ToolResultCommitCommand;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** PostgreSQL crash-window and ACK-loss gates for synchronous durable cancellation. */
@Import({
        JacksonAutoConfiguration.class,
        PersistedMessageCodec.class,
        SessionOrderedMessageWriter.class,
        ArchivePayloadIdentityHasher.class,
        CanonicalToolResultOccurrenceArchiveWriter.class,
        OccurrenceArchivePreparation.class,
        SessionLoopAdmissionService.class,
        SessionToolAttemptTransactionService.class,
        SessionInteractiveControlTransactionService.class,
        SessionDurableCancellationService.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SessionDurableCancellationServicePostgresIT extends AbstractPostgresIT {

    private static final long USER_ID = 5101L;
    private static final String CANCEL_TRIGGER_ERROR = "forced_cancel_receipt_serialization_failure";

    @Autowired private SessionRepository sessionRepository;
    @Autowired private SessionLoopAdmissionService admissionService;
    @Autowired private SessionToolAttemptTransactionService attemptTransactions;
    @Autowired private SessionInteractiveControlTransactionService interactiveTransactions;
    @Autowired private SessionDurableCancellationService cancellationService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private String sessionIdToRemove;
    private String secondSessionIdToRemove;
    private String triggerName;
    private String functionName;

    @AfterEach
    void cleanFixture() {
        if (triggerName != null) {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + triggerName
                    + " ON t_session_cancel_receipt");
        }
        if (functionName != null) {
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + functionName + "()");
        }
        if (sessionIdToRemove != null) {
            jdbcTemplate.update("DELETE FROM t_session WHERE id = ?", sessionIdToRemove);
        }
        if (secondSessionIdToRemove != null) {
            jdbcTemplate.update("DELETE FROM t_session WHERE id = ?", secondSessionIdToRemove);
        }
    }

    @Test
    void cancel_beforeAnyToolAttempt_persistsReceiptAndFencesProviderContinuation() {
        LoopDurabilityScope scope = createRunningSession();
        UUID requestId = UUID.randomUUID();
        SessionDurableCancellationService.CancellationCommand command =
                new SessionDurableCancellationService.CancellationCommand(
                        requestId, scope.sessionId(), USER_ID);

        SessionDurableCancellationService.CancellationAck first =
                cancellationService.cancel(command);
        SessionDurableCancellationService.CancellationAck retry =
                cancellationService.cancel(command);

        assertThat(retry).isEqualTo(first);
        assertThat(first.requestId()).isEqualTo(requestId);
        assertThat(first.sessionId()).isEqualTo(scope.sessionId());
        assertThat(first.userId()).isEqualTo(USER_ID);
        assertThat(first.historyEpoch()).isEqualTo(scope.historyEpoch());
        assertThat(first.targetLoopId()).isEqualTo(scope.loopId());
        assertThat(first.targetLoopFence()).isEqualTo(scope.loopFence());
        assertThat(first.targetOwnerInstanceId()).isEqualTo(scope.ownerInstanceId());
        assertThat(first.attemptId()).isNull();
        assertThat(first.executionGeneration()).isNull();
        assertThat(first.outcome()).isEqualTo(
                SessionDurableCancellationService.CancellationOutcome.CANCELLED_BEFORE_EXECUTION);
        assertThat(first.createdAt()).isNotNull();
        assertSessionFenced(scope.sessionId(), "idle", "cancelled", "none");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM t_session_cancel_receipt WHERE request_id = ?",
                Long.class, requestId)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM t_session_tool_attempt WHERE session_id = ?",
                Long.class, scope.sessionId())).isZero();
    }

    @Test
    void cancel_afterIntentBeforeClaim_closesExactResultVectorWithoutExecutingTool() {
        IntentFixture fixture = createCommittedIntent(2);
        UUID requestId = UUID.randomUUID();

        SessionDurableCancellationService.CancellationCommand command =
                new SessionDurableCancellationService.CancellationCommand(
                        requestId, fixture.scope().sessionId(), USER_ID);
        SessionDurableCancellationService.CancellationAck ack =
                cancellationService.cancel(command);
        SessionDurableCancellationService.CancellationAck retry =
                cancellationService.cancel(command);

        assertThat(retry).isEqualTo(ack);
        assertThat(ack.outcome()).isEqualTo(
                SessionDurableCancellationService.CancellationOutcome.CANCELLED_BEFORE_EXECUTION);
        assertThat(ack.attemptId()).isEqualTo(fixture.intent().attemptId());
        assertThat(ack.executionGeneration()).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT state, execution_generation, archive_preparation_state,
                       archive_prepared_count, archive_total_count
                FROM t_session_tool_attempt WHERE id = ?
                """, fixture.intent().attemptId()))
                .containsEntry("state", "RESULTS_COMMITTED")
                .containsEntry("execution_generation", 1L)
                .containsEntry("archive_preparation_state", "PREPARED")
                .containsEntry("archive_prepared_count", 2)
                .containsEntry("archive_total_count", 2);
        assertThat(jdbcTemplate.queryForList("""
                SELECT seq_no, role, content_json, write_batch_ordinal
                FROM t_session_message
                WHERE session_id = ?
                ORDER BY seq_no
                """, fixture.scope().sessionId()))
                .hasSize(3);
        assertThat(receiptRow(requestId).toString())
                .doesNotContain("secret-tool-input", "CancelProbe", "cancel-tool-use");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM t_session_message
                WHERE session_id = ? AND content_json LIKE '%USER_CANCELLED%'
                """, Long.class, fixture.scope().sessionId())).isEqualTo(2L);
        assertSessionFenced(fixture.scope().sessionId(), "idle", "cancelled", "none");
    }

    @Test
    void cancel_parkedWaitingControl_closesFullVectorAndAckLossRetryDoesNotBumpGeneration() {
        WaitingFixture fixture = createParkedWaitingFixture();
        UUID requestId = UUID.randomUUID();
        SessionDurableCancellationService.CancellationCommand command =
                new SessionDurableCancellationService.CancellationCommand(
                        requestId, fixture.scope().sessionId(), USER_ID);

        SessionDurableCancellationService.CancellationAck first =
                cancellationService.cancel(command);
        SessionDurableCancellationService.CancellationAck retry =
                cancellationService.cancel(command);

        assertThat(retry).isEqualTo(first);
        assertThat(first.outcome()).isEqualTo(
                SessionDurableCancellationService.CancellationOutcome.CANCELLED_BEFORE_EXECUTION);
        assertThat(first.attemptId()).isEqualTo(fixture.waiting().intent().attemptId());
        assertThat(first.executionGeneration()).isEqualTo(1L);
        assertThat(first.targetLoopFence()).isEqualTo(fixture.scope().loopFence() + 1L);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT state, execution_generation, result_batch_id,
                       archive_preparation_state, archive_prepared_count, archive_total_count
                FROM t_session_tool_attempt WHERE id = ?
                """, fixture.waiting().intent().attemptId()))
                .containsEntry("state", "RESULTS_COMMITTED")
                .containsEntry("execution_generation", 1L)
                .containsEntry("archive_preparation_state", "PREPARED")
                .containsEntry("archive_prepared_count", 3)
                .containsEntry("archive_total_count", 3);
        Map<String, Object> control = jdbcTemplate.queryForMap("""
                SELECT answered_at, metadata_json
                FROM t_session_message
                WHERE session_id = ? AND control_id = ?
                """, fixture.scope().sessionId(), fixture.controlId());
        assertThat(control.get("answered_at")).isNotNull();
        assertThat(control.get("metadata_json").toString())
                .contains("\"state\":\"cancelled\"")
                .contains("\"answer\":null")
                .contains("\"answerMode\":\"cancel\"");
        List<Map<String, Object>> results = jdbcTemplate.queryForList("""
                SELECT write_batch_ordinal, content_json
                FROM t_session_message
                WHERE session_id = ?
                  AND write_batch_id = (
                      SELECT result_batch_id::text
                      FROM t_session_tool_attempt WHERE id = ?)
                ORDER BY write_batch_ordinal
                """, fixture.scope().sessionId(), fixture.waiting().intent().attemptId());
        assertThat(results).hasSize(3);
        assertThat(results.get(0).get("content_json").toString())
                .contains("normal-before", InteractiveStepPlanner.ABORTED_RESULT_ERROR_TYPE);
        assertThat(results.get(1).get("content_json").toString())
                .contains("ask-cancel", "USER_CANCELLED");
        assertThat(results.get(2).get("content_json").toString())
                .contains("normal-after", InteractiveStepPlanner.ABORTED_RESULT_ERROR_TYPE);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM t_session_cancel_receipt WHERE request_id = ?",
                Long.class, requestId)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT execution_generation FROM t_session_tool_attempt WHERE id = ?",
                Long.class, fixture.waiting().intent().attemptId())).isEqualTo(1L);
        assertSessionFenced(fixture.scope().sessionId(), "idle", "cancelled", "none");
    }

    @Test
    void cancel_afterResultCommitBeforeArchive_preparesExactOccurrenceBeforeFencingLoop() {
        IntentFixture fixture = createCommittedIntent(1);
        ExecutionClaimAck execution = attemptTransactions.claimExecution(
                new ExecutionClaimCommand(
                        fixture.scope(), fixture.intent().attemptId(), fixture.intent().stepId(),
                        UUID.randomUUID(), DurableToolAttemptState.INTENT_COMMITTED, 0L));
        attemptTransactions.commitResults(new ToolResultCommitCommand(
                fixture.scope(), execution.attemptId(), execution.stepId(),
                execution.claimRequestId(), execution.executionGeneration(), UUID.randomUUID(),
                List.of(MessageSnapshot.capture(Message.toolResult(
                        fixture.toolUseIds().get(0), "completed result", false))),
                new DurableFrontier(
                        fixture.intent().assistant().messageId(),
                        fixture.intent().assistant().seqNo()),
                fixture.intent().assistantPayloadHash(), fixture.intent().manifestHash(),
                "result-before-cancel"));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT archive_preparation_state FROM t_session_tool_attempt WHERE id = ?
                """, String.class, execution.attemptId())).isEqualTo("PENDING");

        SessionDurableCancellationService.CancellationAck ack = cancellationService.cancel(
                new SessionDurableCancellationService.CancellationCommand(
                        UUID.randomUUID(), fixture.scope().sessionId(), USER_ID));

        assertThat(ack.outcome()).isEqualTo(
                SessionDurableCancellationService.CancellationOutcome
                        .CANCELLED_AFTER_RESULTS_COMMITTED);
        assertThat(ack.attemptId()).isEqualTo(execution.attemptId());
        assertThat(ack.executionGeneration()).isEqualTo(execution.executionGeneration());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT archive_preparation_state FROM t_session_tool_attempt WHERE id = ?
                """, String.class, execution.attemptId())).isEqualTo("PREPARED");
        assertSessionFenced(
                fixture.scope().sessionId(), "idle", "cancelled", "possible");
    }

    @Test
    void cancel_duringExecution_parksUncertaintyStopsLeaseAndRejectsLateResult() {
        IntentFixture fixture = createCommittedIntent(1);
        ExecutionClaimAck execution = attemptTransactions.claimExecution(
                new ExecutionClaimCommand(
                        fixture.scope(), fixture.intent().attemptId(), fixture.intent().stepId(),
                        UUID.randomUUID(), DurableToolAttemptState.INTENT_COMMITTED, 0L));
        UUID requestId = UUID.randomUUID();

        SessionDurableCancellationService.CancellationAck ack = cancellationService.cancel(
                new SessionDurableCancellationService.CancellationCommand(
                        requestId, fixture.scope().sessionId(), USER_ID));

        assertThat(ack.outcome()).isEqualTo(
                SessionDurableCancellationService.CancellationOutcome.EXECUTION_OUTCOME_UNCERTAIN);
        assertThat(ack.attemptId()).isEqualTo(execution.attemptId());
        assertThat(ack.executionGeneration()).isEqualTo(execution.executionGeneration());
        Map<String, Object> attempt = jdbcTemplate.queryForMap("""
                SELECT state, execution_generation, execution_lease_until
                FROM t_session_tool_attempt WHERE id = ?
                """, execution.attemptId());
        assertThat(attempt)
                .containsEntry("state", "UNCERTAIN_PENDING_RESOLUTION")
                .containsEntry("execution_generation", execution.executionGeneration());
        assertThat(((java.sql.Timestamp) attempt.get("execution_lease_until")).toInstant())
                .isBeforeOrEqualTo(ack.createdAt());
        assertSessionFenced(fixture.scope().sessionId(), "error",
                "cancelled_tool_outcome_uncertain", "possible");

        Throwable lateResult = catchThrowable(() -> attemptTransactions.commitResults(
                new ToolResultCommitCommand(
                        fixture.scope(), execution.attemptId(), execution.stepId(),
                        execution.claimRequestId(), execution.executionGeneration(),
                        UUID.randomUUID(),
                        List.of(MessageSnapshot.capture(Message.toolResult(
                                fixture.toolUseIds().get(0), "late result", false))),
                        new DurableFrontier(
                                fixture.intent().assistant().messageId(),
                                fixture.intent().assistant().seqNo()),
                        fixture.intent().assistantPayloadHash(),
                        fixture.intent().manifestHash(), "late-result")));
        assertThat(lateResult).isInstanceOf(IllegalStateException.class);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM t_session_message WHERE session_id = ?
                """, Long.class, fixture.scope().sessionId())).isOne();
    }

    @Test
    void cancel_commitAckLossRetry_returnsSameReceiptWithoutChangingRows() {
        IntentFixture fixture = createCommittedIntent(1);
        ExecutionClaimAck execution = attemptTransactions.claimExecution(
                new ExecutionClaimCommand(
                        fixture.scope(), fixture.intent().attemptId(), fixture.intent().stepId(),
                        UUID.randomUUID(), DurableToolAttemptState.INTENT_COMMITTED, 0L));
        SessionDurableCancellationService.CancellationCommand command =
                new SessionDurableCancellationService.CancellationCommand(
                        UUID.randomUUID(), fixture.scope().sessionId(), USER_ID);

        SessionDurableCancellationService.CancellationAck first =
                cancellationService.cancel(command);
        Map<String, Object> firstReceipt = receiptRow(command.requestId());
        SessionDurableCancellationService.CancellationAck retry =
                cancellationService.cancel(command);

        assertThat(retry).isEqualTo(first);
        assertThat(receiptRow(command.requestId())).isEqualTo(firstReceipt);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM t_session_cancel_receipt WHERE request_id = ?",
                Long.class, command.requestId())).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT execution_generation FROM t_session_tool_attempt WHERE id = ?",
                Long.class, execution.attemptId())).isEqualTo(1L);
    }

    @Test
    void transientCommitFailure_rollsBackFenceAndReturnsPayloadFreeRetryableFailure() {
        LoopDurabilityScope scope = createRunningSession();
        UUID requestId = UUID.randomUUID();
        installDeferredSerializationFailure(requestId);

        Throwable failure = catchThrowable(() -> cancellationService.cancel(
                new SessionDurableCancellationService.CancellationCommand(
                        requestId, scope.sessionId(), USER_ID)));

        assertThat(failure)
                .isInstanceOf(DurableCancellationRetryableException.class)
                .hasMessage("Durable cancellation is temporarily unavailable")
                .hasNoCause();
        assertThat(failure.toString()).doesNotContain(
                scope.sessionId(), requestId.toString(), CANCEL_TRIGGER_ERROR);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM t_session_cancel_receipt WHERE request_id = ?",
                Long.class, requestId)).isZero();
        assertThat(jdbcTemplate.queryForMap("""
                SELECT active_loop_id, loop_fence, loop_owner_instance_id,
                       runtime_status
                FROM t_session WHERE id = ?
                """, scope.sessionId()))
                .containsEntry("active_loop_id", scope.loopId())
                .containsEntry("loop_fence", scope.loopFence())
                .containsEntry("loop_owner_instance_id", scope.ownerInstanceId())
                .containsEntry("runtime_status", "running");
    }

    @Test
    void transientCommitFailure_duringExecutionRollsBackAttemptParkingAndReceipt() {
        IntentFixture fixture = createCommittedIntent(1);
        ExecutionClaimAck execution = attemptTransactions.claimExecution(
                new ExecutionClaimCommand(
                        fixture.scope(), fixture.intent().attemptId(), fixture.intent().stepId(),
                        UUID.randomUUID(), DurableToolAttemptState.INTENT_COMMITTED, 0L));
        UUID requestId = UUID.randomUUID();
        installDeferredSerializationFailure(requestId);

        Throwable failure = catchThrowable(() -> cancellationService.cancel(
                new SessionDurableCancellationService.CancellationCommand(
                        requestId, fixture.scope().sessionId(), USER_ID)));

        assertThat(failure)
                .isInstanceOf(DurableCancellationRetryableException.class)
                .hasNoCause();
        assertThat(jdbcTemplate.queryForMap("""
                SELECT state, execution_generation, execution_lease_until
                FROM t_session_tool_attempt WHERE id = ?
                """, execution.attemptId()))
                .containsEntry("state", "EXECUTING")
                .containsEntry("execution_generation", execution.executionGeneration())
                .containsEntry("execution_lease_until",
                        java.sql.Timestamp.from(execution.executionLeaseUntil()));
        assertThat(jdbcTemplate.queryForMap("""
                SELECT active_loop_id, loop_fence, loop_owner_instance_id
                FROM t_session WHERE id = ?
                """, fixture.scope().sessionId()))
                .containsEntry("active_loop_id", fixture.scope().loopId())
                .containsEntry("loop_fence", fixture.scope().loopFence())
                .containsEntry("loop_owner_instance_id", fixture.scope().ownerInstanceId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM t_session_cancel_receipt WHERE request_id = ?",
                Long.class, requestId)).isZero();
    }

    @Test
    void staleReceiptRetryAfterNewFence_isRejectedWithoutCancellingNewLoop() {
        LoopDurabilityScope oldScope = createRunningSession();
        SessionDurableCancellationService.CancellationCommand command =
                new SessionDurableCancellationService.CancellationCommand(
                        UUID.randomUUID(), oldScope.sessionId(), USER_ID);
        cancellationService.cancel(command);
        String newLoopId = UUID.randomUUID().toString();
        String newOwner = "new-loop-owner";
        jdbcTemplate.update("""
                UPDATE t_session
                SET active_loop_id = ?, loop_fence = ?, loop_owner_instance_id = ?,
                    loop_lease_until = clock_timestamp() + INTERVAL '5 minutes',
                    runtime_status = 'running', runtime_step = NULL, runtime_error = NULL,
                    runtime_failure_source = NULL, runtime_failure_code = NULL,
                    runtime_retryable = FALSE, runtime_side_effects = NULL
                WHERE id = ?
                """, newLoopId, oldScope.loopFence() + 1L, newOwner, oldScope.sessionId());

        Throwable failure = catchThrowable(() -> cancellationService.cancel(command));

        assertThat(failure)
                .isInstanceOf(DurableCancellationRejectedException.class)
                .hasMessage("Durable cancellation target is no longer current")
                .hasNoCause();
        assertThat(jdbcTemplate.queryForMap("""
                SELECT active_loop_id, loop_fence, loop_owner_instance_id
                FROM t_session WHERE id = ?
                """, oldScope.sessionId()))
                .containsEntry("active_loop_id", newLoopId)
                .containsEntry("loop_fence", oldScope.loopFence() + 1L)
                .containsEntry("loop_owner_instance_id", newOwner);
    }

    @Test
    void crossUserAndReusedRequestIdForAnotherSession_failClosed() {
        LoopDurabilityScope firstScope = createRunningSession();
        UUID requestId = UUID.randomUUID();

        Throwable crossUser = catchThrowable(() -> cancellationService.cancel(
                new SessionDurableCancellationService.CancellationCommand(
                        requestId, firstScope.sessionId(), USER_ID + 1L)));
        assertThat(crossUser)
                .isInstanceOf(DurableCancellationRejectedException.class)
                .hasNoCause();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM t_session_cancel_receipt WHERE request_id = ?",
                Long.class, requestId)).isZero();

        SessionDurableCancellationService.CancellationAck accepted = cancellationService.cancel(
                new SessionDurableCancellationService.CancellationCommand(
                        requestId, firstScope.sessionId(), USER_ID));
        LoopDurabilityScope secondScope = createSecondRunningSession();
        Throwable reused = catchThrowable(() -> cancellationService.cancel(
                new SessionDurableCancellationService.CancellationCommand(
                        requestId, secondScope.sessionId(), USER_ID)));

        assertThat(reused)
                .isInstanceOf(DurableCancellationRejectedException.class)
                .hasNoCause();
        assertThat(receiptRow(requestId))
                .containsEntry("session_id", accepted.sessionId())
                .containsEntry("target_loop_id", accepted.targetLoopId());
        assertThat(jdbcTemplate.queryForMap("""
                SELECT active_loop_id, loop_fence FROM t_session WHERE id = ?
                """, secondScope.sessionId()))
                .containsEntry("active_loop_id", secondScope.loopId())
                .containsEntry("loop_fence", secondScope.loopFence());
    }

    @Test
    void migration_enforcesReceiptShapeAndRuntimeAppendOnlyPrivileges() {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT has_table_privilege(
                    'skillforge_app', 'public.t_session_cancel_receipt', 'SELECT')
                """, Boolean.class)).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT has_table_privilege(
                    'skillforge_app', 'public.t_session_cancel_receipt', 'INSERT')
                """, Boolean.class)).isTrue();
        for (String privilege : List.of("UPDATE", "DELETE", "TRUNCATE")) {
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT has_table_privilege(
                        'skillforge_app', 'public.t_session_cancel_receipt', ?)
                    """, Boolean.class, privilege)).isFalse();
        }
    }

    private LoopDurabilityScope createRunningSession() {
        String sessionId = UUID.randomUUID().toString();
        sessionIdToRemove = sessionId;
        return persistRunningSession(sessionId);
    }

    private LoopDurabilityScope createSecondRunningSession() {
        String sessionId = UUID.randomUUID().toString();
        secondSessionIdToRemove = sessionId;
        return persistRunningSession(sessionId);
    }

    private LoopDurabilityScope persistRunningSession(String sessionId) {
        String loopId = UUID.randomUUID().toString();
        long historyEpoch = 3L;
        long loopFence = 7L;
        String owner = "cancel-postgres-owner";
        SessionEntity session = new SessionEntity();
        session.setId(sessionId);
        session.setUserId(USER_ID);
        session.setAgentId(5102L);
        session.setStatus("active");
        session.setRuntimeStatus("running");
        session.setHistoryEpoch(historyEpoch);
        session.setActiveLoopId(loopId);
        session.setLoopFence(loopFence);
        session.setLoopOwnerInstanceId(owner);
        session.setLoopLeaseUntil(Instant.now().plusSeconds(300));
        sessionRepository.saveAndFlush(session);
        return new LoopDurabilityScope(
                sessionId, USER_ID, historyEpoch, loopId, loopFence, owner);
    }

    private IntentFixture createCommittedIntent(int toolCount) {
        LoopDurabilityScope scope = createRunningSession();
        List<String> toolUseIds = java.util.stream.IntStream.range(0, toolCount)
                .mapToObj(index -> "cancel-tool-use-" + index)
                .toList();
        List<ContentBlock> blocks = java.util.stream.IntStream.range(0, toolCount)
                .mapToObj(index -> ContentBlock.toolUse(
                        toolUseIds.get(index), "CancelProbe" + index,
                        Map.of("secret", "secret-tool-input-" + index)))
                .toList();
        List<ToolCallIntent> calls = java.util.stream.IntStream.range(0, toolCount)
                .mapToObj(index -> new ToolCallIntent(
                        index, toolUseIds.get(index), "CancelProbe" + index,
                        FrozenJson.capture(Map.of("secret", "secret-tool-input-" + index)),
                        ReplaySafety.MUTATING))
                .toList();
        Message assistant = new Message();
        assistant.setRole(Message.Role.ASSISTANT);
        assistant.setContent(blocks);
        IntentCommitAck intent = attemptTransactions.commitIntent(new IntentCommitCommand(
                scope, UUID.randomUUID(), UUID.randomUUID().toString(),
                MessageSnapshot.capture(assistant),
                new ToolCallManifest(calls, ReplaySafety.MUTATING),
                DurableFrontier.EMPTY, "cancel-intent"));
        return new IntentFixture(scope, intent, toolUseIds);
    }

    private WaitingFixture createParkedWaitingFixture() {
        LoopDurabilityScope scope = createRunningSession();
        List<ToolCallIntent> calls = List.of(
                new ToolCallIntent(
                        0, "normal-before", "NormalBefore", FrozenJson.capture(Map.of()),
                        ReplaySafety.MUTATING),
                new ToolCallIntent(
                        1, "ask-cancel", "ask_user",
                        FrozenJson.capture(Map.of("question", "Continue?")),
                        ReplaySafety.READ_ONLY_REPLAYABLE),
                new ToolCallIntent(
                        2, "normal-after", "NormalAfter", FrozenJson.capture(Map.of()),
                        ReplaySafety.MUTATING));
        Message assistant = new Message();
        assistant.setRole(Message.Role.ASSISTANT);
        assistant.setContent(List.of(
                ContentBlock.toolUse("normal-before", "NormalBefore", Map.of()),
                ContentBlock.toolUse("ask-cancel", "ask_user", Map.of("question", "Continue?")),
                ContentBlock.toolUse("normal-after", "NormalAfter", Map.of())));
        IntentCommitCommand intent = new IntentCommitCommand(
                scope,
                UUID.randomUUID(),
                UUID.randomUUID().toString(),
                MessageSnapshot.capture(assistant),
                new ToolCallManifest(calls, ReplaySafety.MUTATING),
                DurableFrontier.EMPTY,
                "waiting-cancel-intent");
        InteractiveStepPlanner.SelectedControl selected =
                new InteractiveStepPlanner.SelectedControl(
                        calls.get(1), InteractiveStepPlanner.CallKind.ASK_USER);
        String controlId = "waiting-cancel-control";
        SessionInteractiveControlTransactionService.InteractiveIntentAck waiting =
                interactiveTransactions.commitWaitingIntent(
                        new SessionInteractiveControlTransactionService.InteractiveIntentCommand(
                                intent,
                                new InteractiveStepPlanner.InteractiveStepPlan(calls, selected),
                                controlId,
                                "Continue?",
                                FrozenJson.capture(Map.of("question", "Continue?"))));
        admissionService.parkForManualContinuation(
                scope, DurableToolAttemptState.WAITING_USER, null);
        return new WaitingFixture(scope, waiting, controlId);
    }

    private void assertSessionFenced(
            String sessionId,
            String runtimeStatus,
            String runtimeStep,
            String sideEffects) {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT active_loop_id, loop_owner_instance_id, loop_lease_until,
                       runtime_status, runtime_step, runtime_failure_source,
                       runtime_failure_code, runtime_retryable, runtime_side_effects
                FROM t_session WHERE id = ?
                """, sessionId);
        assertThat(row)
                .containsEntry("active_loop_id", null)
                .containsEntry("loop_owner_instance_id", null)
                .containsEntry("loop_lease_until", null)
                .containsEntry("runtime_status", runtimeStatus)
                .containsEntry("runtime_step", runtimeStep)
                .containsEntry("runtime_retryable", false);
        if ("error".equals(runtimeStatus)) {
            assertThat(row)
                    .containsEntry("runtime_failure_source", "user_action")
                    .containsEntry("runtime_side_effects", sideEffects);
        } else {
            assertThat(row)
                    .containsEntry("runtime_failure_source", null)
                    .containsEntry("runtime_failure_code", null)
                    .containsEntry("runtime_side_effects", null);
        }
    }

    private Map<String, Object> receiptRow(UUID requestId) {
        return jdbcTemplate.queryForMap("""
                SELECT request_id, session_id, user_id, history_epoch,
                       target_loop_id, target_loop_fence, target_owner_instance_id,
                       attempt_id, execution_generation, outcome, created_at
                FROM t_session_cancel_receipt WHERE request_id = ?
                """, requestId);
    }

    private void installDeferredSerializationFailure(UUID requestId) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        functionName = "fail_cancel_receipt_" + suffix;
        triggerName = "fail_cancel_receipt_" + suffix;
        jdbcTemplate.execute("""
                CREATE FUNCTION %s() RETURNS trigger
                LANGUAGE plpgsql
                AS $function$
                BEGIN
                    RAISE EXCEPTION '%s' USING ERRCODE = '40001';
                END
                $function$
                """.formatted(functionName, CANCEL_TRIGGER_ERROR));
        jdbcTemplate.execute("""
                CREATE CONSTRAINT TRIGGER %s
                AFTER INSERT ON t_session_cancel_receipt
                DEFERRABLE INITIALLY DEFERRED
                FOR EACH ROW
                WHEN (NEW.request_id = '%s'::uuid)
                EXECUTE FUNCTION %s()
                """.formatted(triggerName, requestId, functionName));
    }

    private record IntentFixture(
            LoopDurabilityScope scope,
            IntentCommitAck intent,
            List<String> toolUseIds) {
    }

    private record WaitingFixture(
            LoopDurabilityScope scope,
            SessionInteractiveControlTransactionService.InteractiveIntentAck waiting,
            String controlId) {
    }
}
