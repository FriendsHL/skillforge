package com.skillforge.server.session;

import com.skillforge.core.engine.durability.FrozenJson;
import com.skillforge.core.engine.durability.DurableFrontier;
import com.skillforge.core.engine.durability.IntentCommitCommand;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** PostgreSQL hard gate for the assistant-intent/attempt transaction boundary. */
@Import({
        JacksonAutoConfiguration.class,
        PersistedMessageCodec.class,
        SessionOrderedMessageWriter.class,
        SessionToolAttemptTransactionService.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SessionToolIntentPostgresIT extends AbstractPostgresIT {

    private static final String TRIGGER_FAILURE = "forced_session_tool_attempt_insert_failure";

    @Autowired private SessionRepository sessionRepository;
    @Autowired private SessionToolAttemptTransactionService transactionService;
    @Autowired private JdbcTemplate jdbcTemplate;
    private String triggerName;
    private String functionName;
    private String sessionIdToRemove;

    @AfterEach
    void removeFailureTrigger() {
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
    void attemptInsertFailure_rollsBackAssistantBatchAndAttemptTogether() {
        String sessionId = UUID.randomUUID().toString();
        sessionIdToRemove = sessionId;
        String loopId = UUID.randomUUID().toString();
        String ownerInstanceId = "intent-red-instance";
        long historyEpoch = 7L;
        long loopFence = 11L;
        long userId = 41L;
        SessionEntity session = new SessionEntity();
        session.setId(sessionId);
        session.setUserId(userId);
        session.setAgentId(73L);
        session.setStatus("active");
        session.setRuntimeStatus("running");
        session.setHistoryEpoch(historyEpoch);
        session.setActiveLoopId(loopId);
        session.setLoopFence(loopFence);
        session.setLoopOwnerInstanceId(ownerInstanceId);
        session.setLoopLeaseUntil(Instant.now().plusSeconds(300));
        sessionRepository.saveAndFlush(session);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("path", "/tmp/durable-intent-red");
        input.put("content", "must-roll-back");
        String toolUseId = "toolu-red-1";
        String toolName = "WriteProbe";
        Message assistant = new Message();
        assistant.setRole(Message.Role.ASSISTANT);
        assistant.setReasoningContent("persist-this-exact-reasoning");
        assistant.setContent(List.of(
                ContentBlock.text("I will write the probe."),
                ContentBlock.toolUse(toolUseId, toolName, input)));

        LoopDurabilityScope scope = new LoopDurabilityScope(
                sessionId, userId, historyEpoch, loopId, loopFence, ownerInstanceId);
        UUID stepId = UUID.randomUUID();
        String writeBatchId = UUID.randomUUID().toString();
        ToolCallManifest manifest = new ToolCallManifest(
                List.of(new ToolCallIntent(
                        0, toolUseId, toolName, FrozenJson.capture(input), ReplaySafety.MUTATING)),
                ReplaySafety.MUTATING);
        IntentCommitCommand command = new IntentCommitCommand(
                scope,
                stepId,
                writeBatchId,
                MessageSnapshot.capture(assistant),
                manifest,
                DurableFrontier.EMPTY,
                UUID.randomUUID().toString());

        installAttemptInsertFailureTrigger(sessionId, stepId);

        Throwable failure = catchThrowable(() -> transactionService.commitIntent(command));

        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Durable intent persistence failed")
                .hasNoCause();
        assertThat(failure.toString())
                .doesNotContain("must-roll-back", toolUseId, stepId.toString(), writeBatchId,
                        TRIGGER_FAILURE);
        assertThat(jdbcTemplate.queryForObject("""
                        SELECT count(*)
                        FROM t_session_message
                        WHERE session_id = ? AND write_batch_id = ?
                        """, Long.class, sessionId, writeBatchId))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("""
                        SELECT count(*)
                        FROM t_session_tool_attempt
                        WHERE session_id = ? AND step_id = ?
                        """, Long.class, sessionId, stepId))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT message_count FROM t_session WHERE id = ?",
                        Integer.class,
                        sessionId))
                .isZero();
    }

    @Test
    void deferredCommitFailure_isMappedOutsideTheTransactionAndRollsBackEverything() {
        String sessionId = UUID.randomUUID().toString();
        sessionIdToRemove = sessionId;
        String loopId = UUID.randomUUID().toString();
        String ownerInstanceId = "intent-deferred-instance";
        long historyEpoch = 8L;
        long loopFence = 12L;
        long userId = 42L;
        SessionEntity session = new SessionEntity();
        session.setId(sessionId);
        session.setUserId(userId);
        session.setAgentId(74L);
        session.setStatus("active");
        session.setRuntimeStatus("running");
        session.setHistoryEpoch(historyEpoch);
        session.setActiveLoopId(loopId);
        session.setLoopFence(loopFence);
        session.setLoopOwnerInstanceId(ownerInstanceId);
        session.setLoopLeaseUntil(Instant.now().plusSeconds(300));
        sessionRepository.saveAndFlush(session);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("path", "/tmp/durable-intent-deferred");
        input.put("content", "must-roll-back-at-commit");
        String toolUseId = "toolu-deferred-1";
        String toolName = "WriteProbe";
        Message assistant = new Message();
        assistant.setRole(Message.Role.ASSISTANT);
        assistant.setContent(List.of(ContentBlock.toolUse(toolUseId, toolName, input)));
        LoopDurabilityScope scope = new LoopDurabilityScope(
                sessionId, userId, historyEpoch, loopId, loopFence, ownerInstanceId);
        UUID stepId = UUID.randomUUID();
        String writeBatchId = UUID.randomUUID().toString();
        ToolCallManifest manifest = new ToolCallManifest(
                List.of(new ToolCallIntent(
                        0, toolUseId, toolName, FrozenJson.capture(input), ReplaySafety.MUTATING)),
                ReplaySafety.MUTATING);
        IntentCommitCommand command = new IntentCommitCommand(
                scope,
                stepId,
                writeBatchId,
                MessageSnapshot.capture(assistant),
                manifest,
                DurableFrontier.EMPTY,
                UUID.randomUUID().toString());

        installDeferredAttemptFailureConstraintTrigger(sessionId, stepId);

        Throwable failure = catchThrowable(() -> transactionService.commitIntent(command));

        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Durable intent persistence failed")
                .hasNoCause();
        assertThat(failure.toString())
                .doesNotContain("must-roll-back-at-commit", toolUseId, stepId.toString(),
                        writeBatchId, TRIGGER_FAILURE);
        assertThat(jdbcTemplate.queryForObject("""
                        SELECT count(*) FROM t_session_message
                        WHERE session_id = ? AND write_batch_id = ?
                        """, Long.class, sessionId, writeBatchId))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("""
                        SELECT count(*) FROM t_session_tool_attempt
                        WHERE session_id = ? AND step_id = ?
                        """, Long.class, sessionId, stepId))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT message_count FROM t_session WHERE id = ?",
                        Integer.class,
                        sessionId))
                .isZero();
    }

    private void installAttemptInsertFailureTrigger(String sessionId, UUID stepId) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        functionName = "fail_session_tool_attempt_insert_" + suffix;
        triggerName = "fail_session_tool_attempt_insert_" + suffix;
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
                CREATE TRIGGER %s
                BEFORE INSERT ON t_session_tool_attempt
                FOR EACH ROW
                WHEN (NEW.session_id = '%s' AND NEW.step_id = '%s'::uuid)
                EXECUTE FUNCTION %s()
                """.formatted(triggerName, sessionId, stepId, functionName));
    }

    private void installDeferredAttemptFailureConstraintTrigger(String sessionId, UUID stepId) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        functionName = "fail_session_tool_attempt_deferred_" + suffix;
        triggerName = "fail_session_tool_attempt_deferred_" + suffix;
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
                AFTER INSERT ON t_session_tool_attempt
                DEFERRABLE INITIALLY DEFERRED
                FOR EACH ROW
                WHEN (NEW.session_id = '%s' AND NEW.step_id = '%s'::uuid)
                EXECUTE FUNCTION %s()
                """.formatted(triggerName, sessionId, stepId, functionName));
    }

}
