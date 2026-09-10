package com.skillforge.server.session;

import com.skillforge.core.engine.durability.DurableFrontier;
import com.skillforge.core.engine.durability.FrozenJson;
import com.skillforge.core.engine.durability.IntentCommitAck;
import com.skillforge.core.engine.durability.IntentCommitCommand;
import com.skillforge.core.engine.durability.LoopDurabilityScope;
import com.skillforge.core.engine.durability.MessageSnapshot;
import com.skillforge.core.engine.durability.ReplaySafety;
import com.skillforge.core.engine.durability.ToolCallIntent;
import com.skillforge.core.engine.durability.ToolCallManifest;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.core.model.ToolUseBlock;
import com.skillforge.server.AbstractPostgresIT;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.session.persistence.PersistedMessageCodec;
import com.skillforge.server.session.persistence.SessionOrderedMessageWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

/** PostgreSQL hard gates for exact, retryable durable assistant-intent commits. */
@Import({
        JacksonAutoConfiguration.class,
        PersistedMessageCodec.class,
        SessionOrderedMessageWriter.class,
        SessionToolAttemptTransactionService.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SessionToolIntentIdempotencyPostgresIT extends AbstractPostgresIT {

    private static final long USER_ID = 541L;
    private static final long HISTORY_EPOCH = 17L;
    private static final long LOOP_FENCE = 23L;
    private static final String OWNER_INSTANCE_ID = "intent-idempotency-instance";
    private static final String SAFE_SCOPE_FAILURE =
            "Durable intent scope is no longer authoritative";
    private static final String SAFE_INTEGRITY_FAILURE =
            "Durable intent is partial or inconsistent";
    private static final String EXPECTED_MANIFEST_JSON = """
            {"calls":[{"input":{"a":"first","z":9},"providerOrdinal":0,"replaySafety":"READ_ONLY_REPLAYABLE","toolName":"ReadProbe","toolUseId":"toolu-p1-0"},{"input":{"nested":{"a":true,"b":2}},"providerOrdinal":1,"replaySafety":"MUTATING","toolName":"WriteProbe","toolUseId":"toolu-p1-1"}],"replaySafety":"MUTATING","schemaVersion":1}""";

    @Autowired private SessionRepository sessionRepository;
    @Autowired private SessionToolAttemptTransactionService transactionService;
    @Autowired private PersistedMessageCodec messageCodec;
    @Autowired private SessionOrderedMessageWriter messageWriter;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<String> sessionIds = new ArrayList<>();

    @AfterEach
    void removeSessions() {
        for (String sessionId : sessionIds) {
            jdbcTemplate.update("DELETE FROM t_session WHERE id = ?", sessionId);
        }
    }

    @Test
    void p1_commitPersistsExactAssistantManifestOriginFrontierAndAck() {
        Fixture fixture = createSession();
        long priorMessageId = insertPriorMessage(fixture.sessionId());
        DurableFrontier expectedFrontier = new DurableFrontier(priorMessageId, 0L);
        IntentCommitCommand command = command(
                fixture,
                UUID.randomUUID(),
                UUID.randomUUID().toString(),
                assistant("exact assistant text", "exact reasoning"),
                manifest(),
                expectedFrontier,
                UUID.randomUUID().toString());

        IntentCommitAck ack = transactionService.commitIntent(command);

        Map<String, Object> messageRow = jdbcTemplate.queryForMap("""
                SELECT id, session_id, seq_no, role, msg_type, content_json,
                       reasoning_content, metadata_json, message_type, control_id, answered_at,
                       trace_id, write_batch_id, write_batch_ordinal, pruned_at,
                       compacted_by_summary_id, created_at
                FROM t_session_message
                WHERE session_id = ? AND write_batch_id = ?
                """, fixture.sessionId(), command.writeBatchId());
        PersistedMessageCodec.EncodedRow expectedRow = messageCodec.encodeRow(
                new PersistedMessageCodec.PersistedMessage(
                        command.assistant().toMessage(),
                        "NORMAL",
                        "normal",
                        null,
                        null,
                        Map.of(),
                        command.traceId()));
        assertThat(messageRow)
                .containsEntry("session_id", fixture.sessionId())
                .containsEntry("seq_no", 1L)
                .containsEntry("role", expectedRow.role())
                .containsEntry("msg_type", expectedRow.msgType())
                .containsEntry("content_json", expectedRow.contentJson())
                .containsEntry("reasoning_content", expectedRow.reasoningContent())
                .containsEntry("metadata_json", expectedRow.metadataJson())
                .containsEntry("message_type", expectedRow.messageType())
                .containsEntry("trace_id", expectedRow.traceId())
                .containsEntry("write_batch_id", command.writeBatchId())
                .containsEntry("write_batch_ordinal", 0);
        assertThat(messageRow.get("control_id")).isNull();
        assertThat(messageRow.get("answered_at")).isNull();
        assertThat(messageRow.get("pruned_at")).isNull();
        assertThat(messageRow.get("compacted_by_summary_id")).isNull();
        assertThat(messageRow.get("created_at")).isNotNull();

        Map<String, Object> attemptRow = attemptRow(fixture.sessionId(), command.stepId());
        String expectedAssistantHash = sha256(messageCodec.writeMessage(command.assistant().toMessage()));
        String expectedManifestHash = sha256(EXPECTED_MANIFEST_JSON);
        assertThat(attemptRow)
                .containsEntry("session_id", fixture.sessionId())
                .containsEntry("step_id", command.stepId())
                .containsEntry("history_epoch", HISTORY_EPOCH)
                .containsEntry("origin_loop_id", fixture.loopId())
                .containsEntry("origin_fence", LOOP_FENCE)
                .containsEntry("assistant_message_id", messageRow.get("id"))
                .containsEntry("pre_intent_max_message_id", priorMessageId)
                .containsEntry("pre_intent_max_seq", 0L)
                .containsEntry("manifest_json", EXPECTED_MANIFEST_JSON)
                .containsEntry("replay_safety", ReplaySafety.MUTATING.name())
                .containsEntry("state", "INTENT_COMMITTED")
                .containsEntry("execution_generation", 0L)
                .containsEntry("archive_preparation_state", "NOT_STARTED")
                .containsEntry("archive_prepared_count", 0)
                .containsEntry("archive_total_count", 0)
                .containsEntry("post_action_state", "NONE");
        assertThat(((String) attemptRow.get("assistant_payload_hash")).trim())
                .isEqualTo(expectedAssistantHash);
        assertThat(((String) attemptRow.get("manifest_hash")).trim())
                .isEqualTo(expectedManifestHash);
        assertExecutionFieldsAreEmpty(attemptRow);
        assertThat(attemptRow.get("created_at")).isNotNull();
        assertThat(attemptRow.get("updated_at")).isNotNull();

        assertThat(ack.attemptId()).isEqualTo(((Number) attemptRow.get("id")).longValue());
        assertThat(ack.stepId()).isEqualTo(command.stepId());
        assertThat(ack.assistant().messageId())
                .isEqualTo(((Number) messageRow.get("id")).longValue());
        assertThat(ack.assistant().seqNo()).isEqualTo(1L);
        assertThat(ack.assistant().writeBatchId()).isEqualTo(command.writeBatchId());
        assertThat(ack.assistant().writeBatchOrdinal()).isZero();
        assertThat(ack.assistant().message()).isEqualTo(command.assistant());
        assertThat(ack.assistant().msgType()).isEqualTo("NORMAL");
        assertThat(ack.assistant().messageType()).isEqualTo("normal");
        assertThat(ack.assistant().controlId()).isNull();
        assertThat(ack.assistant().answeredAt()).isNull();
        assertThat(ack.assistant().metadata()).isEmpty();
        assertThat(ack.assistant().traceId()).isEqualTo(command.traceId());
        assertThat(ack.preIntentFrontier()).isEqualTo(expectedFrontier);
        assertThat(ack.manifest()).isEqualTo(command.manifest());
        assertThat(ack.assistantPayloadHash()).isEqualTo(expectedAssistantHash);
        assertThat(ack.manifestHash()).isEqualTo(expectedManifestHash);
        assertThat(ack.replaySafety()).isEqualTo(ReplaySafety.MUTATING);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT message_count FROM t_session WHERE id = ?",
                Integer.class,
                fixture.sessionId())).isEqualTo(2);
    }

    @Test
    void p2_sameCommandRetryReturnsSameAckAndDoesNotChangeRowsOrTimestamps() {
        Fixture fixture = createSession();
        long priorMessageId = insertPriorMessage(fixture.sessionId());
        IntentCommitCommand command = command(
                fixture,
                UUID.randomUUID(),
                UUID.randomUUID().toString(),
                assistant("retry assistant", "retry reasoning"),
                manifest(),
                new DurableFrontier(priorMessageId, 0L),
                UUID.randomUUID().toString());

        IntentCommitAck first = transactionService.commitIntent(command);
        Map<String, Object> messageBefore = exactMessageState(
                fixture.sessionId(), command.writeBatchId());
        Map<String, Object> attemptBefore = exactAttemptState(
                fixture.sessionId(), command.stepId());

        IntentCommitAck retry = transactionService.commitIntent(command);

        assertThat(retry).isEqualTo(first);
        assertThat(exactMessageState(fixture.sessionId(), command.writeBatchId()))
                .isEqualTo(messageBefore);
        assertThat(exactAttemptState(fixture.sessionId(), command.stepId()))
                .isEqualTo(attemptBefore);
        assertThat(countMessages(fixture.sessionId(), command.writeBatchId())).isOne();
        assertThat(countAttempts(fixture.sessionId(), command.stepId())).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM t_session_message WHERE session_id = ?
                """, Long.class, fixture.sessionId())).isEqualTo(2L);
    }

    @Test
    void p2_sameCommandRetryReturnsSameAckAfterSessionLeaseExpiry() {
        Fixture fixture = createSession();
        long priorMessageId = insertPriorMessage(fixture.sessionId());
        IntentCommitCommand command = command(
                fixture,
                UUID.randomUUID(),
                UUID.randomUUID().toString(),
                assistant("expired retry assistant", "expired retry reasoning"),
                manifest(),
                new DurableFrontier(priorMessageId, 0L),
                UUID.randomUUID().toString());
        IntentCommitAck first = transactionService.commitIntent(command);
        jdbcTemplate.update("""
                UPDATE t_session
                SET loop_lease_until = clock_timestamp() - interval '1 second'
                WHERE id = ?
                """, fixture.sessionId());

        IntentCommitAck retry = transactionService.commitIntent(command);

        assertThat(retry).isEqualTo(first);
        assertThat(countMessages(fixture.sessionId(), command.writeBatchId())).isOne();
        assertThat(countAttempts(fixture.sessionId(), command.stepId())).isOne();
    }

    @Test
    void staleExpectedFrontierFailsClosedWithoutWritingAssistantOrAttempt() {
        Fixture fixture = createSession();
        long priorMessageId = insertPriorMessage(fixture.sessionId());
        String writeBatchId = UUID.randomUUID().toString();
        UUID stepId = UUID.randomUUID();
        IntentCommitCommand command = command(
                fixture,
                stepId,
                writeBatchId,
                assistant("must not persist", "must not persist reasoning"),
                manifest(),
                DurableFrontier.EMPTY,
                UUID.randomUUID().toString());

        Throwable failure = catchThrowable(() -> transactionService.commitIntent(command));

        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(SAFE_SCOPE_FAILURE)
                .hasNoCause();
        assertThat(failure.toString())
                .doesNotContain("must not persist", writeBatchId, stepId.toString());
        assertThat(countMessages(fixture.sessionId(), writeBatchId)).isZero();
        assertThat(countAttempts(fixture.sessionId(), stepId)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM t_session_message WHERE session_id = ?
                """, Long.class, fixture.sessionId())).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT message_count FROM t_session WHERE id = ?",
                Integer.class,
                fixture.sessionId())).isZero();
        assertThat(priorMessageId).isPositive();
    }

    @Test
    void p3_concurrentIdenticalCommandReturnsEqualAckAndCreatesOneMessageAndAttempt()
            throws Exception {
        Fixture fixture = createSession();
        long priorMessageId = insertPriorMessage(fixture.sessionId());
        IntentCommitCommand command = command(
                fixture,
                UUID.randomUUID(),
                UUID.randomUUID().toString(),
                assistant("concurrent assistant", "concurrent reasoning"),
                manifest(),
                new DurableFrontier(priorMessageId, 0L),
                UUID.randomUUID().toString());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<IntentCommitAck> first = null;
        Future<IntentCommitAck> second = null;
        try {
            first = executor.submit(() -> commitAfterBarrier(command, ready, start));
            second = executor.submit(() -> commitAfterBarrier(command, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            IntentCommitAck firstAck = first.get(20, TimeUnit.SECONDS);
            IntentCommitAck secondAck = second.get(20, TimeUnit.SECONDS);

            assertThat(secondAck).isEqualTo(firstAck);
            assertThat(countMessages(fixture.sessionId(), command.writeBatchId())).isOne();
            assertThat(countAttempts(fixture.sessionId(), command.stepId())).isOne();
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM t_session_message WHERE session_id = ?
                    """, Long.class, fixture.sessionId())).isEqualTo(2L);
        } finally {
            start.countDown();
            if (first != null && !first.isDone()) first.cancel(true);
            if (second != null && !second.isDone()) second.cancel(true);
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest(name = "p4 same IDs with changed {0} fail closed")
    @EnumSource(IntentMismatch.class)
    void p4_sameStepAndBatchWithDifferentBytesFailsWithoutChangingPersistedState(
            IntentMismatch mismatch) {
        Fixture fixture = createSession();
        long priorMessageId = insertPriorMessage(fixture.sessionId());
        DurableFrontier frontier = new DurableFrontier(priorMessageId, 0L);
        UUID stepId = UUID.randomUUID();
        String writeBatchId = UUID.randomUUID().toString();
        String traceId = UUID.randomUUID().toString();
        IntentCommitCommand original = command(
                fixture,
                stepId,
                writeBatchId,
                assistant("mismatch assistant", "mismatch reasoning"),
                manifest(),
                frontier,
                traceId);
        transactionService.commitIntent(original);
        Map<String, Object> messageBefore = exactMessageState(fixture.sessionId(), writeBatchId);
        Map<String, Object> attemptBefore = exactAttemptState(fixture.sessionId(), stepId);
        IntentCommitCommand changed = mismatchedCommand(
                mismatch, fixture, stepId, writeBatchId, frontier, traceId);

        Throwable failure = catchThrowable(() -> transactionService.commitIntent(changed));

        assertIntegrityFailure(failure);
        assertThat(exactMessageState(fixture.sessionId(), writeBatchId)).isEqualTo(messageBefore);
        assertThat(exactAttemptState(fixture.sessionId(), stepId)).isEqualTo(attemptBefore);
        assertThat(countMessages(fixture.sessionId(), writeBatchId)).isOne();
        assertThat(countAttempts(fixture.sessionId(), stepId)).isOne();
    }

    @Test
    void p5_sameBatchWithDifferentStepFailsWithoutCreatingAnotherAttempt() {
        Fixture fixture = createSession();
        long priorMessageId = insertPriorMessage(fixture.sessionId());
        DurableFrontier frontier = new DurableFrontier(priorMessageId, 0L);
        String writeBatchId = UUID.randomUUID().toString();
        IntentCommitCommand original = command(
                fixture,
                UUID.randomUUID(),
                writeBatchId,
                assistant("batch owner", "batch owner reasoning"),
                manifest(),
                frontier,
                UUID.randomUUID().toString());
        transactionService.commitIntent(original);
        Map<String, Object> messageBefore = exactMessageState(fixture.sessionId(), writeBatchId);
        Map<String, Object> attemptBefore = exactAttemptState(
                fixture.sessionId(), original.stepId());
        UUID differentStepId = UUID.randomUUID();
        IntentCommitCommand conflicting = command(
                fixture,
                differentStepId,
                writeBatchId,
                original.assistant().toMessage(),
                original.manifest(),
                frontier,
                original.traceId());

        Throwable failure = catchThrowable(() -> transactionService.commitIntent(conflicting));

        assertIntegrityFailure(failure);
        assertThat(exactMessageState(fixture.sessionId(), writeBatchId)).isEqualTo(messageBefore);
        assertThat(exactAttemptState(fixture.sessionId(), original.stepId())).isEqualTo(attemptBefore);
        assertThat(countAttempts(fixture.sessionId(), differentStepId)).isZero();
        assertThat(countMessages(fixture.sessionId(), writeBatchId)).isOne();
    }

    @Test
    void p5_sameStepWithDifferentBatchFailsWithoutAppendingAnotherAssistant() {
        Fixture fixture = createSession();
        long priorMessageId = insertPriorMessage(fixture.sessionId());
        DurableFrontier frontier = new DurableFrontier(priorMessageId, 0L);
        UUID stepId = UUID.randomUUID();
        IntentCommitCommand original = command(
                fixture,
                stepId,
                UUID.randomUUID().toString(),
                assistant("step owner", "step owner reasoning"),
                manifest(),
                frontier,
                UUID.randomUUID().toString());
        transactionService.commitIntent(original);
        Map<String, Object> messageBefore = exactMessageState(
                fixture.sessionId(), original.writeBatchId());
        Map<String, Object> attemptBefore = exactAttemptState(fixture.sessionId(), stepId);
        String differentBatchId = UUID.randomUUID().toString();
        IntentCommitCommand conflicting = command(
                fixture,
                stepId,
                differentBatchId,
                original.assistant().toMessage(),
                original.manifest(),
                frontier,
                original.traceId());

        Throwable failure = catchThrowable(() -> transactionService.commitIntent(conflicting));

        assertIntegrityFailure(failure);
        assertThat(countMessages(fixture.sessionId(), differentBatchId)).isZero();
        assertThat(exactMessageState(fixture.sessionId(), original.writeBatchId()))
                .isEqualTo(messageBefore);
        assertThat(exactAttemptState(fixture.sessionId(), stepId)).isEqualTo(attemptBefore);
        assertThat(countAttempts(fixture.sessionId(), stepId)).isOne();
    }

    @Test
    void p7_exactAssistantBatchWithoutAttemptIsRejectedAsPartialAndNotAdopted() {
        Fixture fixture = createSession();
        UUID stepId = UUID.randomUUID();
        String writeBatchId = UUID.randomUUID().toString();
        String traceId = UUID.randomUUID().toString();
        Message assistant = assistant("orphan assistant", "orphan reasoning");
        IntentCommitCommand command = command(
                fixture,
                stepId,
                writeBatchId,
                assistant,
                manifest(),
                DurableFrontier.EMPTY,
                traceId);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            sessionRepository.findByIdForUpdate(fixture.sessionId()).orElseThrow();
            messageWriter.appendNewBatchLocked(
                    fixture.sessionId(),
                    writeBatchId,
                    List.of(persistedAssistant(command)));
        });
        Map<String, Object> orphanBefore = exactMessageState(fixture.sessionId(), writeBatchId);

        Throwable failure = catchThrowable(() -> transactionService.commitIntent(command));

        assertIntegrityFailure(failure);
        assertThat(exactMessageState(fixture.sessionId(), writeBatchId)).isEqualTo(orphanBefore);
        assertThat(countMessages(fixture.sessionId(), writeBatchId)).isOne();
        assertThat(countAttempts(fixture.sessionId(), stepId)).isZero();
    }

    @Test
    void p8_attemptWhoseAssistantLostExpectedBatchIdentityIsRejectedWithoutRepair() {
        Fixture fixture = createSession();
        long priorMessageId = insertPriorMessage(fixture.sessionId());
        IntentCommitCommand command = command(
                fixture,
                UUID.randomUUID(),
                UUID.randomUUID().toString(),
                assistant("identity corruption", "identity corruption reasoning"),
                manifest(),
                new DurableFrontier(priorMessageId, 0L),
                UUID.randomUUID().toString());
        IntentCommitAck ack = transactionService.commitIntent(command);
        String displacedBatchId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                UPDATE t_session_message
                SET write_batch_id = ?, write_batch_ordinal = 0
                WHERE id = ? AND session_id = ?
                """, displacedBatchId, ack.assistant().messageId(), fixture.sessionId());
        Map<String, Object> corruptedMessageBefore = exactMessageState(
                fixture.sessionId(), displacedBatchId);
        Map<String, Object> attemptBefore = exactAttemptState(
                fixture.sessionId(), command.stepId());

        Throwable failure = catchThrowable(() -> transactionService.commitIntent(command));

        assertIntegrityFailure(failure);
        assertThat(countMessages(fixture.sessionId(), command.writeBatchId())).isZero();
        assertThat(exactMessageState(fixture.sessionId(), displacedBatchId))
                .isEqualTo(corruptedMessageBefore);
        assertThat(exactAttemptState(fixture.sessionId(), command.stepId()))
                .isEqualTo(attemptBefore);
        assertThat(countAttempts(fixture.sessionId(), command.stepId())).isOne();
    }

    @ParameterizedTest(name = "p9 stale authoritative scope {0} writes nothing")
    @EnumSource(StaleScope.class)
    void p9_staleAuthoritativeScopeFailsClosedWithoutWriting(StaleScope staleScope) {
        Fixture fixture = createSession();
        staleScope.mutateSession(jdbcTemplate, fixture);
        LoopDurabilityScope invalidScope = staleScope.scope(fixture);
        UUID stepId = UUID.randomUUID();
        String writeBatchId = UUID.randomUUID().toString();
        IntentCommitCommand command = command(
                invalidScope,
                stepId,
                writeBatchId,
                assistant("stale scope payload", "stale scope reasoning"),
                manifest(),
                DurableFrontier.EMPTY,
                UUID.randomUUID().toString());

        Throwable failure = catchThrowable(() -> transactionService.commitIntent(command));

        assertScopeFailure(failure);
        assertThat(countMessages(fixture.sessionId(), writeBatchId)).isZero();
        assertThat(countAttempts(fixture.sessionId(), stepId)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM t_session_message WHERE session_id = ?
                """, Long.class, fixture.sessionId())).isZero();
    }

    @Test
    void p9_anotherBlockingAttemptRejectsNewIntentWithoutWritingItsBatchOrStep() {
        Fixture fixture = createSession();
        IntentCommitCommand blocking = command(
                fixture,
                UUID.randomUUID(),
                UUID.randomUUID().toString(),
                assistant("blocking assistant", "blocking reasoning"),
                manifest(),
                DurableFrontier.EMPTY,
                UUID.randomUUID().toString());
        IntentCommitAck blockingAck = transactionService.commitIntent(blocking);
        Map<String, Object> blockingMessageBefore = exactMessageState(
                fixture.sessionId(), blocking.writeBatchId());
        Map<String, Object> blockingAttemptBefore = exactAttemptState(
                fixture.sessionId(), blocking.stepId());
        UUID nextStepId = UUID.randomUUID();
        String nextBatchId = UUID.randomUUID().toString();
        IntentCommitCommand next = command(
                fixture,
                nextStepId,
                nextBatchId,
                assistant("must remain blocked", "must remain blocked reasoning"),
                manifest(),
                new DurableFrontier(blockingAck.assistant().messageId(),
                        blockingAck.assistant().seqNo()),
                UUID.randomUUID().toString());

        Throwable failure = catchThrowable(() -> transactionService.commitIntent(next));

        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Session has a blocking durable Tool attempt")
                .hasNoCause();
        assertThat(countMessages(fixture.sessionId(), nextBatchId)).isZero();
        assertThat(countAttempts(fixture.sessionId(), nextStepId)).isZero();
        assertThat(exactMessageState(fixture.sessionId(), blocking.writeBatchId()))
                .isEqualTo(blockingMessageBefore);
        assertThat(exactAttemptState(fixture.sessionId(), blocking.stepId()))
                .isEqualTo(blockingAttemptBefore);
    }

    @Test
    void retryWithTrailingDurableRowIsRejectedWithoutDeletingOrRewritingAnything() {
        Fixture fixture = createSession();
        IntentCommitCommand command = command(
                fixture,
                UUID.randomUUID(),
                UUID.randomUUID().toString(),
                assistant("retry before trailing row", "trailing row reasoning"),
                manifest(),
                DurableFrontier.EMPTY,
                UUID.randomUUID().toString());
        IntentCommitAck ack = transactionService.commitIntent(command);
        long trailingId = jdbcTemplate.queryForObject("""
                INSERT INTO t_session_message (
                    session_id, seq_no, role, msg_type, content_json, metadata_json, message_type
                ) VALUES (?, ?, 'user', 'NORMAL', '"trailing"', '{}', 'normal')
                RETURNING id
                """, Long.class, fixture.sessionId(), ack.assistant().seqNo() + 1L);
        Map<String, Object> intentBefore = exactMessageState(
                fixture.sessionId(), command.writeBatchId());
        Map<String, Object> attemptBefore = exactAttemptState(
                fixture.sessionId(), command.stepId());

        Throwable failure = catchThrowable(() -> transactionService.commitIntent(command));

        assertIntegrityFailure(failure);
        assertThat(exactMessageState(fixture.sessionId(), command.writeBatchId()))
                .isEqualTo(intentBefore);
        assertThat(exactAttemptState(fixture.sessionId(), command.stepId()))
                .isEqualTo(attemptBefore);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM t_session_message WHERE session_id = ? AND id = ?
                """, Long.class, fixture.sessionId(), trailingId)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM t_session_message WHERE session_id = ?
                """, Long.class, fixture.sessionId())).isEqualTo(2L);
    }

    private IntentCommitAck commitAfterBarrier(
            IntentCommitCommand command, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("concurrent intent start barrier timed out");
        }
        return transactionService.commitIntent(command);
    }

    private IntentCommitCommand mismatchedCommand(
            IntentMismatch mismatch,
            Fixture fixture,
            UUID stepId,
            String writeBatchId,
            DurableFrontier frontier,
            String traceId) {
        Message changedAssistant = assistant("mismatch assistant", "mismatch reasoning");
        ToolCallManifest changedManifest = manifest();
        String changedTraceId = traceId;
        switch (mismatch) {
            case CONTENT -> changedAssistant = assistant(
                    "different assistant content", "mismatch reasoning");
            case REASONING -> changedAssistant = assistant(
                    "mismatch assistant", "different reasoning");
            case TRACE -> changedTraceId = UUID.randomUUID().toString();
            case MANIFEST_INPUT -> {
                Map<String, Object> changedInput = firstInput();
                changedInput.put("a", "changed input");
                changedAssistant = assistantWithCalls(
                        "mismatch assistant",
                        "mismatch reasoning",
                        List.of(
                                new ToolUseBlock("toolu-p1-0", "ReadProbe", changedInput),
                                new ToolUseBlock("toolu-p1-1", "WriteProbe", secondInput())));
                changedManifest = manifestWithCalls(List.of(
                        new ToolCallIntent(
                                0, "toolu-p1-0", "ReadProbe",
                                FrozenJson.capture(changedInput),
                                ReplaySafety.READ_ONLY_REPLAYABLE),
                        new ToolCallIntent(
                                1, "toolu-p1-1", "WriteProbe",
                                FrozenJson.capture(secondInput()),
                                ReplaySafety.MUTATING)));
            }
            case MANIFEST_ORDER -> {
                changedAssistant = assistantWithCalls(
                        "mismatch assistant",
                        "mismatch reasoning",
                        List.of(
                                new ToolUseBlock("toolu-p1-1", "WriteProbe", secondInput()),
                                new ToolUseBlock("toolu-p1-0", "ReadProbe", firstInput())));
                changedManifest = manifestWithCalls(List.of(
                        new ToolCallIntent(
                                0, "toolu-p1-1", "WriteProbe",
                                FrozenJson.capture(secondInput()),
                                ReplaySafety.MUTATING),
                        new ToolCallIntent(
                                1, "toolu-p1-0", "ReadProbe",
                                FrozenJson.capture(firstInput()),
                                ReplaySafety.READ_ONLY_REPLAYABLE)));
            }
            case MANIFEST_NAME -> {
                changedAssistant = assistantWithCalls(
                        "mismatch assistant",
                        "mismatch reasoning",
                        List.of(
                                new ToolUseBlock("toolu-p1-0", "RenamedReadProbe", firstInput()),
                                new ToolUseBlock("toolu-p1-1", "WriteProbe", secondInput())));
                changedManifest = manifestWithCalls(List.of(
                        new ToolCallIntent(
                                0, "toolu-p1-0", "RenamedReadProbe",
                                FrozenJson.capture(firstInput()),
                                ReplaySafety.READ_ONLY_REPLAYABLE),
                        new ToolCallIntent(
                                1, "toolu-p1-1", "WriteProbe",
                                FrozenJson.capture(secondInput()),
                                ReplaySafety.MUTATING)));
            }
            case MANIFEST_SAFETY -> changedManifest = manifestWithCalls(List.of(
                    new ToolCallIntent(
                            0, "toolu-p1-0", "ReadProbe",
                            FrozenJson.capture(firstInput()),
                            ReplaySafety.MUTATING),
                    new ToolCallIntent(
                            1, "toolu-p1-1", "WriteProbe",
                            FrozenJson.capture(secondInput()),
                            ReplaySafety.MUTATING)));
        }
        return command(
                fixture,
                stepId,
                writeBatchId,
                changedAssistant,
                changedManifest,
                frontier,
                changedTraceId);
    }

    private static Message assistantWithCalls(
            String text, String reasoning, List<ToolUseBlock> calls) {
        Message value = new Message();
        value.setRole(Message.Role.ASSISTANT);
        value.setReasoningContent(reasoning);
        List<ContentBlock> blocks = new ArrayList<>();
        blocks.add(ContentBlock.text(text));
        for (ToolUseBlock call : calls) {
            blocks.add(ContentBlock.toolUse(call.getId(), call.getName(), call.getInput()));
        }
        value.setContent(blocks);
        return value;
    }

    private static ToolCallManifest manifestWithCalls(List<ToolCallIntent> calls) {
        return new ToolCallManifest(
                calls,
                ReplaySafety.aggregate(calls.stream()
                        .map(ToolCallIntent::replaySafety)
                        .toList()));
    }

    private static Map<String, Object> firstInput() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("z", 9);
        input.put("a", "first");
        return input;
    }

    private static Map<String, Object> secondInput() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("b", 2);
        nested.put("a", true);
        return Map.of("nested", nested);
    }

    private static PersistedMessageCodec.PersistedMessage persistedAssistant(
            IntentCommitCommand command) {
        return new PersistedMessageCodec.PersistedMessage(
                command.assistant().toMessage(),
                "NORMAL",
                "normal",
                null,
                null,
                Map.of(),
                command.traceId());
    }

    private static void assertIntegrityFailure(Throwable failure) {
        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(SAFE_INTEGRITY_FAILURE)
                .hasNoCause();
    }

    private static void assertScopeFailure(Throwable failure) {
        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(SAFE_SCOPE_FAILURE)
                .hasNoCause();
    }

    private Fixture createSession() {
        String sessionId = UUID.randomUUID().toString();
        String loopId = UUID.randomUUID().toString();
        SessionEntity session = new SessionEntity();
        session.setId(sessionId);
        session.setUserId(USER_ID);
        session.setAgentId(773L);
        session.setStatus("active");
        session.setRuntimeStatus("running");
        session.setHistoryEpoch(HISTORY_EPOCH);
        session.setActiveLoopId(loopId);
        session.setLoopFence(LOOP_FENCE);
        session.setLoopOwnerInstanceId(OWNER_INSTANCE_ID);
        session.setLoopLeaseUntil(Instant.now().plusSeconds(300));
        sessionRepository.saveAndFlush(session);
        sessionIds.add(sessionId);
        return new Fixture(sessionId, loopId);
    }

    private long insertPriorMessage(String sessionId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO t_session_message (
                    session_id, seq_no, role, msg_type, content_json, metadata_json, message_type
                ) VALUES (?, 0, 'user', 'NORMAL', '[{"type":"text","text":"prior"}]',
                          '{}', 'normal')
                RETURNING id
                """, Long.class, sessionId);
    }

    private IntentCommitCommand command(
            Fixture fixture,
            UUID stepId,
            String writeBatchId,
            Message assistant,
            ToolCallManifest manifest,
            DurableFrontier expectedFrontier,
            String traceId) {
        return new IntentCommitCommand(
                fixture.scope(),
                stepId,
                writeBatchId,
                MessageSnapshot.capture(assistant),
                manifest,
                expectedFrontier,
                traceId);
    }

    private IntentCommitCommand command(
            LoopDurabilityScope scope,
            UUID stepId,
            String writeBatchId,
            Message assistant,
            ToolCallManifest manifest,
            DurableFrontier expectedFrontier,
            String traceId) {
        return new IntentCommitCommand(
                scope,
                stepId,
                writeBatchId,
                MessageSnapshot.capture(assistant),
                manifest,
                expectedFrontier,
                traceId);
    }

    private static Message assistant(String text, String reasoning) {
        Map<String, Object> firstInput = new LinkedHashMap<>();
        firstInput.put("z", 9);
        firstInput.put("a", "first");
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("b", 2);
        nested.put("a", true);
        Message assistant = new Message();
        assistant.setRole(Message.Role.ASSISTANT);
        assistant.setReasoningContent(reasoning);
        assistant.setContent(List.of(
                ContentBlock.text(text),
                ContentBlock.toolUse("toolu-p1-0", "ReadProbe", firstInput),
                ContentBlock.toolUse(
                        "toolu-p1-1", "WriteProbe", Map.of("nested", nested))));
        return assistant;
    }

    private static ToolCallManifest manifest() {
        Map<String, Object> firstInput = new LinkedHashMap<>();
        firstInput.put("z", 9);
        firstInput.put("a", "first");
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("b", 2);
        nested.put("a", true);
        return new ToolCallManifest(
                List.of(
                        new ToolCallIntent(
                                0,
                                "toolu-p1-0",
                                "ReadProbe",
                                FrozenJson.capture(firstInput),
                                ReplaySafety.READ_ONLY_REPLAYABLE),
                        new ToolCallIntent(
                                1,
                                "toolu-p1-1",
                                "WriteProbe",
                                FrozenJson.capture(Map.of("nested", nested)),
                                ReplaySafety.MUTATING)),
                ReplaySafety.MUTATING);
    }

    private Map<String, Object> attemptRow(String sessionId, UUID stepId) {
        return jdbcTemplate.queryForMap("""
                SELECT id, session_id, step_id, history_epoch, origin_loop_id, origin_fence,
                       assistant_message_id, assistant_payload_hash,
                       pre_intent_max_message_id, pre_intent_max_seq,
                       manifest_json, manifest_hash, replay_safety, state,
                       execution_loop_id, execution_fence, execution_owner_instance_id,
                       execution_generation, claim_request_id, claimed_at, execution_lease_until,
                       result_batch_id, result_execution_generation, result_execution_fence,
                       archive_preparation_state, archive_prepared_count, archive_total_count,
                       post_action_state, post_action_resolution_request_id,
                       post_action_result_batch_id, post_action_kind, post_action_claim_request_id,
                       post_action_loop_id, post_action_fence, created_at, updated_at
                FROM t_session_tool_attempt
                WHERE session_id = ? AND step_id = ?
                """, sessionId, stepId);
    }

    private Map<String, Object> exactMessageState(String sessionId, String writeBatchId) {
        return jdbcTemplate.queryForMap("""
                SELECT id, session_id, seq_no, role, msg_type, content_json, metadata_json,
                       message_type, control_id, reasoning_content, trace_id, write_batch_id,
                       write_batch_ordinal, pruned_at, compacted_by_summary_id, answered_at,
                       created_at
                FROM t_session_message
                WHERE session_id = ? AND write_batch_id = ?
                """, sessionId, writeBatchId);
    }

    private Map<String, Object> exactAttemptState(String sessionId, UUID stepId) {
        return attemptRow(sessionId, stepId);
    }

    private long countMessages(String sessionId, String writeBatchId) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM t_session_message
                WHERE session_id = ? AND write_batch_id = ?
                """, Long.class, sessionId, writeBatchId);
    }

    private long countAttempts(String sessionId, UUID stepId) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM t_session_tool_attempt
                WHERE session_id = ? AND step_id = ?
                """, Long.class, sessionId, stepId);
    }

    private static void assertExecutionFieldsAreEmpty(Map<String, Object> attemptRow) {
        assertThat(attemptRow.get("execution_loop_id")).isNull();
        assertThat(attemptRow.get("execution_fence")).isNull();
        assertThat(attemptRow.get("execution_owner_instance_id")).isNull();
        assertThat(attemptRow.get("execution_generation")).isEqualTo(0L);
        assertThat(attemptRow.get("claim_request_id")).isNull();
        assertThat(attemptRow.get("claimed_at")).isNull();
        assertThat(attemptRow.get("execution_lease_until")).isNull();
        assertThat(attemptRow.get("result_batch_id")).isNull();
        assertThat(attemptRow.get("result_execution_generation")).isNull();
        assertThat(attemptRow.get("result_execution_fence")).isNull();
        assertThat(attemptRow.get("post_action_resolution_request_id")).isNull();
        assertThat(attemptRow.get("post_action_result_batch_id")).isNull();
        assertThat(attemptRow.get("post_action_kind")).isNull();
        assertThat(attemptRow.get("post_action_claim_request_id")).isNull();
        assertThat(attemptRow.get("post_action_loop_id")).isNull();
        assertThat(attemptRow.get("post_action_fence")).isNull();
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private enum IntentMismatch {
        CONTENT,
        REASONING,
        TRACE,
        MANIFEST_INPUT,
        MANIFEST_ORDER,
        MANIFEST_NAME,
        MANIFEST_SAFETY
    }

    private enum StaleScope {
        USER,
        EPOCH,
        LOOP,
        FENCE,
        OWNER,
        EXPIRED_LEASE,
        RESTORE_PREPARING;

        LoopDurabilityScope scope(Fixture fixture) {
            return switch (this) {
                case USER -> new LoopDurabilityScope(
                        fixture.sessionId(), USER_ID + 1L, HISTORY_EPOCH,
                        fixture.loopId(), LOOP_FENCE, OWNER_INSTANCE_ID);
                case EPOCH -> new LoopDurabilityScope(
                        fixture.sessionId(), USER_ID, HISTORY_EPOCH + 1L,
                        fixture.loopId(), LOOP_FENCE, OWNER_INSTANCE_ID);
                case LOOP -> new LoopDurabilityScope(
                        fixture.sessionId(), USER_ID, HISTORY_EPOCH,
                        UUID.randomUUID().toString(), LOOP_FENCE, OWNER_INSTANCE_ID);
                case FENCE -> new LoopDurabilityScope(
                        fixture.sessionId(), USER_ID, HISTORY_EPOCH,
                        fixture.loopId(), LOOP_FENCE + 1L, OWNER_INSTANCE_ID);
                case OWNER -> new LoopDurabilityScope(
                        fixture.sessionId(), USER_ID, HISTORY_EPOCH,
                        fixture.loopId(), LOOP_FENCE, "different-instance");
                case EXPIRED_LEASE, RESTORE_PREPARING -> fixture.scope();
            };
        }

        void mutateSession(JdbcTemplate jdbcTemplate, Fixture fixture) {
            switch (this) {
                case EXPIRED_LEASE -> jdbcTemplate.update("""
                        UPDATE t_session
                        SET loop_lease_until = clock_timestamp() - interval '1 second'
                        WHERE id = ?
                        """, fixture.sessionId());
                case RESTORE_PREPARING -> jdbcTemplate.update("""
                        UPDATE t_session SET restore_preparing = TRUE WHERE id = ?
                        """, fixture.sessionId());
                default -> {
                }
            }
        }
    }

    private record Fixture(String sessionId, String loopId) {
        LoopDurabilityScope scope() {
            return new LoopDurabilityScope(
                    sessionId,
                    USER_ID,
                    HISTORY_EPOCH,
                    loopId,
                    LOOP_FENCE,
                    OWNER_INSTANCE_ID);
        }
    }
}
