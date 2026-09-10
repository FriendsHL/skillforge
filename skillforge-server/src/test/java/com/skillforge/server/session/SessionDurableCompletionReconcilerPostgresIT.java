package com.skillforge.server.session;

import com.skillforge.core.engine.durability.DurableFrontier;
import com.skillforge.core.engine.durability.LoopDurabilityScope;
import com.skillforge.core.engine.durability.MessageSnapshot;
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

/** PostgreSQL gates for append-only final reconciliation after durable Tool ACKs. */
@Import({
        JacksonAutoConfiguration.class,
        PersistedMessageCodec.class,
        SessionOrderedMessageWriter.class,
        SessionDurableCompletionReconciler.class,
        SessionLoopAdmissionService.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SessionDurableCompletionReconcilerPostgresIT extends AbstractPostgresIT {

    @Autowired private SessionRepository sessionRepository;
    @Autowired private SessionDurableCompletionReconciler reconciler;
    @Autowired private SessionLoopAdmissionService admissionService;
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
    void transientCompletionWriteFailureKeepsScopeRecoverable() {
        Fixture fixture = createFixture();
        installTransientInsertFailure();

        Throwable failure = catchThrowable(() -> reconciler.reconcile(
                fixture.scope(), fixture.frontier(), UUID.randomUUID(),
                MessageSnapshot.capture(Message.assistant("not acknowledged")),
                "completion-trace"));

        assertThat(failure)
                .isInstanceOf(DurableRecoveryRetryableException.class)
                .hasNoCause();
        assertThat(rows(fixture.scope().sessionId())).isEqualTo(fixture.originalRows());
        assertThat(jdbcTemplate.queryForMap("""
                SELECT active_loop_id, loop_fence, loop_owner_instance_id,
                       loop_lease_until, runtime_status
                FROM t_session WHERE id = ?
                """, fixture.scope().sessionId()))
                .containsEntry("active_loop_id", fixture.scope().loopId())
                .containsEntry("loop_fence", fixture.scope().loopFence())
                .containsEntry("loop_owner_instance_id", fixture.scope().ownerInstanceId())
                .containsEntry("runtime_status", "running");

        jdbcTemplate.execute("DROP TRIGGER " + triggerName + " ON t_session_message");
        triggerName = null;
        jdbcTemplate.execute("DROP FUNCTION " + functionName + "()");
        functionName = null;
        jdbcTemplate.update("""
                UPDATE t_session
                SET loop_lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE id = ?
                """, fixture.scope().sessionId());

        SessionLoopAdmissionService.RecoveryAdmissionAck recovered =
                admissionService.claimRecovery(
                        fixture.scope().sessionId(), fixture.scope().userId(),
                        UUID.randomUUID().toString());

        assertThat(recovered.scope().loopFence()).isEqualTo(fixture.scope().loopFence() + 1L);
        assertThat(recovered.attemptId()).isNull();
    }

    @Test
    void acknowledgedRowsRemainUntouched_andOnePlainTerminalAssistantIsAppendedIdempotently() {
        Fixture fixture = createFixture();
        Message terminal = Message.assistant("terminal answer");
        UUID completionBatchId = UUID.randomUUID();

        SessionDurableCompletionReconciler.CompletionAck first = reconciler.reconcile(
                fixture.scope(), fixture.frontier(), completionBatchId,
                MessageSnapshot.capture(terminal), "completion-trace", 17L, 5L);
        List<Map<String, Object>> rowsAfterFirst = rows(fixture.scope().sessionId());
        SessionDurableCompletionReconciler.CompletionAck retry = reconciler.reconcile(
                fixture.scope(), fixture.frontier(), completionBatchId,
                MessageSnapshot.capture(terminal), "completion-trace", 17L, 5L);

        assertThat(first).isEqualTo(retry);
        assertThat(first.terminalAssistant()).isNotNull();
        assertThat(first.preCompletionFrontier()).isEqualTo(fixture.frontier());
        assertThat(first.postCompletionFrontier()).isEqualTo(new DurableFrontier(
                first.terminalAssistant().messageId(), fixture.frontier().maxSeq() + 1));
        assertThat(rows(fixture.scope().sessionId())).isEqualTo(rowsAfterFirst);
        assertThat(rowsAfterFirst).hasSize(3);
        assertThat(rowsAfterFirst.get(0))
                .containsEntry("id", fixture.originalRows().get(0).get("id"))
                .containsEntry("content_json", fixture.originalRows().get(0).get("content_json"));
        assertThat(rowsAfterFirst.get(1))
                .containsEntry("id", fixture.originalRows().get(1).get("id"))
                .containsEntry("write_batch_id", fixture.originalRows().get(1).get("write_batch_id"));
        assertThat(rowsAfterFirst.get(2))
                .containsEntry("seq_no", 2L)
                .containsEntry("role", "assistant")
                .containsEntry("write_batch_id", completionBatchId.toString())
                .containsEntry("write_batch_ordinal", 0);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT history_epoch FROM t_session WHERE id = ?", Long.class,
                fixture.scope().sessionId())).isEqualTo(fixture.scope().historyEpoch());
        assertThat(jdbcTemplate.queryForMap("""
                SELECT active_loop_id, loop_fence, loop_owner_instance_id,
                       loop_lease_until, runtime_status
                FROM t_session WHERE id = ?
                """, fixture.scope().sessionId()))
                .containsEntry("active_loop_id", null)
                .containsEntry("loop_fence", fixture.scope().loopFence())
                .containsEntry("loop_owner_instance_id", null)
                .containsEntry("loop_lease_until", null)
                .containsEntry("runtime_status", "idle");
        assertThat(jdbcTemplate.queryForMap("""
                SELECT total_input_tokens, total_output_tokens
                FROM t_session WHERE id = ?
                """, fixture.scope().sessionId()))
                .containsEntry("total_input_tokens", 17L)
                .containsEntry("total_output_tokens", 5L);
    }

    @Test
    void inputAcceptedDuringTerminalProviderCall_keepsExactFenceForContinuation() {
        Fixture fixture = createFixture();
        UUID inboxId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO t_session_message_inbox
                    (inbox_id, session_id, user_id, message_json)
                VALUES (?, ?, ?, ?)
                """,
                inboxId, fixture.scope().sessionId(), fixture.scope().userId(),
                "{\"role\":\"user\",\"content\":\"late queued input\"}");

        SessionDurableCompletionReconciler.CompletionAck acknowledgement =
                reconciler.reconcile(
                        fixture.scope(),
                        fixture.frontier(),
                        UUID.randomUUID(),
                        MessageSnapshot.capture(Message.assistant("terminal before late input")),
                        "completion-trace");

        assertThat(acknowledgement.continuationRequired()).isTrue();
        assertThat(acknowledgement.continuationLeaseUntil()).isNotNull();
        assertThat(rows(fixture.scope().sessionId()).get(2))
                .containsEntry("role", "assistant")
                .containsEntry("seq_no", 2L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_session_message_inbox WHERE session_id = ?",
                Long.class, fixture.scope().sessionId())).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT active_loop_id, loop_fence, loop_owner_instance_id,
                       loop_lease_until, runtime_status, runtime_step
                FROM t_session WHERE id = ?
                """, fixture.scope().sessionId()))
                .containsEntry("active_loop_id", fixture.scope().loopId())
                .containsEntry("loop_fence", fixture.scope().loopFence())
                .containsEntry("loop_owner_instance_id", fixture.scope().ownerInstanceId())
                .containsEntry("runtime_status", "running")
                .containsEntry("runtime_step", "Queued input");
    }

    @Test
    void staleAcknowledgedFrontierFailsClosed_withoutAppendingOrRewritingAnything() {
        Fixture fixture = createFixture();
        List<Map<String, Object>> before = rows(fixture.scope().sessionId());
        DurableFrontier stale = new DurableFrontier(
                fixture.frontier().maxMessageId(), fixture.frontier().maxSeq() - 1);

        Throwable failure = catchThrowable(() -> reconciler.reconcile(
                fixture.scope(), stale, UUID.randomUUID(),
                MessageSnapshot.capture(Message.assistant("must not be written")),
                "completion-trace"));

        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Durable completion reconciliation failed")
                .hasNoCause();
        assertThat(rows(fixture.scope().sessionId())).isEqualTo(before);
    }

    @Test
    void noTerminalCompletionIsRejectedWithoutReleasingScope() {
        Fixture fixture = createFixture();
        UUID completionBatchId = UUID.randomUUID();

        Throwable failure = catchThrowable(() -> reconciler.reconcile(
                fixture.scope(), fixture.frontier(), completionBatchId, null,
                "completion-trace"));

        assertThat(failure).isInstanceOf(IllegalStateException.class)
                .hasMessage("Durable completion reconciliation failed")
                .hasNoCause();
        assertThat(rows(fixture.scope().sessionId())).isEqualTo(fixture.originalRows());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT active_loop_id FROM t_session WHERE id = ?",
                String.class, fixture.scope().sessionId()))
                .isEqualTo(fixture.scope().loopId());
    }

    @Test
    void toolBearingTailIsRejected_withoutRepairingTheAttemptOrWritingRows() {
        Fixture fixture = createFixture();
        List<Map<String, Object>> before = rows(fixture.scope().sessionId());
        Message illegalTail = new Message();
        illegalTail.setRole(Message.Role.ASSISTANT);
        illegalTail.setContent(List.of(ContentBlock.toolUse(
                "late-tool", "MustNotRun", Map.of("secret", "not-persisted"))));

        Throwable failure = catchThrowable(() -> reconciler.reconcile(
                fixture.scope(), fixture.frontier(), UUID.randomUUID(),
                MessageSnapshot.capture(illegalTail), "completion-trace"));

        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Durable completion reconciliation failed")
                .hasNoCause();
        assertThat(failure.toString()).doesNotContain("late-tool", "MustNotRun", "secret");
        assertThat(rows(fixture.scope().sessionId())).isEqualTo(before);
    }

    private Fixture createFixture() {
        String sessionId = UUID.randomUUID().toString();
        sessionIdToRemove = sessionId;
        long userId = 1101L;
        long historyEpoch = 4L;
        String loopId = UUID.randomUUID().toString();
        long loopFence = 12L;
        String owner = "completion-postgres-instance";
        SessionEntity session = new SessionEntity();
        session.setId(sessionId);
        session.setUserId(userId);
        session.setAgentId(1102L);
        session.setStatus("active");
        session.setRuntimeStatus("running");
        session.setHistoryEpoch(historyEpoch);
        session.setActiveLoopId(loopId);
        session.setLoopFence(loopFence);
        session.setLoopOwnerInstanceId(owner);
        session.setLoopLeaseUntil(Instant.now().plusSeconds(300));
        sessionRepository.saveAndFlush(session);

        String inputBatchId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO t_session_message
                    (session_id, seq_no, role, msg_type, message_type,
                     content_json, metadata_json, trace_id,
                     write_batch_id, write_batch_ordinal)
                VALUES (?, 0, 'user', 'NORMAL', 'normal', ?, '{}', 'input-trace', ?, 0),
                       (?, 1, 'user', 'NORMAL', 'normal', ?, '{}', 'result-trace', ?, 1)
                """,
                sessionId, "\"question\"", inputBatchId,
                sessionId,
                "[{\"type\":\"tool_result\",\"tool_use_id\":\"tool-1\","
                        + "\"content\":\"durable result\",\"is_error\":false}]",
                inputBatchId);
        List<Map<String, Object>> originalRows = rows(sessionId);
        Map<String, Object> tail = originalRows.get(originalRows.size() - 1);
        DurableFrontier frontier = new DurableFrontier(
                ((Number) tail.get("id")).longValue(),
                ((Number) tail.get("seq_no")).longValue());
        return new Fixture(
                new LoopDurabilityScope(
                        sessionId, userId, historyEpoch, loopId, loopFence, owner),
                frontier,
                originalRows);
    }

    private List<Map<String, Object>> rows(String sessionId) {
        return jdbcTemplate.queryForList("""
                SELECT id, seq_no, role, content_json, write_batch_id, write_batch_ordinal
                FROM t_session_message
                WHERE session_id = ?
                ORDER BY seq_no
                """, sessionId);
    }

    private void installTransientInsertFailure() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        functionName = "force_completion_transient_" + suffix;
        triggerName = "force_completion_transient_trigger_" + suffix;
        jdbcTemplate.execute("""
                CREATE FUNCTION %s() RETURNS trigger AS $$
                BEGIN
                    RAISE EXCEPTION 'forced completion serialization failure'
                        USING ERRCODE = '40001';
                END;
                $$ LANGUAGE plpgsql
                """.formatted(functionName));
        jdbcTemplate.execute("""
                CREATE TRIGGER %s
                BEFORE INSERT ON t_session_message
                FOR EACH ROW EXECUTE FUNCTION %s()
                """.formatted(triggerName, functionName));
    }

    private record Fixture(
            LoopDurabilityScope scope,
            DurableFrontier frontier,
            List<Map<String, Object>> originalRows) {
    }
}
