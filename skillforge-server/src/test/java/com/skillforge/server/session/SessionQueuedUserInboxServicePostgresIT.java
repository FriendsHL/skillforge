package com.skillforge.server.session;

import com.skillforge.core.engine.durability.LoopDurabilityScope;
import com.skillforge.core.engine.durability.MessageSnapshot;
import com.skillforge.core.engine.durability.DurableFrontier;
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

/** PostgreSQL gates for ordered queued USER acceptance and crash-safe drain. */
@Import({
        JacksonAutoConfiguration.class,
        PersistedMessageCodec.class,
        SessionOrderedMessageWriter.class,
        SessionQueuedUserInboxService.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SessionQueuedUserInboxServicePostgresIT extends AbstractPostgresIT {

    private static final long USER_ID = 1301L;
    private static final String DELETE_TRIGGER = "test_fail_inbox_delete";
    private static final String DELETE_FUNCTION = "test_fail_inbox_delete_fn";

    @Autowired private SessionRepository sessionRepository;
    @Autowired private SessionQueuedUserInboxService inboxService;
    @Autowired private PersistedMessageCodec messageCodec;
    @Autowired private JdbcTemplate jdbcTemplate;

    private String sessionIdToRemove;

    @AfterEach
    void cleanFixture() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + DELETE_TRIGGER
                + " ON t_session_message_inbox");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + DELETE_FUNCTION + "()");
        if (sessionIdToRemove != null) {
            jdbcTemplate.update("DELETE FROM t_session WHERE id = ?", sessionIdToRemove);
        }
    }

    @Test
    void acceptAndDrain_multipleMessages_preservesDatabaseOrderAndExactJson() {
        Fixture fixture = createActiveSession(false);
        QueuedInput first = input("line 1\n\"quoted\" 𠮷");
        QueuedInput second = input("second");
        QueuedInput third = input("third");

        SessionQueuedUserInboxService.AcceptanceAck firstAck = accept(fixture, first);
        SessionQueuedUserInboxService.AcceptanceAck secondAck = accept(fixture, second);
        SessionQueuedUserInboxService.AcceptanceAck thirdAck = accept(fixture, third);

        assertThat(List.of(firstAck.mode(), secondAck.mode(), thirdAck.mode()))
                .containsOnly(SessionQueuedUserInboxService.AcceptanceMode.QUEUED_LIVE);
        assertThat(List.of(firstAck.inboxRowId(), secondAck.inboxRowId(), thirdAck.inboxRowId()))
                .isSorted()
                .doesNotHaveDuplicates();
        assertThat(inboxRows(fixture.sessionId()))
                .extracting(row -> row.get("message_json"))
                .containsExactly(
                        exactJson(first), exactJson(second), exactJson(third));
        assertThat(messageRows(fixture.sessionId())).isEmpty();

        SessionQueuedUserInboxService.DrainAck drained = inboxService.drain(
                fixture.scope(), DurableFrontier.EMPTY);

        assertThat(drained.items())
                .extracting(SessionQueuedUserInboxService.DrainedItem::inboxId)
                .containsExactly(first.inboxId(), second.inboxId(), third.inboxId());
        assertThat(drained.items())
                .extracting(item -> item.message().message().toMessage().getContent())
                .containsExactly(first.text(), second.text(), third.text());
        assertThat(messageRows(fixture.sessionId()))
                .extracting(
                        row -> row.get("content_json"),
                        row -> row.get("write_batch_id"),
                        row -> row.get("write_batch_ordinal"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                jsonString(first.text()), first.inboxId().toString(), 0),
                        org.assertj.core.groups.Tuple.tuple(
                                jsonString(second.text()), second.inboxId().toString(), 0),
                        org.assertj.core.groups.Tuple.tuple(
                                jsonString(third.text()), third.inboxId().toString(), 0));
        assertThat(inboxRows(fixture.sessionId())).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT message_count FROM t_session WHERE id = ?",
                Integer.class, fixture.sessionId())).isEqualTo(3);

        List<Map<String, Object>> afterFirstDrain = messageRows(fixture.sessionId());
        SessionQueuedUserInboxService.DrainAck ackLossRetry =
                inboxService.drain(fixture.scope(), DurableFrontier.EMPTY);
        SessionQueuedUserInboxService.AcceptanceAck lateAcceptanceRetry =
                accept(fixture, second);

        assertThat(ackLossRetry).isEqualTo(drained);
        assertThat(lateAcceptanceRetry.mode())
                .isEqualTo(SessionQueuedUserInboxService.AcceptanceMode.ALREADY_DRAINED);
        assertThat(messageRows(fixture.sessionId())).isEqualTo(afterFirstDrain);
        assertThat(inboxRows(fixture.sessionId())).isEmpty();
    }

    @Test
    void accept_idleRoutesToAdmission_expiredLeaseQueuesBeforeRecoveryHint() {
        Fixture idle = createIdleSession();
        QueuedInput idleInput = input("idle input");

        SessionQueuedUserInboxService.AcceptanceAck idleAck = accept(idle, idleInput);

        assertThat(idleAck.mode())
                .isEqualTo(SessionQueuedUserInboxService.AcceptanceMode.IDLE_ADMISSION_REQUIRED);
        assertThat(idleAck.inboxRowId()).isNull();
        assertThat(inboxRows(idle.sessionId())).isEmpty();
        assertThat(messageRows(idle.sessionId())).isEmpty();

        deleteSession(idle.sessionId());
        Fixture expired = createActiveSession(true);
        QueuedInput expiredInput = input("recover after accepting me");

        SessionQueuedUserInboxService.AcceptanceAck expiredAck =
                accept(expired, expiredInput);

        assertThat(expiredAck.mode())
                .isEqualTo(SessionQueuedUserInboxService.AcceptanceMode.QUEUED_RECOVERY_REQUIRED);
        assertThat(expiredAck.inboxRowId()).isPositive();
        assertThat(inboxRows(expired.sessionId())).hasSize(1);
        assertThat(messageRows(expired.sessionId())).isEmpty();
    }

    @Test
    void drain_openOrDanglingToolIntent_rejectsWithoutSplittingIntentResultPair() {
        Fixture fixture = createActiveSession(false);
        insertOpenIntent(fixture);
        QueuedInput queued = input("must stay behind the tool pair");
        accept(fixture, queued);
        List<Map<String, Object>> before = messageRows(fixture.sessionId());

        DurableFrontier intentFrontier = currentFrontier(fixture.sessionId());
        Throwable failure = catchThrowable(() -> inboxService.drain(
                fixture.scope(), intentFrontier));

        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Queued USER drain failed")
                .hasNoCause();
        assertThat(messageRows(fixture.sessionId())).isEqualTo(before);
        assertThat(inboxRows(fixture.sessionId())).hasSize(1);

        jdbcTemplate.update(
                "DELETE FROM t_session_tool_attempt WHERE session_id = ?",
                fixture.sessionId());
        Throwable danglingIntentFailure =
                catchThrowable(() -> inboxService.drain(fixture.scope(), intentFrontier));

        assertThat(danglingIntentFailure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Queued USER drain failed")
                .hasNoCause();
        assertThat(messageRows(fixture.sessionId())).isEqualTo(before);
        assertThat(inboxRows(fixture.sessionId())).hasSize(1);
    }

    @Test
    void drain_deleteFailure_rollsBackAllAppends_thenRetryWritesEachInputOnce() {
        Fixture fixture = createActiveSession(false);
        QueuedInput first = input("first before crash");
        QueuedInput second = input("second before crash");
        accept(fixture, first);
        accept(fixture, second);
        installFailingDeleteTrigger();

        Throwable failure = catchThrowable(() -> inboxService.drain(
                fixture.scope(), DurableFrontier.EMPTY));

        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Queued USER drain failed")
                .hasNoCause();
        assertThat(messageRows(fixture.sessionId())).isEmpty();
        assertThat(inboxRows(fixture.sessionId())).hasSize(2);

        dropFailingDeleteTrigger();
        SessionQueuedUserInboxService.DrainAck retry = inboxService.drain(
                fixture.scope(), DurableFrontier.EMPTY);
        List<Map<String, Object>> afterRetry = messageRows(fixture.sessionId());
        SessionQueuedUserInboxService.DrainAck ackLossRetry = inboxService.drain(
                fixture.scope(), DurableFrontier.EMPTY);

        assertThat(retry.items()).hasSize(2);
        assertThat(afterRetry).hasSize(2);
        assertThat(ackLossRetry).isEqualTo(retry);
        assertThat(messageRows(fixture.sessionId())).isEqualTo(afterRetry);
        assertThat(inboxRows(fixture.sessionId())).isEmpty();
    }

    private SessionQueuedUserInboxService.AcceptanceAck accept(
            Fixture fixture,
            QueuedInput input) {
        return inboxService.acceptOrRoute(
                fixture.sessionId(), USER_ID, input.inboxId(), input.message());
    }

    private String exactJson(QueuedInput input) {
        return messageCodec.writeMessage(input.message().toMessage());
    }

    private String jsonString(String value) {
        return messageCodec.encodeRow(new PersistedMessageCodec.PersistedMessage(
                Message.user(value), "NORMAL", "normal", null, null,
                Map.of(), null)).contentJson();
    }

    private QueuedInput input(String text) {
        return new QueuedInput(
                UUID.randomUUID(), MessageSnapshot.capture(Message.user(text)), text);
    }

    private Fixture createActiveSession(boolean expired) {
        String sessionId = UUID.randomUUID().toString();
        String loopId = UUID.randomUUID().toString();
        String owner = "inbox-postgres-instance";
        long epoch = 7L;
        long fence = 9L;
        SessionEntity session = baseSession(sessionId);
        session.setRuntimeStatus("running");
        session.setHistoryEpoch(epoch);
        session.setActiveLoopId(loopId);
        session.setLoopFence(fence);
        session.setLoopOwnerInstanceId(owner);
        session.setLoopLeaseUntil(Instant.now().plusSeconds(expired ? -10 : 300));
        sessionRepository.saveAndFlush(session);
        return new Fixture(
                sessionId,
                new LoopDurabilityScope(sessionId, USER_ID, epoch, loopId, fence, owner));
    }

    private Fixture createIdleSession() {
        String sessionId = UUID.randomUUID().toString();
        SessionEntity session = baseSession(sessionId);
        session.setRuntimeStatus("idle");
        session.setHistoryEpoch(7L);
        sessionRepository.saveAndFlush(session);
        return new Fixture(sessionId, null);
    }

    private SessionEntity baseSession(String sessionId) {
        sessionIdToRemove = sessionId;
        SessionEntity session = new SessionEntity();
        session.setId(sessionId);
        session.setUserId(USER_ID);
        session.setAgentId(1302L);
        session.setStatus("active");
        return session;
    }

    private void insertOpenIntent(Fixture fixture) {
        UUID intentBatchId = UUID.randomUUID();
        Long assistantMessageId = jdbcTemplate.queryForObject("""
                INSERT INTO t_session_message
                    (session_id, seq_no, role, msg_type, message_type,
                     content_json, metadata_json, write_batch_id, write_batch_ordinal)
                VALUES (?, 0, 'assistant', 'NORMAL', 'normal',
                        '[{"type":"tool_use","id":"tool-1","name":"ReadOnly","input":{}}]',
                        '{}', ?, 0)
                RETURNING id
                """, Long.class, fixture.sessionId(), intentBatchId.toString());
        jdbcTemplate.update("""
                INSERT INTO t_session_tool_attempt
                    (session_id, step_id, history_epoch, origin_loop_id, origin_fence,
                     assistant_message_id, assistant_payload_hash,
                     pre_intent_max_message_id, pre_intent_max_seq,
                     manifest_json, manifest_hash, replay_safety, state)
                VALUES (?, ?, 7, ?, 9, ?, ?, -1, -1, '[]', ?,
                        'READ_ONLY_REPLAYABLE', 'INTENT_COMMITTED')
                """,
                fixture.sessionId(), UUID.randomUUID(), fixture.scope().loopId(),
                assistantMessageId, "0".repeat(64), "1".repeat(64));
    }

    private List<Map<String, Object>> inboxRows(String sessionId) {
        return jdbcTemplate.queryForList("""
                SELECT id, inbox_id, user_id, message_json
                FROM t_session_message_inbox
                WHERE session_id = ?
                ORDER BY id
                """, sessionId);
    }

    private List<Map<String, Object>> messageRows(String sessionId) {
        return jdbcTemplate.queryForList("""
                SELECT id, seq_no, role, content_json, metadata_json,
                       write_batch_id, write_batch_ordinal
                FROM t_session_message
                WHERE session_id = ?
                ORDER BY seq_no
                """, sessionId);
    }

    private DurableFrontier currentFrontier(String sessionId) {
        List<Map<String, Object>> rows = messageRows(sessionId);
        if (rows.isEmpty()) return DurableFrontier.EMPTY;
        Map<String, Object> tail = rows.get(rows.size() - 1);
        return new DurableFrontier(
                ((Number) tail.get("id")).longValue(),
                ((Number) tail.get("seq_no")).longValue());
    }

    private void installFailingDeleteTrigger() {
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION test_fail_inbox_delete_fn()
                RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'simulated crash before inbox delete commit';
                END;
                $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER test_fail_inbox_delete
                BEFORE DELETE ON t_session_message_inbox
                FOR EACH STATEMENT EXECUTE FUNCTION test_fail_inbox_delete_fn()
                """);
    }

    private void dropFailingDeleteTrigger() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + DELETE_TRIGGER
                + " ON t_session_message_inbox");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + DELETE_FUNCTION + "()");
    }

    private void deleteSession(String sessionId) {
        jdbcTemplate.update("DELETE FROM t_session WHERE id = ?", sessionId);
        if (sessionId.equals(sessionIdToRemove)) sessionIdToRemove = null;
    }

    private record Fixture(String sessionId, LoopDurabilityScope scope) {
    }

    private record QueuedInput(UUID inboxId, MessageSnapshot message, String text) {
    }
}
