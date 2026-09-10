package com.skillforge.server.session;

import com.skillforge.core.engine.durability.DurableFrontier;
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
import com.skillforge.core.engine.durability.ToolResultCommitCommand;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.server.AbstractPostgresIT;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.runtime.RuntimeFailureFact;
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

/** PostgreSQL hard gates for INTENT_COMMITTED -> EXECUTING claim atomicity. */
@Import({
        JacksonAutoConfiguration.class,
        PersistedMessageCodec.class,
        SessionOrderedMessageWriter.class,
        SessionLoopAdmissionService.class,
        SessionToolAttemptTransactionService.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SessionToolExecutionClaimPostgresIT extends AbstractPostgresIT {

    private static final String TRIGGER_FAILURE = "forced_execution_claim_update_failure";

    @Autowired private SessionRepository sessionRepository;
    @Autowired private SessionToolAttemptTransactionService transactionService;
    @Autowired private SessionLoopAdmissionService admissionService;
    @Autowired private JdbcTemplate jdbcTemplate;

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
    void initialClaim_setsCompleteExecutionTupleFromLockedDatabaseScope() {
        Fixture fixture = createCommittedIntent();
        UUID claimRequestId = UUID.randomUUID();
        ExecutionClaimCommand command = new ExecutionClaimCommand(
                fixture.scope(),
                fixture.intentAck().attemptId(),
                fixture.intentAck().stepId(),
                claimRequestId,
                DurableToolAttemptState.INTENT_COMMITTED,
                0L);

        ExecutionClaimAck ack = transactionService.claimExecution(command);

        assertThat(ack.attemptId()).isEqualTo(command.attemptId());
        assertThat(ack.stepId()).isEqualTo(command.stepId());
        assertThat(ack.claimRequestId()).isEqualTo(claimRequestId);
        assertThat(ack.state()).isEqualTo(DurableToolAttemptState.EXECUTING);
        assertThat(ack.executionScope()).isEqualTo(fixture.scope());
        assertThat(ack.executionGeneration()).isEqualTo(1L);
        assertThat(ack.claimedAt()).isNotNull();
        assertThat(ack.executionLeaseUntil()).isEqualTo(fixture.leaseUntil());
        assertThat(ack.executionLeaseUntil()).isAfter(ack.claimedAt());

        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT state, history_epoch, origin_loop_id, origin_fence,
                       execution_loop_id, execution_fence, execution_owner_instance_id,
                       execution_generation, claim_request_id, claimed_at,
                       execution_lease_until, result_batch_id,
                       result_execution_generation, result_execution_fence
                FROM t_session_tool_attempt
                WHERE id = ?
                """, command.attemptId());
        assertThat(row)
                .containsEntry("state", "EXECUTING")
                .containsEntry("history_epoch", fixture.scope().historyEpoch())
                .containsEntry("origin_loop_id", fixture.scope().loopId())
                .containsEntry("origin_fence", fixture.scope().loopFence())
                .containsEntry("execution_loop_id", fixture.scope().loopId())
                .containsEntry("execution_fence", fixture.scope().loopFence())
                .containsEntry("execution_owner_instance_id", fixture.scope().ownerInstanceId())
                .containsEntry("execution_generation", 1L)
                .containsEntry("claim_request_id", claimRequestId)
                .containsEntry("result_batch_id", null)
                .containsEntry("result_execution_generation", null)
                .containsEntry("result_execution_fence", null);
        assertThat(((java.sql.Timestamp) row.get("claimed_at")).toInstant())
                .isEqualTo(ack.claimedAt());
        assertThat(((java.sql.Timestamp) row.get("execution_lease_until")).toInstant())
                .isEqualTo(ack.executionLeaseUntil());
    }

    @Test
    void userCancellationParksUnknownOutcome_andRejectsLateResultCommit() {
        Fixture fixture = createCommittedIntent(ReplaySafety.READ_ONLY_REPLAYABLE);
        ExecutionClaimAck execution = transactionService.claimExecution(
                new ExecutionClaimCommand(
                        fixture.scope(), fixture.intentAck().attemptId(),
                        fixture.intentAck().stepId(), UUID.randomUUID(),
                        DurableToolAttemptState.INTENT_COMMITTED, 0L));

        admissionService.parkCancelledExecution(
                fixture.scope(), execution,
                new RuntimeFailureFact(
                        "user_action", "CANCELLED_TOOL_OUTCOME_UNCERTAIN", false,
                        "possible", "Cancellation occurred during Tool execution."));

        Throwable lateCommit = catchThrowable(() -> transactionService.commitResults(
                new ToolResultCommitCommand(
                        fixture.scope(), fixture.intentAck().attemptId(),
                        fixture.intentAck().stepId(), execution.claimRequestId(),
                        execution.executionGeneration(), UUID.randomUUID(),
                        List.of(MessageSnapshot.capture(Message.toolResult(
                                "claim-tool-use", "late", false))),
                        new DurableFrontier(
                                fixture.intentAck().assistant().messageId(),
                                fixture.intentAck().assistant().seqNo()),
                        fixture.intentAck().assistantPayloadHash(),
                        fixture.intentAck().manifestHash(), "late-trace")));

        assertThat(lateCommit).isInstanceOf(IllegalStateException.class);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT state FROM t_session_tool_attempt WHERE id = ?
                """, fixture.intentAck().attemptId()))
                .containsEntry("state", "UNCERTAIN_PENDING_RESOLUTION");
        assertThat(jdbcTemplate.queryForMap("""
                SELECT runtime_status, runtime_step, active_loop_id,
                       loop_owner_instance_id, loop_lease_until
                FROM t_session WHERE id = ?
                """, fixture.scope().sessionId()))
                .containsEntry("runtime_status", "error")
                .containsEntry("runtime_step", "cancelled_tool_outcome_uncertain")
                .containsEntry("active_loop_id", null)
                .containsEntry("loop_owner_instance_id", null)
                .containsEntry("loop_lease_until", null);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM t_session_message WHERE session_id = ?",
                Long.class, fixture.scope().sessionId())).isEqualTo(1L);
    }

    @Test
    void sameClaimRequestAfterAckLoss_returnsSameAckWithoutAdvancingGeneration() {
        Fixture fixture = createCommittedIntent();
        ExecutionClaimCommand command = new ExecutionClaimCommand(
                fixture.scope(),
                fixture.intentAck().attemptId(),
                fixture.intentAck().stepId(),
                UUID.randomUUID(),
                DurableToolAttemptState.INTENT_COMMITTED,
                0L);

        ExecutionClaimAck first = transactionService.claimExecution(command);
        Map<String, Object> firstRow = jdbcTemplate.queryForMap("""
                SELECT state, execution_generation, claim_request_id, claimed_at,
                       execution_lease_until, updated_at
                FROM t_session_tool_attempt WHERE id = ?
                """, command.attemptId());
        ExecutionClaimAck retry = transactionService.claimExecution(command);
        Map<String, Object> retryRow = jdbcTemplate.queryForMap("""
                SELECT state, execution_generation, claim_request_id, claimed_at,
                       execution_lease_until, updated_at
                FROM t_session_tool_attempt WHERE id = ?
                """, command.attemptId());

        assertThat(retry).isEqualTo(first);
        assertThat(retryRow).isEqualTo(firstRow);
        assertThat(retryRow)
                .containsEntry("state", "EXECUTING")
                .containsEntry("execution_generation", 1L)
                .containsEntry("claim_request_id", command.claimRequestId());
    }

    @Test
    void sameClaimRequestAfterAckLoss_afterLeaseExpiryCannotAuthorizeLateDispatch() {
        Fixture fixture = createCommittedIntent();
        ExecutionClaimCommand command = new ExecutionClaimCommand(
                fixture.scope(),
                fixture.intentAck().attemptId(),
                fixture.intentAck().stepId(),
                UUID.randomUUID(),
                DurableToolAttemptState.INTENT_COMMITTED,
                0L);
        ExecutionClaimAck first = transactionService.claimExecution(command);
        jdbcTemplate.update(
                "UPDATE t_session SET loop_lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second' "
                        + "WHERE id = ?",
                fixture.scope().sessionId());

        Throwable failure = catchThrowable(() -> transactionService.claimExecution(command));

        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Durable execution claim scope is no longer authoritative")
                .hasNoCause();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT execution_generation FROM t_session_tool_attempt WHERE id = ?",
                Long.class,
                command.attemptId())).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT claim_request_id FROM t_session_tool_attempt WHERE id = ?",
                UUID.class,
                command.attemptId())).isEqualTo(first.claimRequestId());
    }

    @Test
    void differentOwnerBeforeAuthoritativeLeaseExpiry_isRejectedWithoutMutation() {
        Fixture fixture = createCommittedIntent(ReplaySafety.READ_ONLY_REPLAYABLE);
        LoopDurabilityScope differentOwner = new LoopDurabilityScope(
                fixture.scope().sessionId(),
                fixture.scope().userId(),
                fixture.scope().historyEpoch(),
                UUID.randomUUID().toString(),
                fixture.scope().loopFence() + 1L,
                "different-owner-before-expiry");
        ExecutionClaimCommand command = new ExecutionClaimCommand(
                differentOwner,
                fixture.intentAck().attemptId(),
                fixture.intentAck().stepId(),
                UUID.randomUUID(),
                DurableToolAttemptState.INTENT_COMMITTED,
                0L);

        Throwable failure = catchThrowable(() -> transactionService.claimExecution(command));

        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Durable execution claim scope is no longer authoritative")
                .hasNoCause();
        assertIntentCommittedShape(command.attemptId());
    }

    @Test
    void safeIntentAfterSessionLeaseTakeover_advancesToNewOwnerGenerationOne() {
        Fixture fixture = createCommittedIntent(ReplaySafety.READ_ONLY_REPLAYABLE);
        LoopDurabilityScope takeover = installAuthoritativeSessionTakeover(fixture, false);
        ExecutionClaimCommand command = new ExecutionClaimCommand(
                takeover,
                fixture.intentAck().attemptId(),
                fixture.intentAck().stepId(),
                UUID.randomUUID(),
                DurableToolAttemptState.INTENT_COMMITTED,
                0L);

        ExecutionClaimAck ack = transactionService.claimExecution(command);

        assertThat(ack.executionScope()).isEqualTo(takeover);
        assertThat(ack.executionGeneration()).isEqualTo(1L);
        assertThat(ack.state()).isEqualTo(DurableToolAttemptState.EXECUTING);
        assertExecutionShape(command.attemptId(), takeover, 1L, command.claimRequestId());
    }

    @Test
    void safeExecutingAttemptAfterLeaseExpiry_advancesOwnerAndGeneration() {
        Fixture fixture = createCommittedIntent(ReplaySafety.READ_ONLY_REPLAYABLE);
        ExecutionClaimCommand initial = new ExecutionClaimCommand(
                fixture.scope(),
                fixture.intentAck().attemptId(),
                fixture.intentAck().stepId(),
                UUID.randomUUID(),
                DurableToolAttemptState.INTENT_COMMITTED,
                0L);
        transactionService.claimExecution(initial);
        LoopDurabilityScope takeover = installAuthoritativeSessionTakeover(fixture, true);
        ExecutionClaimCommand command = new ExecutionClaimCommand(
                takeover,
                fixture.intentAck().attemptId(),
                fixture.intentAck().stepId(),
                UUID.randomUUID(),
                DurableToolAttemptState.EXECUTING,
                1L);

        ExecutionClaimAck ack = transactionService.claimExecution(command);

        assertThat(ack.executionScope()).isEqualTo(takeover);
        assertThat(ack.executionGeneration()).isEqualTo(2L);
        assertExecutionShape(command.attemptId(), takeover, 2L, command.claimRequestId());
    }

    @Test
    void unsafeExecutingAttemptAfterLeaseExpiry_isFencedIntoUncertaintyAndRetryIsStable() {
        Fixture fixture = createCommittedIntent(ReplaySafety.MUTATING);
        ExecutionClaimCommand initial = new ExecutionClaimCommand(
                fixture.scope(),
                fixture.intentAck().attemptId(),
                fixture.intentAck().stepId(),
                UUID.randomUUID(),
                DurableToolAttemptState.INTENT_COMMITTED,
                0L);
        transactionService.claimExecution(initial);
        LoopDurabilityScope takeover = installAuthoritativeSessionTakeover(fixture, true);
        ExecutionClaimCommand command = new ExecutionClaimCommand(
                takeover,
                fixture.intentAck().attemptId(),
                fixture.intentAck().stepId(),
                UUID.randomUUID(),
                DurableToolAttemptState.EXECUTING,
                1L);

        ExecutionClaimAck first = transactionService.claimExecution(command);
        Map<String, Object> firstRow = jdbcTemplate.queryForMap("""
                SELECT state, execution_loop_id, execution_fence,
                       execution_owner_instance_id, execution_generation, claim_request_id,
                       claimed_at, execution_lease_until, updated_at
                FROM t_session_tool_attempt WHERE id = ?
                """, command.attemptId());
        ExecutionClaimAck retry = transactionService.claimExecution(command);

        assertThat(first.state())
                .isEqualTo(DurableToolAttemptState.UNCERTAIN_PENDING_RESOLUTION);
        assertThat(first.executionScope()).isEqualTo(takeover);
        assertThat(first.executionGeneration()).isEqualTo(2L);
        assertThat(retry).isEqualTo(first);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT state, execution_loop_id, execution_fence,
                       execution_owner_instance_id, execution_generation, claim_request_id,
                       claimed_at, execution_lease_until, updated_at
                FROM t_session_tool_attempt WHERE id = ?
                """, command.attemptId())).isEqualTo(firstRow);
        assertThat(firstRow)
                .containsEntry("state", "UNCERTAIN_PENDING_RESOLUTION")
                .containsEntry("execution_loop_id", takeover.loopId())
                .containsEntry("execution_fence", takeover.loopFence())
                .containsEntry("execution_owner_instance_id", takeover.ownerInstanceId())
                .containsEntry("execution_generation", 2L)
                .containsEntry("claim_request_id", command.claimRequestId());

        Throwable staleOwnerFailure = catchThrowable(
                () -> transactionService.claimExecution(initial));
        assertThat(staleOwnerFailure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Durable execution claim scope is no longer authoritative")
                .hasNoCause();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT state FROM t_session_tool_attempt WHERE id = ?",
                String.class,
                command.attemptId())).isEqualTo("UNCERTAIN_PENDING_RESOLUTION");
    }

    @Test
    void deferredClaimUpdateFailure_rollsBackAndReturnsPayloadFreeFailure() {
        Fixture fixture = createCommittedIntent();
        UUID claimRequestId = UUID.randomUUID();
        ExecutionClaimCommand command = new ExecutionClaimCommand(
                fixture.scope(),
                fixture.intentAck().attemptId(),
                fixture.intentAck().stepId(),
                claimRequestId,
                DurableToolAttemptState.INTENT_COMMITTED,
                0L);
        installDeferredClaimFailureTrigger(fixture.scope().sessionId(), command.stepId());

        Throwable failure = catchThrowable(() -> transactionService.claimExecution(command));

        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Durable execution claim persistence failed")
                .hasNoCause();
        assertThat(failure.toString()).doesNotContain(
                fixture.scope().sessionId(), command.stepId().toString(),
                claimRequestId.toString(), "claim-payload-secret", TRIGGER_FAILURE);
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT state, execution_loop_id, execution_fence,
                       execution_owner_instance_id, execution_generation,
                       claim_request_id, claimed_at, execution_lease_until
                FROM t_session_tool_attempt
                WHERE id = ?
                """, command.attemptId());
        assertThat(row)
                .containsEntry("state", "INTENT_COMMITTED")
                .containsEntry("execution_loop_id", null)
                .containsEntry("execution_fence", null)
                .containsEntry("execution_owner_instance_id", null)
                .containsEntry("execution_generation", 0L)
                .containsEntry("claim_request_id", null)
                .containsEntry("claimed_at", null)
                .containsEntry("execution_lease_until", null);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM t_session_message
                WHERE session_id = ? AND write_batch_id IS NOT NULL
                """, Long.class, fixture.scope().sessionId())).isEqualTo(1L);
    }

    private Fixture createCommittedIntent() {
        return createCommittedIntent(ReplaySafety.MUTATING);
    }

    private Fixture createCommittedIntent(ReplaySafety replaySafety) {
        String sessionId = UUID.randomUUID().toString();
        sessionIdToRemove = sessionId;
        long userId = 901L;
        long historyEpoch = 3L;
        String loopId = UUID.randomUUID().toString();
        long loopFence = 7L;
        String ownerInstanceId = "claim-postgres-instance";
        Instant leaseUntil = Instant.now().plusSeconds(300);
        SessionEntity session = new SessionEntity();
        session.setId(sessionId);
        session.setUserId(userId);
        session.setAgentId(902L);
        session.setStatus("active");
        session.setRuntimeStatus("running");
        session.setHistoryEpoch(historyEpoch);
        session.setActiveLoopId(loopId);
        session.setLoopFence(loopFence);
        session.setLoopOwnerInstanceId(ownerInstanceId);
        session.setLoopLeaseUntil(leaseUntil);
        sessionRepository.saveAndFlush(session);
        leaseUntil = sessionRepository.findById(sessionId).orElseThrow().getLoopLeaseUntil();

        LoopDurabilityScope scope = new LoopDurabilityScope(
                sessionId, userId, historyEpoch, loopId, loopFence, ownerInstanceId);
        String toolUseId = "claim-tool-use";
        String toolName = "ClaimWriteProbe";
        Map<String, Object> input = Map.of(
                "value", "claim-payload-secret",
                "nested", Map.of("enabled", true));
        Message assistant = new Message();
        assistant.setRole(Message.Role.ASSISTANT);
        assistant.setContent(List.of(ContentBlock.toolUse(toolUseId, toolName, input)));
        ToolCallManifest manifest = new ToolCallManifest(
                List.of(new ToolCallIntent(
                        0, toolUseId, toolName,
                        FrozenJson.capture(input), replaySafety)),
                replaySafety);
        IntentCommitCommand intent = new IntentCommitCommand(
                scope,
                UUID.randomUUID(),
                UUID.randomUUID().toString(),
                MessageSnapshot.capture(assistant),
                manifest,
                DurableFrontier.EMPTY,
                UUID.randomUUID().toString());
        IntentCommitAck ack = transactionService.commitIntent(intent);
        return new Fixture(scope, leaseUntil, ack);
    }

    private LoopDurabilityScope installAuthoritativeSessionTakeover(
            Fixture fixture, boolean expireAttemptLease) {
        String loopId = UUID.randomUUID().toString();
        long loopFence = fixture.scope().loopFence() + 1L;
        String owner = "takeover-owner";
        if (expireAttemptLease) {
            jdbcTemplate.update("""
                    UPDATE t_session_tool_attempt
                    SET execution_lease_until = clock_timestamp() - INTERVAL '1 second'
                    WHERE id = ?
                    """, fixture.intentAck().attemptId());
        }
        jdbcTemplate.update("""
                UPDATE t_session
                SET loop_lease_until = clock_timestamp() - INTERVAL '1 second'
                WHERE id = ?
                """, fixture.scope().sessionId());
        jdbcTemplate.update("""
                UPDATE t_session
                SET active_loop_id = ?, loop_fence = ?, loop_owner_instance_id = ?,
                    loop_lease_until = clock_timestamp() + INTERVAL '5 minutes'
                WHERE id = ?
                """, loopId, loopFence, owner, fixture.scope().sessionId());
        return new LoopDurabilityScope(
                fixture.scope().sessionId(),
                fixture.scope().userId(),
                fixture.scope().historyEpoch(),
                loopId,
                loopFence,
                owner);
    }

    private void assertIntentCommittedShape(long attemptId) {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT state, execution_loop_id, execution_fence,
                       execution_owner_instance_id, execution_generation,
                       claim_request_id, claimed_at, execution_lease_until
                FROM t_session_tool_attempt WHERE id = ?
                """, attemptId);
        assertThat(row)
                .containsEntry("state", "INTENT_COMMITTED")
                .containsEntry("execution_loop_id", null)
                .containsEntry("execution_fence", null)
                .containsEntry("execution_owner_instance_id", null)
                .containsEntry("execution_generation", 0L)
                .containsEntry("claim_request_id", null)
                .containsEntry("claimed_at", null)
                .containsEntry("execution_lease_until", null);
    }

    private void assertExecutionShape(
            long attemptId,
            LoopDurabilityScope scope,
            long generation,
            UUID claimRequestId) {
        assertThat(jdbcTemplate.queryForMap("""
                SELECT state, execution_loop_id, execution_fence,
                       execution_owner_instance_id, execution_generation, claim_request_id
                FROM t_session_tool_attempt WHERE id = ?
                """, attemptId))
                .containsEntry("state", "EXECUTING")
                .containsEntry("execution_loop_id", scope.loopId())
                .containsEntry("execution_fence", scope.loopFence())
                .containsEntry("execution_owner_instance_id", scope.ownerInstanceId())
                .containsEntry("execution_generation", generation)
                .containsEntry("claim_request_id", claimRequestId);
    }

    private void installDeferredClaimFailureTrigger(String sessionId, UUID stepId) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        functionName = "fail_execution_claim_deferred_" + suffix;
        triggerName = "fail_execution_claim_deferred_" + suffix;
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
                      AND OLD.state = 'INTENT_COMMITTED' AND NEW.state = 'EXECUTING')
                EXECUTE FUNCTION %s()
                """.formatted(triggerName, sessionId, stepId, functionName));
    }

    private record Fixture(
            LoopDurabilityScope scope,
            Instant leaseUntil,
            IntentCommitAck intentAck) {
    }
}
