package com.skillforge.server.session;

import com.skillforge.core.engine.durability.DurableFrontier;
import com.skillforge.core.engine.durability.ArchivePreparationAck;
import com.skillforge.core.engine.durability.ArchivePreparationCommand;
import com.skillforge.core.engine.durability.ArchivePreparationState;
import com.skillforge.core.engine.durability.DurableToolAttemptState;
import com.skillforge.core.engine.durability.ExecutionClaimAck;
import com.skillforge.core.engine.durability.ExecutionClaimCommand;
import com.skillforge.core.engine.durability.FrozenJson;
import com.skillforge.core.engine.durability.IntentCommitAck;
import com.skillforge.core.engine.durability.IntentCommitCommand;
import com.skillforge.core.engine.durability.LoopDurabilityScope;
import com.skillforge.core.engine.durability.MessageSnapshot;
import com.skillforge.core.engine.durability.ReplaySafety;
import com.skillforge.core.engine.durability.ToolCallIntent;
import com.skillforge.core.engine.durability.ToolCallManifest;
import com.skillforge.core.engine.durability.ToolResultCommitAck;
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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** PostgreSQL hard gates for atomic, provider-ordered Tool result closure. */
@Import({
        JacksonAutoConfiguration.class,
        PersistedMessageCodec.class,
        SessionOrderedMessageWriter.class,
        SessionToolAttemptTransactionService.class,
        OccurrenceArchivePreparation.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SessionToolResultCommitPostgresIT extends AbstractPostgresIT {

    private static final String TRIGGER_FAILURE = "forced_result_close_failure";

    @Autowired private SessionRepository sessionRepository;
    @Autowired private SessionToolAttemptTransactionService transactionService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockBean private ToolResultOccurrenceArchiveWriter occurrenceArchiveWriter;

    private String sessionIdToRemove;
    private String triggerName;
    private String functionName;

    @AfterEach
    void cleanFixture() {
        if (triggerName != null) {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + triggerName
                    + " ON t_session_tool_attempt");
        }
        if (functionName != null) {
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + functionName + "()");
        }
        if (sessionIdToRemove != null) {
            jdbcTemplate.update("DELETE FROM t_session WHERE id = ?", sessionIdToRemove);
        }
    }

    @Test
    void completeThreeResultVector_isCommittedOnceInManifestOrder() {
        Fixture fixture = createExecutingFixture();
        ToolResultCommitCommand command = resultCommand(fixture, List.of(
                Message.toolResult(fixture.toolUseIds().get(0), "finished-second", false),
                Message.toolResult(fixture.toolUseIds().get(1), "finished-third", true, "EXECUTION"),
                Message.toolResult(fixture.toolUseIds().get(2), "finished-first", false)));

        ToolResultCommitAck ack = transactionService.commitResults(command);

        assertThat(ack.attemptId()).isEqualTo(fixture.intentAck().attemptId());
        assertThat(ack.stepId()).isEqualTo(fixture.intentAck().stepId());
        assertThat(ack.resultBatchId()).isEqualTo(command.resultBatchId());
        assertThat(ack.executionScope()).isEqualTo(fixture.claimAck().executionScope());
        assertThat(ack.executionGeneration()).isEqualTo(1L);
        assertThat(ack.preResultFrontier()).isEqualTo(command.expectedPreResultFrontier());
        assertThat(ack.results()).hasSize(3);
        assertThat(ack.results()).extracting(result -> result.writeBatchOrdinal())
                .containsExactly(0, 1, 2);
        assertThat(ack.results()).extracting(result -> result.seqNo())
                .containsExactly(1L, 2L, 3L);
        assertThat(ack.results()).extracting(result -> toolUseId(result.message()))
                .containsExactlyElementsOf(fixture.toolUseIds());
        assertThat(ack.postResultFrontier()).isEqualTo(new DurableFrontier(
                ack.results().get(2).messageId(), 3L));

        assertThat(jdbcTemplate.queryForMap("""
                SELECT state, result_batch_id, result_execution_generation,
                       result_execution_fence, archive_preparation_state,
                       archive_prepared_count, archive_total_count
                FROM t_session_tool_attempt WHERE id = ?
                """, command.attemptId()))
                .containsEntry("state", "RESULTS_COMMITTED")
                .containsEntry("result_batch_id", command.resultBatchId())
                .containsEntry("result_execution_generation", 1L)
                .containsEntry("result_execution_fence", fixture.scope().loopFence())
                .containsEntry("archive_preparation_state", "PENDING")
                .containsEntry("archive_prepared_count", 0)
                .containsEntry("archive_total_count", 3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT message_count FROM t_session WHERE id = ?",
                Integer.class,
                fixture.scope().sessionId())).isEqualTo(4);
    }

    @Test
    void sameResultBatchAfterAckLoss_returnsSameAckWithoutRewritingRows() {
        Fixture fixture = createExecutingFixture();
        ToolResultCommitCommand command = resultCommand(fixture, List.of(
                Message.toolResult(fixture.toolUseIds().get(0), "stable-a", false),
                Message.toolResult(fixture.toolUseIds().get(1), "stable-b", false),
                Message.toolResult(fixture.toolUseIds().get(2), "stable-c", false)));

        ToolResultCommitAck first = transactionService.commitResults(command);
        List<Map<String, Object>> firstRows = jdbcTemplate.queryForList("""
                SELECT id, seq_no, write_batch_ordinal, content_json, created_at
                FROM t_session_message
                WHERE session_id = ? AND write_batch_id = ?
                ORDER BY write_batch_ordinal
                """, fixture.scope().sessionId(), command.resultBatchId().toString());
        Map<String, Object> firstAttempt = jdbcTemplate.queryForMap("""
                SELECT state, result_batch_id, result_execution_generation,
                       result_execution_fence, archive_preparation_state,
                       archive_prepared_count, archive_total_count, updated_at
                FROM t_session_tool_attempt WHERE id = ?
                """, command.attemptId());

        ToolResultCommitAck retry = transactionService.commitResults(command);

        assertThat(retry).isEqualTo(first);
        assertThat(jdbcTemplate.queryForList("""
                SELECT id, seq_no, write_batch_ordinal, content_json, created_at
                FROM t_session_message
                WHERE session_id = ? AND write_batch_id = ?
                ORDER BY write_batch_ordinal
                """, fixture.scope().sessionId(), command.resultBatchId().toString()))
                .isEqualTo(firstRows);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT state, result_batch_id, result_execution_generation,
                       result_execution_fence, archive_preparation_state,
                       archive_prepared_count, archive_total_count, updated_at
                FROM t_session_tool_attempt WHERE id = ?
                """, command.attemptId())).isEqualTo(firstAttempt);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM t_session_message WHERE session_id = ?",
                Long.class,
                fixture.scope().sessionId())).isEqualTo(4L);
    }

    @Test
    void sameResultBatchAfterAckLoss_returnsSameAckAfterLeaseExpiry() {
        Fixture fixture = createExecutingFixture();
        ToolResultCommitCommand command = resultCommand(fixture, List.of(
                Message.toolResult(fixture.toolUseIds().get(0), "stable-a", false),
                Message.toolResult(fixture.toolUseIds().get(1), "stable-b", false),
                Message.toolResult(fixture.toolUseIds().get(2), "stable-c", false)));
        ToolResultCommitAck first = transactionService.commitResults(command);
        jdbcTemplate.update(
                "UPDATE t_session SET loop_lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second' "
                        + "WHERE id = ?",
                fixture.scope().sessionId());
        jdbcTemplate.update(
                "UPDATE t_session_tool_attempt "
                        + "SET execution_lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second' "
                        + "WHERE id = ?",
                command.attemptId());

        ToolResultCommitAck retry = transactionService.commitResults(command);

        assertThat(retry).isEqualTo(first);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM t_session_message WHERE session_id = ?",
                Long.class,
                fixture.scope().sessionId())).isEqualTo(4L);
    }

    @Test
    void restartArchivePreparation_retriesTwiceThenMarksCompleteWithoutChangingRawResults() {
        Fixture fixture = createExecutingFixture();
        ToolResultCommitAck resultAck = transactionService.commitResults(resultCommand(fixture, List.of(
                Message.toolResult(fixture.toolUseIds().get(0), "raw-a", false),
                Message.toolResult(fixture.toolUseIds().get(1), "raw-b", false),
                Message.toolResult(fixture.toolUseIds().get(2), "raw-c", false))));
        ArchivePreparationCommand command = ArchivePreparationCommand.from(resultAck);
        org.mockito.Mockito.doThrow(new ArchivePreparationStorageException())
                .doThrow(new ArchivePreparationStorageException())
                .doNothing()
                .when(occurrenceArchiveWriter).prepare(command.resultBlocks());

        ArchivePreparationAck ack = occurrenceArchivePreparation.ensurePrepared(command);

        assertThat(ack.state()).isEqualTo(ArchivePreparationState.PREPARED);
        assertThat(ack.preparedCount()).isEqualTo(3);
        assertThat(ack.totalCount()).isEqualTo(3);
        assertThat(ack.resultBlocks()).isEqualTo(resultAck.resultBlocks());
        org.mockito.Mockito.verify(occurrenceArchiveWriter, org.mockito.Mockito.times(3))
                .prepare(command.resultBlocks());
        assertThat(jdbcTemplate.queryForMap("""
                SELECT archive_preparation_state, archive_prepared_count, archive_total_count
                FROM t_session_tool_attempt WHERE id = ?
                """, command.attemptId()))
                .containsEntry("archive_preparation_state", "PREPARED")
                .containsEntry("archive_prepared_count", 3)
                .containsEntry("archive_total_count", 3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM t_session_message WHERE session_id = ?",
                Long.class,
                fixture.scope().sessionId())).isEqualTo(4L);
    }

    @Test
    void restartArchivePreparation_afterThreeFailuresRecordsStableRawFallback() {
        Fixture fixture = createExecutingFixture();
        ToolResultCommitAck resultAck = transactionService.commitResults(resultCommand(fixture, List.of(
                Message.toolResult(fixture.toolUseIds().get(0), "fallback-a", false),
                Message.toolResult(fixture.toolUseIds().get(1), "fallback-b", false),
                Message.toolResult(fixture.toolUseIds().get(2), "fallback-c", false))));
        ArchivePreparationCommand command = ArchivePreparationCommand.from(resultAck);
        org.mockito.Mockito.doThrow(new ArchivePreparationStorageException())
                .when(occurrenceArchiveWriter).prepare(command.resultBlocks());

        ArchivePreparationAck first = occurrenceArchivePreparation.ensurePrepared(command);
        ArchivePreparationAck retry = occurrenceArchivePreparation.ensurePrepared(command);

        assertThat(first.state()).isEqualTo(ArchivePreparationState.RAW_FALLBACK);
        assertThat(first.preparedCount()).isZero();
        assertThat(first.totalCount()).isEqualTo(3);
        assertThat(first.resultBlocks()).isEqualTo(resultAck.resultBlocks());
        assertThat(retry).isEqualTo(first);
        org.mockito.Mockito.verify(occurrenceArchiveWriter, org.mockito.Mockito.times(3))
                .prepare(command.resultBlocks());
        assertThat(jdbcTemplate.queryForMap("""
                SELECT state, archive_preparation_state,
                       archive_prepared_count, archive_total_count
                FROM t_session_tool_attempt WHERE id = ?
                """, command.attemptId()))
                .containsEntry("state", "RESULTS_COMMITTED")
                .containsEntry("archive_preparation_state", "RAW_FALLBACK")
                .containsEntry("archive_prepared_count", 0)
                .containsEntry("archive_total_count", 3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM t_session_message WHERE session_id = ?",
                Long.class,
                fixture.scope().sessionId())).isEqualTo(4L);
    }

    @Test
    void archiveIntegrityFailure_neverDegradesToRawFallback() {
        Fixture fixture = createExecutingFixture();
        ToolResultCommitAck resultAck = transactionService.commitResults(resultCommand(fixture, List.of(
                Message.toolResult(fixture.toolUseIds().get(0), "conflict-a", false),
                Message.toolResult(fixture.toolUseIds().get(1), "conflict-b", false),
                Message.toolResult(fixture.toolUseIds().get(2), "conflict-c", false))));
        ArchivePreparationCommand command = ArchivePreparationCommand.from(resultAck);
        org.mockito.Mockito.doThrow(new ArchivePreparationIntegrityException())
                .when(occurrenceArchiveWriter).prepare(command.resultBlocks());

        Throwable failure = catchThrowable(
                () -> occurrenceArchivePreparation.ensurePrepared(command));

        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Tool result archive occurrence is partial or inconsistent")
                .hasNoCause();
        org.mockito.Mockito.verify(occurrenceArchiveWriter)
                .prepare(command.resultBlocks());
        assertThat(jdbcTemplate.queryForMap("""
                SELECT archive_preparation_state, archive_prepared_count, archive_total_count
                FROM t_session_tool_attempt WHERE id = ?
                """, command.attemptId()))
                .containsEntry("archive_preparation_state", "PENDING")
                .containsEntry("archive_prepared_count", 0)
                .containsEntry("archive_total_count", 3);
    }

    @Test
    void staleResultRunnerCannotPrepareArchive_afterSessionFenceTakeover() {
        Fixture fixture = createExecutingFixture();
        ToolResultCommitAck resultAck = transactionService.commitResults(resultCommand(fixture, List.of(
                Message.toolResult(fixture.toolUseIds().get(0), "takeover-a", false),
                Message.toolResult(fixture.toolUseIds().get(1), "takeover-b", false),
                Message.toolResult(fixture.toolUseIds().get(2), "takeover-c", false))));
        ArchivePreparationCommand stale = ArchivePreparationCommand.from(resultAck);
        LoopDurabilityScope recoveryScope = new LoopDurabilityScope(
                fixture.scope().sessionId(),
                fixture.scope().userId(),
                fixture.scope().historyEpoch(),
                UUID.randomUUID().toString(),
                fixture.scope().loopFence() + 1L,
                "archive-recovery-owner");
        jdbcTemplate.update("""
                UPDATE t_session
                SET active_loop_id = ?, loop_fence = ?, loop_owner_instance_id = ?,
                    loop_lease_until = CURRENT_TIMESTAMP + INTERVAL '5 minutes'
                WHERE id = ?
                """, recoveryScope.loopId(), recoveryScope.loopFence(),
                recoveryScope.ownerInstanceId(), recoveryScope.sessionId());

        Throwable staleFailure = catchThrowable(
                () -> occurrenceArchivePreparation.ensurePrepared(stale));

        assertThat(staleFailure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Tool result archive occurrence is partial or inconsistent")
                .hasNoCause();
        org.mockito.Mockito.verifyNoInteractions(occurrenceArchiveWriter);

        ArchivePreparationAck recovered = occurrenceArchivePreparation.ensurePrepared(
                stale.withCoordinatorScope(recoveryScope));

        assertThat(recovered.state()).isEqualTo(ArchivePreparationState.PREPARED);
        assertThat(recovered.executionScope()).isEqualTo(resultAck.executionScope());
        org.mockito.Mockito.verify(occurrenceArchiveWriter).prepare(stale.resultBlocks());

        AtomicBoolean staleVisibility = new AtomicBoolean();
        Throwable staleVisibilityFailure = catchThrowable(() ->
                occurrenceArchivePreparation.withResultVisibilityAuthority(
                        stale, () -> staleVisibility.set(true)));
        assertThat(staleVisibilityFailure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Tool result archive occurrence is partial or inconsistent")
                .hasNoCause();
        assertThat(staleVisibility).isFalse();

        AtomicBoolean recoveredVisibility = new AtomicBoolean();
        occurrenceArchivePreparation.withResultVisibilityAuthority(
                stale.withCoordinatorScope(recoveryScope),
                () -> recoveredVisibility.set(true));
        assertThat(recoveredVisibility).isTrue();
    }

    @Test
    void visibilityCallbackHoldsSessionFenceAgainstConcurrentTakeover() throws Exception {
        Fixture fixture = createExecutingFixture();
        ToolResultCommitAck resultAck = transactionService.commitResults(resultCommand(
                fixture, List.of(
                        Message.toolResult(fixture.toolUseIds().get(0), "visible-a", false),
                        Message.toolResult(fixture.toolUseIds().get(1), "visible-b", false),
                        Message.toolResult(fixture.toolUseIds().get(2), "visible-c", false))));
        ArchivePreparationCommand command = ArchivePreparationCommand.from(resultAck);
        occurrenceArchivePreparation.ensurePrepared(command);
        CountDownLatch visibilityEntered = new CountDownLatch(1);
        CountDownLatch releaseVisibility = new CountDownLatch(1);

        CompletableFuture<Void> visibility = CompletableFuture.runAsync(() ->
                occurrenceArchivePreparation.withResultVisibilityAuthority(command, () -> {
                    visibilityEntered.countDown();
                    try {
                        if (!releaseVisibility.await(2L, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test visibility release timed out");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("test visibility interrupted");
                    }
                }));
        assertThat(visibilityEntered.await(2L, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Integer> takeover = CompletableFuture.supplyAsync(() ->
                jdbcTemplate.update("""
                        UPDATE t_session
                        SET active_loop_id = ?, loop_fence = loop_fence + 1,
                            loop_owner_instance_id = ?,
                            loop_lease_until = CURRENT_TIMESTAMP + INTERVAL '5 minutes'
                        WHERE id = ?
                        """, UUID.randomUUID().toString(), "concurrent-recovery-owner",
                        fixture.scope().sessionId()));
        Thread.sleep(100L);
        assertThat(takeover).isNotDone();

        releaseVisibility.countDown();
        visibility.get(2L, TimeUnit.SECONDS);
        assertThat(takeover.get(2L, TimeUnit.SECONDS)).isEqualTo(1);

        AtomicBoolean lateVisibility = new AtomicBoolean();
        Throwable lateFailure = catchThrowable(() ->
                occurrenceArchivePreparation.withResultVisibilityAuthority(
                        command, () -> lateVisibility.set(true)));
        assertThat(lateFailure).isInstanceOf(IllegalStateException.class);
        assertThat(lateVisibility).isFalse();
    }

    @Test
    void resultVectorWithWrongToolUseOrder_isRejectedWithoutAppendingRows() {
        Fixture fixture = createExecutingFixture();
        ToolResultCommitCommand command = resultCommand(fixture, List.of(
                Message.toolResult(fixture.toolUseIds().get(1), "wrong-first", false),
                Message.toolResult(fixture.toolUseIds().get(0), "wrong-second", false),
                Message.toolResult(fixture.toolUseIds().get(2), "third", false)));

        Throwable failure = catchThrowable(() -> transactionService.commitResults(command));

        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Durable Tool result vector is partial or inconsistent")
                .hasNoCause();
        assertExecutingAttemptAndAssistantOnly(fixture);
    }

    @Test
    void corruptManifestHash_isRejectedWithoutAppendingResultRows() {
        Fixture fixture = createExecutingFixture();
        ToolResultCommitCommand command = resultCommand(fixture, List.of(
                Message.toolResult(fixture.toolUseIds().get(0), "a", false),
                Message.toolResult(fixture.toolUseIds().get(1), "b", false),
                Message.toolResult(fixture.toolUseIds().get(2), "c", false)));
        jdbcTemplate.update("""
                UPDATE t_session_tool_attempt
                SET manifest_hash = ?
                WHERE id = ?
                """, "f".repeat(64), command.attemptId());

        Throwable failure = catchThrowable(() -> transactionService.commitResults(command));

        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Durable Tool result vector is partial or inconsistent")
                .hasNoCause();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM t_session_message WHERE session_id = ?",
                Long.class,
                fixture.scope().sessionId())).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT state FROM t_session_tool_attempt WHERE id = ?",
                String.class,
                command.attemptId())).isEqualTo("EXECUTING");
    }

    @Test
    void orphanPartialResultBatch_isRejectedAndNeverRepaired() {
        Fixture fixture = createExecutingFixture();
        ToolResultCommitCommand command = resultCommand(fixture, List.of(
                Message.toolResult(fixture.toolUseIds().get(0), "complete-a", false),
                Message.toolResult(fixture.toolUseIds().get(1), "complete-b", false),
                Message.toolResult(fixture.toolUseIds().get(2), "complete-c", false)));
        jdbcTemplate.update("""
                INSERT INTO t_session_message
                    (session_id, seq_no, role, msg_type, message_type,
                     content_json, metadata_json, trace_id,
                     write_batch_id, write_batch_ordinal)
                VALUES (?, 1, 'user', 'NORMAL', 'normal', ?, '{}', 'result-trace', ?, 0)
                """,
                fixture.scope().sessionId(),
                "[{\"type\":\"tool_result\",\"tool_use_id\":\"result-tool-0\","
                        + "\"content\":\"complete-a\",\"is_error\":false}]",
                command.resultBatchId().toString());

        Throwable failure = catchThrowable(() -> transactionService.commitResults(command));

        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Durable Tool result vector is partial or inconsistent")
                .hasNoCause();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM t_session_message
                WHERE session_id = ? AND write_batch_id = ?
                """, Long.class, fixture.scope().sessionId(), command.resultBatchId().toString()))
                .isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT state FROM t_session_tool_attempt WHERE id = ?",
                String.class,
                command.attemptId())).isEqualTo("EXECUTING");
    }

    @Test
    void deferredAttemptCloseFailure_rollsBackAllResultRowsAndReturnsPayloadFreeFailure() {
        Fixture fixture = createExecutingFixture();
        ToolResultCommitCommand command = resultCommand(fixture, List.of(
                Message.toolResult(fixture.toolUseIds().get(0), "result-secret-a", false),
                Message.toolResult(fixture.toolUseIds().get(1), "result-secret-b", false),
                Message.toolResult(fixture.toolUseIds().get(2), "result-secret-c", false)));
        installDeferredResultFailureTrigger(fixture.scope().sessionId(), fixture.intentAck().stepId());

        Throwable failure = catchThrowable(() -> transactionService.commitResults(command));

        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Durable Tool result persistence failed")
                .hasNoCause();
        assertThat(failure.toString()).doesNotContain(
                fixture.scope().sessionId(), fixture.intentAck().stepId().toString(),
                command.resultBatchId().toString(), "result-secret", TRIGGER_FAILURE);
        assertExecutingAttemptAndAssistantOnly(fixture);
    }

    private Fixture createExecutingFixture() {
        String sessionId = UUID.randomUUID().toString();
        sessionIdToRemove = sessionId;
        long userId = 941L;
        long historyEpoch = 9L;
        String loopId = UUID.randomUUID().toString();
        long loopFence = 11L;
        String owner = "result-postgres-instance";
        SessionEntity session = new SessionEntity();
        session.setId(sessionId);
        session.setUserId(userId);
        session.setAgentId(942L);
        session.setStatus("active");
        session.setRuntimeStatus("running");
        session.setHistoryEpoch(historyEpoch);
        session.setActiveLoopId(loopId);
        session.setLoopFence(loopFence);
        session.setLoopOwnerInstanceId(owner);
        session.setLoopLeaseUntil(Instant.now().plusSeconds(300));
        sessionRepository.saveAndFlush(session);

        LoopDurabilityScope scope = new LoopDurabilityScope(
                sessionId, userId, historyEpoch, loopId, loopFence, owner);
        List<String> toolUseIds = List.of("result-tool-0", "result-tool-1", "result-tool-2");
        List<ContentBlock> blocks = new ArrayList<>();
        List<ToolCallIntent> calls = new ArrayList<>();
        for (int ordinal = 0; ordinal < toolUseIds.size(); ordinal++) {
            String toolUseId = toolUseIds.get(ordinal);
            String toolName = "ResultProbe" + ordinal;
            Map<String, Object> input = Map.of("ordinal", ordinal);
            blocks.add(ContentBlock.toolUse(toolUseId, toolName, input));
            calls.add(new ToolCallIntent(
                    ordinal, toolUseId, toolName, FrozenJson.capture(input),
                    ReplaySafety.READ_ONLY_REPLAYABLE));
        }
        Message assistant = new Message();
        assistant.setRole(Message.Role.ASSISTANT);
        assistant.setContent(blocks);
        ToolCallManifest manifest = new ToolCallManifest(
                calls, ReplaySafety.READ_ONLY_REPLAYABLE);
        IntentCommitAck intentAck = transactionService.commitIntent(new IntentCommitCommand(
                scope,
                UUID.randomUUID(),
                UUID.randomUUID().toString(),
                MessageSnapshot.capture(assistant),
                manifest,
                DurableFrontier.EMPTY,
                "result-trace"));
        ExecutionClaimAck claimAck = transactionService.claimExecution(new ExecutionClaimCommand(
                scope,
                intentAck.attemptId(),
                intentAck.stepId(),
                UUID.randomUUID(),
                DurableToolAttemptState.INTENT_COMMITTED,
                0L));
        return new Fixture(scope, intentAck, claimAck, toolUseIds);
    }

    @Autowired
    private OccurrenceArchivePreparation occurrenceArchivePreparation;

    private ToolResultCommitCommand resultCommand(Fixture fixture, List<Message> results) {
        return new ToolResultCommitCommand(
                fixture.claimAck().executionScope(),
                fixture.intentAck().attemptId(),
                fixture.intentAck().stepId(),
                fixture.claimAck().claimRequestId(),
                fixture.claimAck().executionGeneration(),
                UUID.randomUUID(),
                results.stream().map(MessageSnapshot::capture).toList(),
                new DurableFrontier(
                        fixture.intentAck().assistant().messageId(),
                        fixture.intentAck().assistant().seqNo()),
                fixture.intentAck().assistantPayloadHash(),
                fixture.intentAck().manifestHash(),
                "result-trace");
    }

    private void assertExecutingAttemptAndAssistantOnly(Fixture fixture) {
        assertThat(jdbcTemplate.queryForMap("""
                SELECT state, result_batch_id, result_execution_generation,
                       result_execution_fence, archive_preparation_state,
                       archive_prepared_count, archive_total_count
                FROM t_session_tool_attempt WHERE id = ?
                """, fixture.intentAck().attemptId()))
                .containsEntry("state", "EXECUTING")
                .containsEntry("result_batch_id", null)
                .containsEntry("result_execution_generation", null)
                .containsEntry("result_execution_fence", null)
                .containsEntry("archive_preparation_state", "NOT_STARTED")
                .containsEntry("archive_prepared_count", 0)
                .containsEntry("archive_total_count", 0);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM t_session_message WHERE session_id = ?",
                Long.class,
                fixture.scope().sessionId())).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT message_count FROM t_session WHERE id = ?",
                Integer.class,
                fixture.scope().sessionId())).isEqualTo(1);
    }

    @SuppressWarnings("unchecked")
    private static String toolUseId(MessageSnapshot snapshot) {
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) snapshot.toMessage().getContent();
        return (String) blocks.get(0).get("tool_use_id");
    }

    private void installDeferredResultFailureTrigger(String sessionId, UUID stepId) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        functionName = "fail_result_close_deferred_" + suffix;
        triggerName = "fail_result_close_deferred_" + suffix;
        jdbcTemplate.execute("""
                CREATE FUNCTION %s() RETURNS trigger
                LANGUAGE plpgsql
                AS $function$
                BEGIN
                    RAISE EXCEPTION '%s';
                END
                $function$
                """.formatted(functionName, TRIGGER_FAILURE));
        jdbcTemplate.execute("""
                CREATE CONSTRAINT TRIGGER %s
                AFTER UPDATE ON t_session_tool_attempt
                DEFERRABLE INITIALLY DEFERRED
                FOR EACH ROW
                WHEN (NEW.session_id = '%s' AND NEW.step_id = '%s'::uuid
                      AND OLD.state = 'EXECUTING' AND NEW.state = 'RESULTS_COMMITTED')
                EXECUTE FUNCTION %s()
                """.formatted(triggerName, sessionId, stepId, functionName));
    }

    private record Fixture(
            LoopDurabilityScope scope,
            IntentCommitAck intentAck,
            ExecutionClaimAck claimAck,
            List<String> toolUseIds) {
    }
}
