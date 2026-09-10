package com.skillforge.server.session;

import com.skillforge.server.SharedPostgresContainer;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SessionMessageRepository;
import com.skillforge.server.repository.SessionToolAttemptRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.skillforge.server.session.SessionRunCoordinator.ClaimDisposition.ACCEPTED;
import static com.skillforge.server.session.SessionRunCoordinator.ClaimDisposition.REJECTED;
import static com.skillforge.server.session.SessionRunCoordinator.HandoffDisposition.HANDOFF_ACCEPTED;
import static com.skillforge.server.session.SessionRunCoordinator.HandoffDisposition.HANDOFF_REJECTED;
import static com.skillforge.server.session.SessionRunCoordinator.HandoffDisposition.HANDOFF_REPLAYED;
import static com.skillforge.server.session.SessionRunCoordinator.RecoveryDisposition.RECOVERY_AUTHORIZED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PostgreSQL implementation of the frozen W-R6-1 single-continuation oracle. */
@DataJpaTest
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaSessionRunCoordinator.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JpaSessionRunCoordinatorPostgresIT extends SessionRunCoordinatorContract {

    private static final String SESSION_ID = "session-1";
    private static final long ATTEMPT_ID = 41L;
    private static final long HISTORY_EPOCH = 3L;
    private static final long PREVIOUS_LOOP_FENCE = 6L;
    private static final UUID RESOLUTION_REQUEST_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID RESULT_BATCH_ID =
            UUID.fromString("20000000-0000-4000-8000-000000000001");

    @Autowired private JpaSessionRunCoordinator coordinator;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private SessionMessageRepository messageRepository;
    @Autowired private SessionToolAttemptRepository attemptRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    private boolean fixtureCreated;

    @DynamicPropertySource
    static void registerPostgresProperties(DynamicPropertyRegistry registry) {
        PostgreSQLContainer<?> postgres = SharedPostgresContainer.getInstance();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
    }

    @Override
    protected SessionRunCoordinator newCoordinator() {
        createPendingFixture();
        return coordinator;
    }

    @Override
    protected void persistTranscriptEvidence(SessionRunCoordinator.ClaimIdentity claim) {
        appendTranscriptEvidence();
    }

    @AfterEach
    void removeFixture() {
        if (fixtureCreated) {
            jdbcTemplate.update("DELETE FROM t_session WHERE id = ?", SESSION_ID);
            fixtureCreated = false;
        }
    }

    @Test
    void competingClaims_areSerializedAndLoserReceivesDurableWinnerAck() throws Exception {
        createPendingFixture();
        SessionRunCoordinator.ClaimCommand left = command(
                UUID.fromString("00000000-0000-4000-8000-000000000011"), "loop-left", 7L);
        SessionRunCoordinator.ClaimCommand right = command(
                UUID.fromString("00000000-0000-4000-8000-000000000012"), "loop-right", 7L);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<SessionRunCoordinator.ClaimResult> first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return coordinator.claim(left);
            });
            Future<SessionRunCoordinator.ClaimResult> second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return coordinator.claim(right);
            });
            ready.await();
            start.countDown();
            List<SessionRunCoordinator.ClaimResult> results = List.of(first.get(), second.get());

            assertThat(results).extracting(SessionRunCoordinator.ClaimResult::disposition)
                    .containsExactlyInAnyOrder(ACCEPTED, REJECTED);
            SessionRunCoordinator.ClaimResult winner = results.stream()
                    .filter(result -> result.disposition() == ACCEPTED)
                    .findFirst().orElseThrow();
            SessionRunCoordinator.ClaimResult loser = results.stream()
                    .filter(result -> result.disposition() == REJECTED)
                    .findFirst().orElseThrow();
            assertThat(loser.ack()).isEqualTo(winner.ack());
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM t_session_tool_attempt
                    WHERE id = ? AND post_action_state = 'CLAIMED'
                    """, Long.class, ATTEMPT_ID)).isOne();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void handoffMarkers_surviveCoordinatorRestartAndEnforceArchiveThenInboxThenTranscript() {
        createPendingFixture();
        SessionRunCoordinator.ClaimIdentity winner = coordinator.claim(command(
                UUID.fromString("00000000-0000-4000-8000-000000000021"),
                "loop-handoff", 7L)).ack().winner();
        jdbcTemplate.update("""
                UPDATE t_session_tool_attempt
                SET archive_preparation_state = 'PENDING'
                WHERE id = ?
                """, ATTEMPT_ID);

        assertThat(coordinator.acceptInboxDrain(winner).disposition())
                .isEqualTo(HANDOFF_REJECTED);
        jdbcTemplate.update("""
                UPDATE t_session_tool_attempt
                SET archive_preparation_state = 'RAW_FALLBACK'
                WHERE id = ?
                """, ATTEMPT_ID);
        assertThat(coordinator.acceptTranscriptContinuation(winner).disposition())
                .isEqualTo(HANDOFF_REJECTED);
        assertThat(coordinator.acceptInboxDrain(winner).disposition())
                .isEqualTo(HANDOFF_ACCEPTED);

        assertThat(coordinator.acceptTranscriptContinuation(winner).disposition())
                .isEqualTo(HANDOFF_REJECTED);
        appendTranscriptEvidence();

        JpaSessionRunCoordinator restarted = new JpaSessionRunCoordinator(
                sessionRepository, messageRepository, attemptRepository, transactionManager,
                coordinator.ownerInstanceIdForTest(), Duration.ofMinutes(2));
        assertThat(restarted.acceptInboxDrain(winner).disposition())
                .isEqualTo(SessionRunCoordinator.HandoffDisposition.HANDOFF_REPLAYED);
        assertThat(restarted.acceptTranscriptContinuation(winner).disposition())
                .isEqualTo(HANDOFF_ACCEPTED);
        assertThat(restarted.acceptTranscriptContinuation(winner).disposition())
                .isEqualTo(SessionRunCoordinator.HandoffDisposition.HANDOFF_REPLAYED);

        assertThat(jdbcTemplate.queryForMap("""
                SELECT post_action_state, post_action_inbox_handoff_accepted
                FROM t_session_tool_attempt WHERE id = ?
                """, ATTEMPT_ID))
                .containsEntry("post_action_state", "COMPLETED")
                .containsEntry("post_action_inbox_handoff_accepted", true);
    }

    @Test
    void transcriptEvidence_isFoundAfterMoreThanOnePageOfQueuedUserMessages() {
        createPendingFixture();
        SessionRunCoordinator.ClaimIdentity winner = coordinator.claim(command(
                UUID.fromString("00000000-0000-4000-8000-000000000022"),
                "loop-large-inbox", 7L)).ack().winner();
        assertThat(coordinator.acceptInboxDrain(winner).disposition())
                .isEqualTo(HANDOFF_ACCEPTED);
        jdbcTemplate.update("""
                INSERT INTO t_session_message (
                    session_id, seq_no, role, msg_type, content_json, metadata_json,
                    message_type, created_at)
                SELECT ?, generated_seq, 'user', 'NORMAL', '"queued"', '{}',
                       'normal', clock_timestamp()
                FROM generate_series(2, 515) AS generated_seq
                """, SESSION_ID);
        jdbcTemplate.update("""
                INSERT INTO t_session_message (
                    session_id, seq_no, role, msg_type, content_json, metadata_json,
                    message_type, created_at)
                VALUES (?, 516, 'assistant', 'NORMAL', '"continued"', '{}',
                        'normal', clock_timestamp())
                """, SESSION_ID);

        assertThat(coordinator.acceptTranscriptContinuation(winner).disposition())
                .isEqualTo(HANDOFF_ACCEPTED);
    }

    @Test
    void expiredOwner_canBeReplacedOnlyForThePersistedWinningIdentity() {
        createPendingFixture();
        SessionRunCoordinator.ClaimIdentity winner = coordinator.claim(command(
                UUID.fromString("00000000-0000-4000-8000-000000000031"),
                "loop-recovery", 7L)).ack().winner();
        jdbcTemplate.update("""
                UPDATE t_session
                SET loop_owner_instance_id = 'dead-instance',
                    loop_lease_until = clock_timestamp() - INTERVAL '1 second'
                WHERE id = ?
                """, SESSION_ID);
        JpaSessionRunCoordinator recovery = new JpaSessionRunCoordinator(
                sessionRepository, messageRepository, attemptRepository, transactionManager,
                "recovery-instance", Duration.ofMinutes(2));

        SessionRunCoordinator.ClaimIdentity stale = new SessionRunCoordinator.ClaimIdentity(
                winner.sessionId(), winner.attemptId(), winner.resolutionRequestId(),
                winner.resultBatchId(), winner.action(), winner.claimRequestId(),
                winner.loopId(), winner.loopFence() - 1L);
        assertThat(recovery.authorizeCrashRecovery(stale).disposition())
                .isEqualTo(SessionRunCoordinator.RecoveryDisposition.RECOVERY_REJECTED);
        assertThat(recovery.authorizeCrashRecovery(winner).disposition())
                .isEqualTo(RECOVERY_AUTHORIZED);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT active_loop_id, loop_fence, loop_owner_instance_id
                FROM t_session WHERE id = ?
                """, SESSION_ID))
                .containsEntry("active_loop_id", winner.loopId())
                .containsEntry("loop_fence", winner.loopFence())
                .containsEntry("loop_owner_instance_id", "recovery-instance");
    }

    @Test
    void staleHistoryEpochOrUnreservedFence_cannotClaimPendingContinuation() {
        createPendingFixture();
        jdbcTemplate.update(
                "UPDATE t_session SET history_epoch = history_epoch + 1 WHERE id = ?",
                SESSION_ID);
        assertThatThrownBySafe(() -> coordinator.claim(command(
                UUID.randomUUID(), "loop-stale-epoch", 7L)));

        jdbcTemplate.update(
                "UPDATE t_session SET history_epoch = ?, loop_fence = ? WHERE id = ?",
                HISTORY_EPOCH, PREVIOUS_LOOP_FENCE, SESSION_ID);
        assertThatThrownBySafe(() -> coordinator.claim(command(
                UUID.randomUUID(), "loop-skipped-fence", 8L)));
        assertThat(jdbcTemplate.queryForMap("""
                SELECT active_loop_id, loop_fence FROM t_session WHERE id = ?
                """, SESSION_ID))
                .containsEntry("active_loop_id", null)
                .containsEntry("loop_fence", PREVIOUS_LOOP_FENCE);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT post_action_state FROM t_session_tool_attempt WHERE id = ?
                """, String.class, ATTEMPT_ID)).isEqualTo("PENDING");
    }

    @Test
    void concurrentHandoffRetries_acceptEachLogicalStageExactlyOnce() throws Exception {
        createPendingFixture();
        SessionRunCoordinator.ClaimIdentity winner = coordinator.claim(command(
                UUID.fromString("00000000-0000-4000-8000-000000000041"),
                "loop-racing-handoff", 7L)).ack().winner();

        assertThat(race(() -> coordinator.acceptInboxDrain(winner)))
                .extracting(SessionRunCoordinator.HandoffResult::disposition)
                .containsExactlyInAnyOrder(HANDOFF_ACCEPTED, HANDOFF_REPLAYED);
        appendTranscriptEvidence();
        assertThat(race(() -> coordinator.acceptTranscriptContinuation(winner)))
                .extracting(SessionRunCoordinator.HandoffResult::disposition)
                .containsExactlyInAnyOrder(HANDOFF_ACCEPTED, HANDOFF_REPLAYED);
    }

    @Test
    void databaseConstraint_rejectsSkippedHandoffStages() {
        createPendingFixture();
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE t_session_tool_attempt
                SET post_action_inbox_handoff_accepted = TRUE
                WHERE id = ?
                """, ATTEMPT_ID)).isInstanceOf(Exception.class);

        SessionRunCoordinator.ClaimIdentity winner = coordinator.claim(command(
                UUID.fromString("00000000-0000-4000-8000-000000000051"),
                "loop-db-constraint", 7L)).ack().winner();
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE t_session_tool_attempt
                SET post_action_state = 'COMPLETED'
                WHERE id = ?
                """, ATTEMPT_ID)).isInstanceOf(Exception.class);
        assertThat(coordinator.acceptInboxDrain(winner).disposition())
                .isEqualTo(HANDOFF_ACCEPTED);
        appendTranscriptEvidence();
        assertThat(coordinator.acceptTranscriptContinuation(winner).disposition())
                .isEqualTo(HANDOFF_ACCEPTED);
    }

    private void assertThatThrownBySafe(Runnable action) {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(action::run))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Durable continuation is not available")
                .hasNoCause();
    }

    private static <T> List<T> race(Callable<T> action) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<T> contestant = () -> {
            ready.countDown();
            start.await();
            return action.call();
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<T> first = executor.submit(contestant);
            Future<T> second = executor.submit(contestant);
            ready.await();
            start.countDown();
            return List.of(first.get(), second.get());
        } finally {
            executor.shutdownNow();
        }
    }

    private void createPendingFixture() {
        if (fixtureCreated) return;
        jdbcTemplate.update("DELETE FROM t_session WHERE id = ?", SESSION_ID);
        jdbcTemplate.update("""
                INSERT INTO t_session (
                    id, user_id, agent_id, status, message_count, total_input_tokens,
                    total_output_tokens, history_epoch, loop_fence, restore_preparing,
                    runtime_status, runtime_retryable, recovery_attempts, recovery_state,
                    execution_mode, smart_titled, depth, light_context, light_compact_count,
                    full_compact_count, last_compacted_at_message_count,
                    total_tokens_reclaimed, last_extracted_message_seq, origin,
                    created_at, updated_at)
                VALUES (?, 7001, 7002, 'active', 1, 0, 0, ?, ?, FALSE,
                        'idle', FALSE, 0, 'none', 'ask', FALSE, 0, FALSE, 0, 0, 0,
                        0, 0, 'production', clock_timestamp(), clock_timestamp())
                """, SESSION_ID, HISTORY_EPOCH, PREVIOUS_LOOP_FENCE);
        Long assistantMessageId = jdbcTemplate.queryForObject("""
                INSERT INTO t_session_message (
                    session_id, seq_no, role, msg_type, content_json, metadata_json,
                    message_type, created_at)
                VALUES (?, 0, 'assistant', 'NORMAL', '"fixture"', '{}',
                        'normal', clock_timestamp())
                RETURNING id
                """, Long.class, SESSION_ID);
        jdbcTemplate.update("""
                INSERT INTO t_session_tool_attempt (
                    id, session_id, step_id, history_epoch, origin_loop_id, origin_fence,
                    assistant_message_id, assistant_payload_hash, pre_intent_max_message_id,
                    pre_intent_max_seq, manifest_json, manifest_hash, replay_safety, state,
                    execution_loop_id, execution_fence, execution_owner_instance_id,
                    execution_generation, claim_request_id, claimed_at, execution_lease_until,
                    result_batch_id, result_execution_generation, result_execution_fence,
                    archive_preparation_state, archive_prepared_count, archive_total_count,
                    post_action_state, post_action_resolution_request_id,
                    post_action_result_batch_id, post_action_kind, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'origin-loop', 6, ?, repeat('a', 64), -1, -1,
                        '[]', repeat('b', 64), 'MUTATING', 'RESOLVED_UNKNOWN',
                        'origin-loop', 6, 'dead-executor', 1, ?, clock_timestamp(),
                        clock_timestamp() + INTERVAL '1 minute', ?, 1, 6,
                        'RAW_FALLBACK', 0, 1, 'PENDING', ?, ?,
                        'CONTINUE_CURRENT_TIMELINE', clock_timestamp(), clock_timestamp())
                """, ATTEMPT_ID, SESSION_ID, UUID.randomUUID(), HISTORY_EPOCH,
                assistantMessageId, UUID.randomUUID(), RESULT_BATCH_ID,
                RESOLUTION_REQUEST_ID, RESULT_BATCH_ID);
        jdbcTemplate.update("""
                INSERT INTO t_session_message (
                    session_id, seq_no, role, msg_type, content_json, metadata_json,
                    message_type, write_batch_id, write_batch_ordinal, created_at)
                VALUES (?, 1, 'user', 'NORMAL',
                        '[{"type":"tool_result","tool_use_id":"tool-1","content":"unknown","is_error":true,"error_type":"OUTCOME_UNKNOWN"}]',
                        '{}', 'normal', ?, 0, clock_timestamp())
                """, SESSION_ID, RESULT_BATCH_ID.toString());
        fixtureCreated = true;
    }

    private void appendTranscriptEvidence() {
        Integer existing = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM t_session_message
                WHERE session_id = ? AND seq_no = 2
                """, Integer.class, SESSION_ID);
        if (existing != null && existing > 0) return;
        jdbcTemplate.update("""
                INSERT INTO t_session_message (
                    session_id, seq_no, role, msg_type, content_json, metadata_json,
                    message_type, write_batch_id, write_batch_ordinal, created_at)
                VALUES (?, 2, 'assistant', 'NORMAL', '"continued"', '{}',
                        'normal', ?, 0, clock_timestamp())
                """, SESSION_ID, UUID.randomUUID().toString());
    }

    private static SessionRunCoordinator.ClaimCommand command(
            UUID claimRequestId, String loopId, long loopFence) {
        return new SessionRunCoordinator.ClaimCommand(
                SESSION_ID, ATTEMPT_ID, RESOLUTION_REQUEST_ID, RESULT_BATCH_ID,
                SessionRunCoordinator.ContinuationAction.CONTINUE_CURRENT_TIMELINE,
                claimRequestId, loopId, loopFence);
    }
}
