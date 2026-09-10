package com.skillforge.server.session;

import com.skillforge.core.engine.durability.ArchivePreparationState;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** PostgreSQL restart-cut gates for durable Session recovery coordination. */
@Import({
        JacksonAutoConfiguration.class,
        PersistedMessageCodec.class,
        SessionOrderedMessageWriter.class,
        SessionLoopAdmissionService.class,
        SessionToolAttemptTransactionService.class,
        SessionInteractiveControlTransactionService.class,
        OccurrenceArchivePreparation.class,
        DurableSessionRecoveryCoordinator.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DurableSessionRecoveryCoordinatorPostgresIT extends AbstractPostgresIT {

    private static final long USER_ID = 1701L;
    private static final long HISTORY_EPOCH = 5L;

    @Autowired private SessionRepository sessionRepository;
    @Autowired private SessionToolAttemptTransactionService attemptTransactions;
    @Autowired private SessionInteractiveControlTransactionService interactiveTransactions;
    @Autowired private DurableSessionRecoveryCoordinator recoveryCoordinator;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockBean private ToolResultOccurrenceArchiveWriter occurrenceArchiveWriter;

    private String sessionIdToRemove;

    @AfterEach
    void cleanFixture() {
        if (sessionIdToRemove != null) {
            jdbcTemplate.update("DELETE FROM t_session WHERE id = ?", sessionIdToRemove);
        }
    }

    @Test
    void intentCommittedAfterCrash_isClaimedOnceAndReturnedForExactSafeReplay() {
        Fixture fixture = createCommittedIntent(ReplaySafety.READ_ONLY_REPLAYABLE);
        expireSessionLease(fixture.scope().sessionId());

        DurableSessionRecoveryCoordinator.RecoveryPlan plan =
                recoveryCoordinator.recover(fixture.scope().sessionId(), USER_ID);
        Map<String, Object> firstExecutionRow = executionRow(fixture.intentAck().attemptId());
        DurableSessionRecoveryCoordinator.RecoveryPlan retry =
                recoveryCoordinator.recover(fixture.scope().sessionId(), USER_ID);

        assertThat(plan.disposition())
                .isEqualTo(DurableSessionRecoveryCoordinator.RecoveryDisposition.TOOL_REPLAY);
        assertThat(plan.admission().state()).isEqualTo(DurableToolAttemptState.INTENT_COMMITTED);
        assertThat(plan.admission().scope().loopFence())
                .isEqualTo(fixture.scope().loopFence() + 1L);
        assertThat(plan.recoveredToolAttempt()).isNotNull();
        assertThat(plan.recoveredToolAttempt().intent()).isEqualTo(fixture.intentAck());
        assertThat(plan.recoveredToolAttempt().execution().state())
                .isEqualTo(DurableToolAttemptState.EXECUTING);
        assertThat(plan.recoveredToolAttempt().execution().executionGeneration()).isEqualTo(1L);
        assertThat(plan.recoveredToolAttempt().execution().executionScope())
                .isEqualTo(plan.admission().scope());
        assertThat(plan.frontier()).isEqualTo(new DurableFrontier(
                fixture.intentAck().assistant().messageId(),
                fixture.intentAck().assistant().seqNo()));
        assertThat(jdbcTemplate.queryForMap("""
                SELECT state, execution_loop_id, execution_fence,
                       execution_owner_instance_id, execution_generation
                FROM t_session_tool_attempt WHERE id = ?
                """, fixture.intentAck().attemptId()))
                .containsEntry("state", "EXECUTING")
                .containsEntry("execution_loop_id", plan.admission().scope().loopId())
                .containsEntry("execution_fence", plan.admission().scope().loopFence())
                .containsEntry("execution_owner_instance_id",
                        plan.admission().scope().ownerInstanceId())
                .containsEntry("execution_generation", 1L);
        assertThat(retry.disposition())
                .isEqualTo(DurableSessionRecoveryCoordinator.RecoveryDisposition.TOOL_REPLAY);
        assertThat(retry.admission().state()).isEqualTo(DurableToolAttemptState.EXECUTING);
        assertThat(retry.admission().attemptAlreadyClaimedByScope()).isTrue();
        assertThat(retry.admission().scope()).isEqualTo(plan.admission().scope());
        assertThat(retry.recoveredToolAttempt().intent()).isEqualTo(fixture.intentAck());
        assertThat(retry.recoveredToolAttempt().execution())
                .isEqualTo(plan.recoveredToolAttempt().execution());
        assertThat(retry.recoveredToolAttempt().execution().executionGeneration()).isEqualTo(1L);
        assertThat(executionRow(fixture.intentAck().attemptId())).isEqualTo(firstExecutionRow);
        assertThat(messageCount(fixture.scope().sessionId())).isEqualTo(1L);
        verifyNoInteractions(occurrenceArchiveWriter);
    }

    @Test
    void resultsCommittedAfterCrash_preparesExactArchiveThenContinuesProviderWithoutToolReplay() {
        Fixture fixture = createCommittedIntent(ReplaySafety.READ_ONLY_REPLAYABLE);
        ExecutionClaimAck execution = claimInitialExecution(fixture);
        ToolResultCommitAck result = attemptTransactions.commitResults(new ToolResultCommitCommand(
                execution.executionScope(),
                fixture.intentAck().attemptId(),
                fixture.intentAck().stepId(),
                execution.claimRequestId(),
                execution.executionGeneration(),
                UUID.randomUUID(),
                List.of(MessageSnapshot.capture(
                        Message.toolResult(fixture.toolUseId(), "durable result", false))),
                assistantFrontier(fixture),
                fixture.intentAck().assistantPayloadHash(),
                fixture.intentAck().manifestHash(),
                "result-trace"));
        expireSessionLease(fixture.scope().sessionId());

        DurableSessionRecoveryCoordinator.RecoveryPlan plan =
                recoveryCoordinator.recover(fixture.scope().sessionId(), USER_ID);

        assertThat(plan.disposition())
                .isEqualTo(DurableSessionRecoveryCoordinator.RecoveryDisposition.PROVIDER_CONTINUE);
        assertThat(plan.admission().state()).isEqualTo(DurableToolAttemptState.RESULTS_COMMITTED);
        assertThat(plan.recoveredToolAttempt()).isNull();
        assertThat(plan.archivePreparation()).isNotNull();
        assertThat(plan.archivePreparation().state()).isEqualTo(ArchivePreparationState.PREPARED);
        assertThat(plan.archivePreparation().resultBlocks()).isEqualTo(result.resultBlocks());
        assertThat(plan.frontier()).isEqualTo(result.postResultFrontier());
        assertThat(jdbcTemplate.queryForMap("""
                SELECT state, execution_generation, archive_preparation_state,
                       archive_prepared_count, archive_total_count
                FROM t_session_tool_attempt WHERE id = ?
                """, fixture.intentAck().attemptId()))
                .containsEntry("state", "RESULTS_COMMITTED")
                .containsEntry("execution_generation", 1L)
                .containsEntry("archive_preparation_state", "PREPARED")
                .containsEntry("archive_prepared_count", 1)
                .containsEntry("archive_total_count", 1);
        assertThat(messageCount(fixture.scope().sessionId())).isEqualTo(2L);
        verify(occurrenceArchiveWriter).prepare(result.resultBlocks());
    }

    @Test
    void unsafeExecutingAttemptAfterCrash_isFencedIntoUncertaintyWithoutReplay() {
        Fixture fixture = createCommittedIntent(ReplaySafety.MUTATING);
        ExecutionClaimAck initialExecution = claimInitialExecution(fixture);
        expireExecutionAndSessionLeases(
                fixture.scope().sessionId(), fixture.intentAck().attemptId());

        DurableSessionRecoveryCoordinator.RecoveryPlan plan =
                recoveryCoordinator.recover(fixture.scope().sessionId(), USER_ID);

        assertThat(plan.disposition()).isEqualTo(
                DurableSessionRecoveryCoordinator.RecoveryDisposition
                        .UNCERTAIN_PENDING_RESOLUTION);
        assertThat(plan.admission().state()).isEqualTo(DurableToolAttemptState.EXECUTING);
        assertThat(plan.recoveredToolAttempt()).isNull();
        assertThat(plan.archivePreparation()).isNull();
        assertThat(plan.frontier()).isEqualTo(assistantFrontier(fixture));
        assertThat(jdbcTemplate.queryForMap("""
                SELECT state, execution_loop_id, execution_fence,
                       execution_owner_instance_id, execution_generation,
                       result_batch_id
                FROM t_session_tool_attempt WHERE id = ?
                """, fixture.intentAck().attemptId()))
                .containsEntry("state", "UNCERTAIN_PENDING_RESOLUTION")
                .containsEntry("execution_loop_id", plan.admission().scope().loopId())
                .containsEntry("execution_fence", plan.admission().scope().loopFence())
                .containsEntry("execution_owner_instance_id",
                        plan.admission().scope().ownerInstanceId())
                .containsEntry("execution_generation",
                        initialExecution.executionGeneration() + 1L)
                .containsEntry("result_batch_id", null);
        assertThat(messageCount(fixture.scope().sessionId())).isEqualTo(1L);
        verifyNoInteractions(occurrenceArchiveWriter);
    }

    @Test
    void waitingUserAfterCrash_rebuildsExactDurableControlWithoutExecutionClaim() {
        InteractiveFixture fixture = createWaitingControl();
        SessionInteractiveControlTransactionService.InteractiveIntentAck committed =
                interactiveTransactions.commitWaitingIntent(fixture.command());
        expireSessionLease(fixture.scope().sessionId());

        DurableSessionRecoveryCoordinator.RecoveryPlan plan =
                recoveryCoordinator.recover(fixture.scope().sessionId(), USER_ID);

        assertThat(plan.disposition())
                .isEqualTo(DurableSessionRecoveryCoordinator.RecoveryDisposition.WAITING_USER);
        assertThat(plan.admission().state()).isEqualTo(DurableToolAttemptState.WAITING_USER);
        assertThat(plan.recoveredWaitingControl()).isEqualTo(committed);
        assertThat(plan.recoveredToolAttempt()).isNull();
        assertThat(plan.archivePreparation()).isNull();
        assertThat(plan.frontier()).isEqualTo(new DurableFrontier(
                committed.control().messageId(), committed.control().seqNo()));
        assertThat(jdbcTemplate.queryForMap("""
                SELECT state, execution_loop_id, execution_fence,
                       execution_owner_instance_id, execution_generation,
                       claim_request_id, claimed_at, execution_lease_until
                FROM t_session_tool_attempt WHERE id = ?
                """, committed.intent().attemptId()))
                .containsEntry("state", "WAITING_USER")
                .containsEntry("execution_loop_id", null)
                .containsEntry("execution_fence", null)
                .containsEntry("execution_owner_instance_id", null)
                .containsEntry("execution_generation", 0L)
                .containsEntry("claim_request_id", null)
                .containsEntry("claimed_at", null)
                .containsEntry("execution_lease_until", null);
        assertThat(messageCount(fixture.scope().sessionId())).isEqualTo(2L);
        verifyNoInteractions(occurrenceArchiveWriter);
    }

    @Test
    void waitingUserWithMissingControlAfterCrash_failsClosedAndParksRecovery() {
        InteractiveFixture fixture = createWaitingControl();
        SessionInteractiveControlTransactionService.InteractiveIntentAck committed =
                interactiveTransactions.commitWaitingIntent(fixture.command());
        jdbcTemplate.update(
                "DELETE FROM t_session_message WHERE id = ?",
                committed.control().messageId());
        expireSessionLease(fixture.scope().sessionId());

        Throwable failure = catchThrowable(
                () -> recoveryCoordinator.recover(fixture.scope().sessionId(), USER_ID));

        assertThat(failure)
                .isInstanceOf(DurableRecoveryFailureException.class)
                .hasMessage("Durable recovery failed")
                .hasNoCause();
        assertRecoveryParked(fixture.scope().sessionId(), committed.intent().attemptId());
        assertThat(messageCount(fixture.scope().sessionId())).isEqualTo(1L);
        verifyNoInteractions(occurrenceArchiveWriter);
    }

    @Test
    void waitingUserWithCorruptControlAfterCrash_failsClosedAndParksRecovery() {
        InteractiveFixture fixture = createWaitingControl();
        SessionInteractiveControlTransactionService.InteractiveIntentAck committed =
                interactiveTransactions.commitWaitingIntent(fixture.command());
        jdbcTemplate.update("""
                UPDATE t_session_message
                SET metadata_json = jsonb_set(
                        metadata_json::jsonb, '{providerOrdinal}', '99'::jsonb)::text
                WHERE id = ?
                """, committed.control().messageId());
        expireSessionLease(fixture.scope().sessionId());

        Throwable failure = catchThrowable(
                () -> recoveryCoordinator.recover(fixture.scope().sessionId(), USER_ID));

        assertThat(failure)
                .isInstanceOf(DurableRecoveryFailureException.class)
                .hasMessage("Durable recovery failed")
                .hasNoCause();
        assertRecoveryParked(fixture.scope().sessionId(), committed.intent().attemptId());
        assertThat(messageCount(fixture.scope().sessionId())).isEqualTo(2L);
        verifyNoInteractions(occurrenceArchiveWriter);
    }

    private Fixture createCommittedIntent(ReplaySafety replaySafety) {
        String sessionId = UUID.randomUUID().toString();
        sessionIdToRemove = sessionId;
        String loopId = UUID.randomUUID().toString();
        long loopFence = 9L;
        String owner = "pre-crash-instance";
        SessionEntity session = new SessionEntity();
        session.setId(sessionId);
        session.setUserId(USER_ID);
        session.setAgentId(1702L);
        session.setStatus("active");
        session.setRuntimeStatus("running");
        session.setRuntimeStep("Executing");
        session.setHistoryEpoch(HISTORY_EPOCH);
        session.setActiveLoopId(loopId);
        session.setLoopFence(loopFence);
        session.setLoopOwnerInstanceId(owner);
        session.setLoopLeaseUntil(Instant.now().plusSeconds(300));
        sessionRepository.saveAndFlush(session);

        LoopDurabilityScope scope = new LoopDurabilityScope(
                sessionId, USER_ID, HISTORY_EPOCH, loopId, loopFence, owner);
        String toolUseId = "recovery-tool-use";
        String toolName = "RecoveryProbe";
        Map<String, Object> input = Map.of("value", "persisted input");
        Message assistant = new Message();
        assistant.setRole(Message.Role.ASSISTANT);
        assistant.setContent(List.of(ContentBlock.toolUse(toolUseId, toolName, input)));
        ToolCallManifest manifest = new ToolCallManifest(
                List.of(new ToolCallIntent(
                        0, toolUseId, toolName, FrozenJson.capture(input), replaySafety)),
                replaySafety);
        IntentCommitAck intentAck = attemptTransactions.commitIntent(new IntentCommitCommand(
                scope,
                UUID.randomUUID(),
                UUID.randomUUID().toString(),
                MessageSnapshot.capture(assistant),
                manifest,
                DurableFrontier.EMPTY,
                "intent-trace"));
        return new Fixture(scope, intentAck, toolUseId);
    }

    private InteractiveFixture createWaitingControl() {
        String sessionId = UUID.randomUUID().toString();
        sessionIdToRemove = sessionId;
        LoopDurabilityScope scope = new LoopDurabilityScope(
                sessionId, USER_ID, HISTORY_EPOCH,
                UUID.randomUUID().toString(), 9L, "pre-crash-interactive-instance");
        SessionEntity session = new SessionEntity();
        session.setId(sessionId);
        session.setUserId(USER_ID);
        session.setAgentId(1702L);
        session.setStatus("active");
        session.setRuntimeStatus("running");
        session.setRuntimeStep("Waiting");
        session.setHistoryEpoch(HISTORY_EPOCH);
        session.setActiveLoopId(scope.loopId());
        session.setLoopFence(scope.loopFence());
        session.setLoopOwnerInstanceId(scope.ownerInstanceId());
        session.setLoopLeaseUntil(Instant.now().plusSeconds(300));
        sessionRepository.saveAndFlush(session);

        ToolCallManifest manifest = new ToolCallManifest(
                List.of(new ToolCallIntent(
                        0,
                        "recovery-ask",
                        "ask_user",
                        FrozenJson.capture(Map.of("question", "Choose")),
                        ReplaySafety.READ_ONLY_REPLAYABLE)),
                ReplaySafety.READ_ONLY_REPLAYABLE);
        InteractiveStepPlanner.InteractiveStepPlan plan = new InteractiveStepPlanner()
                .plan(manifest, ignored -> InteractiveStepPlanner.CallKind.ASK_USER)
                .orElseThrow();
        Message assistant = new Message();
        assistant.setRole(Message.Role.ASSISTANT);
        assistant.setReasoningContent("preserved interactive reasoning");
        assistant.setContent(List.of(ContentBlock.toolUse(
                "recovery-ask", "ask_user", Map.of("question", "Choose"))));
        IntentCommitCommand intent = new IntentCommitCommand(
                scope,
                UUID.randomUUID(),
                UUID.randomUUID().toString(),
                MessageSnapshot.capture(assistant),
                manifest,
                DurableFrontier.EMPTY,
                "interactive-recovery-trace");
        var command = new SessionInteractiveControlTransactionService.InteractiveIntentCommand(
                intent,
                plan,
                UUID.randomUUID().toString(),
                "Choose one option",
                FrozenJson.capture(Map.of(
                        "question", "Choose one option",
                        "options", List.of("option-a", "option-b"))));
        return new InteractiveFixture(scope, command);
    }

    private void assertRecoveryParked(String sessionId, long attemptId) {
        assertThat(jdbcTemplate.queryForMap("""
                SELECT runtime_status, runtime_step, runtime_failure_source,
                       runtime_failure_code, runtime_retryable, runtime_side_effects,
                       active_loop_id, loop_owner_instance_id, loop_lease_until
                FROM t_session WHERE id = ?
                """, sessionId))
                .containsEntry("runtime_status", "error")
                .containsEntry("runtime_step", "recovery_failed")
                .containsEntry("runtime_failure_source", "harness")
                .containsEntry("runtime_failure_code", "DURABLE_RECOVERY_RECONSTRUCTION_FAILED")
                .containsEntry("runtime_retryable", false)
                .containsEntry("runtime_side_effects", "possible")
                .containsEntry("active_loop_id", null)
                .containsEntry("loop_owner_instance_id", null)
                .containsEntry("loop_lease_until", null);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT state, execution_generation, claim_request_id, result_batch_id
                FROM t_session_tool_attempt WHERE id = ?
                """, attemptId))
                .containsEntry("state", "WAITING_USER")
                .containsEntry("execution_generation", 0L)
                .containsEntry("claim_request_id", null)
                .containsEntry("result_batch_id", null);
    }

    private ExecutionClaimAck claimInitialExecution(Fixture fixture) {
        return attemptTransactions.claimExecution(new ExecutionClaimCommand(
                fixture.scope(),
                fixture.intentAck().attemptId(),
                fixture.intentAck().stepId(),
                UUID.randomUUID(),
                DurableToolAttemptState.INTENT_COMMITTED,
                0L));
    }

    private void expireSessionLease(String sessionId) {
        jdbcTemplate.update("""
                UPDATE t_session
                SET loop_lease_until = clock_timestamp() - INTERVAL '1 second'
                WHERE id = ?
                """, sessionId);
    }

    private void expireExecutionAndSessionLeases(String sessionId, long attemptId) {
        jdbcTemplate.update("""
                UPDATE t_session_tool_attempt
                SET execution_lease_until = clock_timestamp() - INTERVAL '1 second'
                WHERE id = ?
                """, attemptId);
        expireSessionLease(sessionId);
    }

    private long messageCount(String sessionId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM t_session_message WHERE session_id = ?",
                Long.class,
                sessionId);
    }

    private Map<String, Object> executionRow(long attemptId) {
        return jdbcTemplate.queryForMap("""
                SELECT state, execution_loop_id, execution_fence,
                       execution_owner_instance_id, execution_generation,
                       claim_request_id, claimed_at, execution_lease_until, updated_at
                FROM t_session_tool_attempt WHERE id = ?
                """, attemptId);
    }

    private static DurableFrontier assistantFrontier(Fixture fixture) {
        return new DurableFrontier(
                fixture.intentAck().assistant().messageId(),
                fixture.intentAck().assistant().seqNo());
    }

    private record Fixture(
            LoopDurabilityScope scope,
            IntentCommitAck intentAck,
            String toolUseId) {
    }

    private record InteractiveFixture(
            LoopDurabilityScope scope,
            SessionInteractiveControlTransactionService.InteractiveIntentCommand command) {
    }
}
