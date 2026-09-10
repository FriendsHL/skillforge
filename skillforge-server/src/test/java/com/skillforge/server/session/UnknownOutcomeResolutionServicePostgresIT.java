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
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.server.AbstractPostgresIT;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionMessageInboxEntity;
import com.skillforge.server.repository.SessionMessageInboxRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.session.UnknownOutcomeResolutionAck.ActorAuthority;
import com.skillforge.server.session.UnknownOutcomeResolutionRequest.InboxDisposition;
import com.skillforge.server.session.UnknownOutcomeResolutionRequest.InboxDispositionKind;
import com.skillforge.server.session.UnknownOutcomeResolutionRequest.PostAction;
import com.skillforge.server.session.persistence.PersistedMessageCodec;
import com.skillforge.server.session.persistence.SessionOrderedMessageWriter;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** PostgreSQL gates for the audited, fail-stop unknown-outcome resolution transaction. */
@Import({
        JacksonAutoConfiguration.class,
        PersistedMessageCodec.class,
        SessionOrderedMessageWriter.class,
        SessionToolAttemptTransactionService.class,
        UnknownOutcomeAttemptVerifier.class,
        UnknownOutcomeResolutionAuditWriter.class,
        UnknownOutcomeResolutionDiscoveryService.class,
        UnknownOutcomeResolutionService.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UnknownOutcomeResolutionServicePostgresIT extends AbstractPostgresIT {

    private static final String FORCED_FAILURE = "forced_unknown_resolution_commit_failure";
    private static final String REASON_SECRET = "operator reason should never be stored verbatim";
    private static final String TOOL_PAYLOAD_SECRET = "tool-input-secret";
    private static final String INBOX_PAYLOAD_SECRET = "queued-user-secret";

    @Autowired private SessionRepository sessionRepository;
    @Autowired private SessionMessageInboxRepository inboxRepository;
    @Autowired private SessionToolAttemptTransactionService attemptTransactions;
    @Autowired private UnknownOutcomeResolutionService resolutionService;
    @Autowired private UnknownOutcomeResolutionDiscoveryService discoveryService;
    @Autowired private PersistedMessageCodec messageCodec;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;

    private final List<String> sessionsToRemove = new ArrayList<>();
    private String triggerName;
    private String functionName;

    @Test
    void discovery_readsExactCurrentGenerationAndOrderedInboxFromPostgres() {
        Fixture fixture = createUncertainFixture(2, 2);

        UnknownOutcomeResolutionDiscovery discovery = discoveryService
                .find(fixture.sessionId(), owner(fixture))
                .orElseThrow();

        assertThat(discovery.sessionId()).isEqualTo(fixture.sessionId());
        assertThat(discovery.attemptId()).isEqualTo(fixture.attemptId());
        assertThat(discovery.historyEpoch()).isEqualTo(fixture.historyEpoch());
        assertThat(discovery.executionGeneration()).isEqualTo(fixture.executionGeneration());
        assertThat(discovery.executionFence()).isEqualTo(fixture.executionFence());
        assertThat(discovery.state()).isEqualTo("UNCERTAIN_PENDING_RESOLUTION");
        assertThat(discovery.actorAuthority()).isEqualTo(ActorAuthority.OWNER);
        assertThat(discovery.calls()).extracting(
                        UnknownOutcomeResolutionDiscovery.ToolCall::providerOrdinal,
                        UnknownOutcomeResolutionDiscovery.ToolCall::toolUseId,
                        UnknownOutcomeResolutionDiscovery.ToolCall::toolName)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                0, "unknown-tool-use-0", "UnknownMutation0"),
                        org.assertj.core.groups.Tuple.tuple(
                                1, "unknown-tool-use-1", "UnknownMutation1"));
        assertThat(discovery.calls()).allSatisfy(call -> assertThat(call.input())
                .contains(TOOL_PAYLOAD_SECRET)
                .startsWith("{")
                .endsWith("}"));
        assertThat(discovery.inboxIds()).containsExactlyElementsOf(fixture.inboxIds());
    }

    @AfterEach
    void cleanFixture() {
        if (triggerName != null) {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + triggerName
                    + " ON t_session_tool_attempt_resolution_audit");
        }
        if (functionName != null) {
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + functionName + "()");
        }
        for (String sessionId : sessionsToRemove) {
            jdbcTemplate.update("DELETE FROM t_session WHERE id = ?", sessionId);
        }
    }

    @Test
    void ownerContinue_writesOrderedUnknownVectorAuditAndPendingContinuationAtomically() {
        Fixture fixture = createUncertainFixture(3, 2);
        UUID requestId = UUID.randomUUID();
        UnknownOutcomeResolutionRequest request = request(
                fixture, requestId, PostAction.CONTINUE_CURRENT_TIMELINE,
                fixture.inboxIds().stream()
                        .map(id -> new InboxDisposition(id, InboxDispositionKind.KEEP_FOR_CONTINUE))
                        .toList());

        UnknownOutcomeResolutionAck ack = resolutionService.resolve(
                fixture.sessionId(), fixture.attemptId(),
                UnknownOutcomeResolutionActor.authenticated(fixture.ownerUserId(), Set.of()),
                request);

        assertThat(ack.resolutionRequestId()).isEqualTo(requestId);
        assertThat(ack.actorAuthority()).isEqualTo(ActorAuthority.OWNER);
        assertThat(ack.outcomeState()).isEqualTo("RESOLVED_UNKNOWN");
        assertThat(ack.postActionState()).isEqualTo("PENDING");
        assertThat(ack.restorePreparing()).isFalse();
        assertThat(ack.inboxDispositions()).isEqualTo(request.inboxDispositions());

        assertThat(jdbcTemplate.queryForMap("""
                SELECT state, result_batch_id, result_execution_generation,
                       result_execution_fence, archive_preparation_state,
                       archive_prepared_count, archive_total_count,
                       post_action_state, post_action_resolution_request_id,
                       post_action_result_batch_id, post_action_kind
                FROM t_session_tool_attempt WHERE id = ?
                """, fixture.attemptId()))
                .containsEntry("state", "RESOLVED_UNKNOWN")
                .containsEntry("result_batch_id", ack.resultBatchId())
                .containsEntry("result_execution_generation", fixture.executionGeneration())
                .containsEntry("result_execution_fence", fixture.executionFence())
                .containsEntry("archive_preparation_state", "PENDING")
                .containsEntry("archive_prepared_count", 0)
                .containsEntry("archive_total_count", 3)
                .containsEntry("post_action_state", "PENDING")
                .containsEntry("post_action_resolution_request_id", requestId)
                .containsEntry("post_action_result_batch_id", ack.resultBatchId())
                .containsEntry("post_action_kind", "CONTINUE_CURRENT_TIMELINE");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM t_session_message_inbox WHERE session_id = ?",
                Long.class, fixture.sessionId())).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT message_count FROM t_session WHERE id = ?",
                Integer.class, fixture.sessionId())).isEqualTo(4);
        assertUnknownResults(fixture, ack);
        assertAuditContainsNoPayload(fixture, requestId, "OWNER");
    }

    @Test
    void authorizedAdminPrepareRestore_discardsOnlyExplicitRowsAndKeepsAuditEvidence() {
        Fixture fixture = createUncertainFixture(2, 2);
        List<InboxDisposition> dispositions = List.of(
                new InboxDisposition(fixture.inboxIds().get(0),
                        InboxDispositionKind.DISCARD_FOR_RESTORE),
                new InboxDisposition(fixture.inboxIds().get(1),
                        InboxDispositionKind.KEEP_FOR_RESTORE));
        UUID requestId = UUID.randomUUID();

        UnknownOutcomeResolutionAck ack = resolutionService.resolve(
                fixture.sessionId(), fixture.attemptId(),
                UnknownOutcomeResolutionActor.authenticated(
                        fixture.ownerUserId() + 100, Set.of("session:resolve-unknown")),
                request(fixture, requestId, PostAction.PREPARE_RESTORE, dispositions));

        assertThat(ack.actorAuthority()).isEqualTo(ActorAuthority.ADMIN);
        assertThat(ack.postActionState()).isEqualTo("NONE");
        assertThat(ack.restorePreparing()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT restore_preparing FROM t_session WHERE id = ?",
                Boolean.class, fixture.sessionId())).isTrue();
        assertThat(jdbcTemplate.queryForList("""
                SELECT inbox_id FROM t_session_message_inbox
                WHERE session_id = ? ORDER BY id
                """, UUID.class, fixture.sessionId()))
                .containsExactly(fixture.inboxIds().get(1));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT post_action_state FROM t_session_tool_attempt WHERE id = ?
                """, String.class, fixture.attemptId())).isEqualTo("NONE");
        assertAuditContainsNoPayload(fixture, requestId, "ADMIN");
    }

    @Test
    void continueCurrentTimeline_keepsCanonicalMultimodalQueuedUser() {
        Fixture fixture = createUncertainFixture(1, 1);
        replaceInboxMessageWithMultimodalUser(fixture, 0);
        UUID inboxId = fixture.inboxIds().get(0);

        UnknownOutcomeResolutionAck ack = resolutionService.resolve(
                fixture.sessionId(), fixture.attemptId(), owner(fixture),
                request(fixture, UUID.randomUUID(), PostAction.CONTINUE_CURRENT_TIMELINE,
                        List.of(new InboxDisposition(
                                inboxId, InboxDispositionKind.KEEP_FOR_CONTINUE))));

        assertThat(ack.outcomeState()).isEqualTo("RESOLVED_UNKNOWN");
        assertThat(ack.inboxDispositions()).containsExactly(
                new InboxDisposition(inboxId, InboxDispositionKind.KEEP_FOR_CONTINUE));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM t_session_message_inbox WHERE session_id = ?",
                Long.class, fixture.sessionId())).isEqualTo(1L);
    }

    @Test
    void prepareRestore_discardsCanonicalMultimodalQueuedUser() {
        Fixture fixture = createUncertainFixture(1, 1);
        replaceInboxMessageWithMultimodalUser(fixture, 0);
        UUID inboxId = fixture.inboxIds().get(0);

        UnknownOutcomeResolutionAck ack = resolutionService.resolve(
                fixture.sessionId(), fixture.attemptId(), owner(fixture),
                request(fixture, UUID.randomUUID(), PostAction.PREPARE_RESTORE,
                        List.of(new InboxDisposition(
                                inboxId, InboxDispositionKind.DISCARD_FOR_RESTORE))));

        assertThat(ack.outcomeState()).isEqualTo("RESOLVED_UNKNOWN");
        assertThat(ack.restorePreparing()).isTrue();
        assertThat(ack.inboxDispositions()).containsExactly(
                new InboxDisposition(inboxId, InboxDispositionKind.DISCARD_FOR_RESTORE));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM t_session_message_inbox WHERE session_id = ?",
                Long.class, fixture.sessionId())).isZero();
    }

    @Test
    void sameResolutionRequestAfterAckLoss_returnsIdenticalAckWithoutDuplicateWrites() {
        Fixture fixture = createUncertainFixture(2, 2);
        List<InboxDisposition> dispositions = List.of(
                new InboxDisposition(fixture.inboxIds().get(0),
                        InboxDispositionKind.DISCARD_FOR_RESTORE),
                new InboxDisposition(fixture.inboxIds().get(1),
                        InboxDispositionKind.KEEP_FOR_RESTORE));
        UnknownOutcomeResolutionRequest request = request(
                fixture, UUID.randomUUID(), PostAction.PREPARE_RESTORE, dispositions);
        UnknownOutcomeResolutionActor actor = UnknownOutcomeResolutionActor.authenticated(
                fixture.ownerUserId(), Set.of());

        UnknownOutcomeResolutionAck first = resolutionService.resolve(
                fixture.sessionId(), fixture.attemptId(), actor, request);
        List<Map<String, Object>> firstRows = jdbcTemplate.queryForList("""
                SELECT id, seq_no, write_batch_id, write_batch_ordinal, content_json, created_at
                FROM t_session_message WHERE session_id = ? ORDER BY seq_no
                """, fixture.sessionId());
        UnknownOutcomeResolutionAck retry = resolutionService.resolve(
                fixture.sessionId(), fixture.attemptId(), actor, request);

        assertThat(retry).isEqualTo(first);
        assertThat(jdbcTemplate.queryForList("""
                SELECT id, seq_no, write_batch_id, write_batch_ordinal, content_json, created_at
                FROM t_session_message WHERE session_id = ? ORDER BY seq_no
                """, fixture.sessionId())).isEqualTo(firstRows);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM t_session_tool_attempt_resolution_audit
                WHERE session_id = ?
                """, Long.class, fixture.sessionId())).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM t_session_message_inbox WHERE session_id = ?
                """, Long.class, fixture.sessionId())).isEqualTo(1L);
    }

    @Test
    void unauthorizedAndStaleCommands_areNonEnumerativeAndLeaveBarrierUntouched() {
        Fixture fixture = createUncertainFixture(2, 1);
        UnknownOutcomeResolutionRequest valid = request(
                fixture, UUID.randomUUID(), PostAction.CONTINUE_CURRENT_TIMELINE,
                List.of(new InboxDisposition(fixture.inboxIds().get(0),
                        InboxDispositionKind.KEEP_FOR_CONTINUE)));
        List<Throwable> failures = List.of(
                catchThrowable(() -> resolutionService.resolve(
                        fixture.sessionId(), fixture.attemptId(),
                        UnknownOutcomeResolutionActor.authenticated(
                                fixture.ownerUserId() + 1, Set.of()), valid)),
                catchThrowable(() -> resolutionService.resolve(
                        fixture.sessionId(), fixture.attemptId(),
                        UnknownOutcomeResolutionActor.authenticated(
                                fixture.ownerUserId() + 2, Set.of("ROLE_ADMIN")), valid)),
                catchThrowable(() -> resolutionService.resolve(
                        fixture.sessionId(), fixture.attemptId(), owner(fixture),
                        withEpoch(valid, fixture.historyEpoch() + 1))),
                catchThrowable(() -> resolutionService.resolve(
                        fixture.sessionId(), fixture.attemptId(), owner(fixture),
                        withGeneration(valid, fixture.executionGeneration() + 1))),
                catchThrowable(() -> resolutionService.resolve(
                        fixture.sessionId(), fixture.attemptId(), owner(fixture),
                        withFence(valid, fixture.executionFence() + 1))),
                catchThrowable(() -> resolutionService.resolve(
                        fixture.sessionId(), fixture.attemptId() + 999, owner(fixture), valid)));

        assertThat(failures).allSatisfy(failure -> assertThat(failure)
                .isInstanceOf(UnknownOutcomeResolutionException.class)
                .hasMessage("Unknown outcome resolution is not available")
                .hasNoCause());
        assertThat(failures).extracting(failure ->
                        ((UnknownOutcomeResolutionException) failure).code())
                .containsOnly(UnknownOutcomeResolutionException.Code.RESOLUTION_NOT_AVAILABLE);
        assertUnresolvedAndUnchanged(fixture);
    }

    @Test
    void secondResolutionRequest_isRejectedWithoutSecondVectorOrAudit() {
        Fixture fixture = createUncertainFixture(2, 0);
        UnknownOutcomeResolutionRequest first = request(
                fixture, UUID.randomUUID(), PostAction.CONTINUE_CURRENT_TIMELINE, List.of());
        resolutionService.resolve(
                fixture.sessionId(), fixture.attemptId(), owner(fixture), first);

        Throwable failure = catchThrowable(() -> resolutionService.resolve(
                fixture.sessionId(), fixture.attemptId(), owner(fixture),
                request(fixture, UUID.randomUUID(), PostAction.CONTINUE_CURRENT_TIMELINE,
                        List.of())));

        assertThat(failure)
                .isInstanceOf(UnknownOutcomeResolutionException.class)
                .hasMessage("Unknown outcome resolution is not available")
                .hasNoCause();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM t_session_tool_attempt_resolution_audit
                WHERE session_id = ?
                """, Long.class, fixture.sessionId())).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM t_session_message WHERE session_id = ?",
                Long.class, fixture.sessionId())).isEqualTo(3L);
    }

    @Test
    void corruptedManifestEvidence_isRejectedBeforeAnyMutation() {
        Fixture fixture = createUncertainFixture(2, 0);
        jdbcTemplate.update("""
                UPDATE t_session_tool_attempt
                SET manifest_json = manifest_json || ' '
                WHERE id = ?
                """, fixture.attemptId());

        Throwable failure = catchThrowable(() -> resolutionService.resolve(
                fixture.sessionId(), fixture.attemptId(), owner(fixture),
                request(fixture, UUID.randomUUID(), PostAction.CONTINUE_CURRENT_TIMELINE,
                        List.of())));

        assertThat(failure)
                .isInstanceOf(UnknownOutcomeResolutionException.class)
                .hasMessage("Unknown outcome resolution is not available")
                .hasNoCause();
        assertUnresolvedAndUnchanged(fixture);
    }

    @Test
    void deferredAuditFailure_rollsBackResultsAttemptDispositionAndRestorePreparation() {
        Fixture fixture = createUncertainFixture(2, 1);
        UUID requestId = UUID.randomUUID();
        installDeferredAuditFailure(requestId);
        UnknownOutcomeResolutionRequest request = request(
                fixture, requestId, PostAction.PREPARE_RESTORE,
                List.of(new InboxDisposition(fixture.inboxIds().get(0),
                        InboxDispositionKind.DISCARD_FOR_RESTORE)));

        Throwable failure = catchThrowable(() -> resolutionService.resolve(
                fixture.sessionId(), fixture.attemptId(), owner(fixture), request));

        assertThat(failure)
                .isInstanceOf(UnknownOutcomeResolutionException.class)
                .hasMessage("Unknown outcome resolution could not be persisted")
                .hasNoCause();
        assertThat(failure.toString()).doesNotContain(
                fixture.sessionId(), requestId.toString(), REASON_SECRET,
                TOOL_PAYLOAD_SECRET, INBOX_PAYLOAD_SECRET, FORCED_FAILURE);
        assertUnresolvedAndUnchanged(fixture);
    }

    private Fixture createUncertainFixture(int toolCount, int inboxCount) {
        String sessionId = UUID.randomUUID().toString();
        sessionsToRemove.add(sessionId);
        long ownerUserId = 7101L;
        long historyEpoch = 4L;
        long loopFence = 9L;
        String loopId = UUID.randomUUID().toString();
        String executionOwner = "unknown-resolution-fixture";
        SessionEntity session = new SessionEntity();
        session.setId(sessionId);
        session.setUserId(ownerUserId);
        session.setAgentId(7102L);
        session.setStatus("active");
        session.setRuntimeStatus("running");
        session.setHistoryEpoch(historyEpoch);
        session.setActiveLoopId(loopId);
        session.setLoopFence(loopFence);
        session.setLoopOwnerInstanceId(executionOwner);
        session.setLoopLeaseUntil(Instant.now().plusSeconds(300));
        sessionRepository.saveAndFlush(session);

        List<String> toolUseIds = new ArrayList<>();
        List<ContentBlock> blocks = new ArrayList<>();
        List<ToolCallIntent> calls = new ArrayList<>();
        for (int ordinal = 0; ordinal < toolCount; ordinal++) {
            String toolUseId = "unknown-tool-use-" + ordinal;
            String toolName = "UnknownMutation" + ordinal;
            Map<String, Object> input = Map.of(
                    "secret", TOOL_PAYLOAD_SECRET + ordinal,
                    "ordinal", ordinal);
            toolUseIds.add(toolUseId);
            blocks.add(ContentBlock.toolUse(toolUseId, toolName, input));
            calls.add(new ToolCallIntent(
                    ordinal, toolUseId, toolName, FrozenJson.capture(input),
                    ReplaySafety.MUTATING));
        }
        Message assistant = new Message();
        assistant.setRole(Message.Role.ASSISTANT);
        assistant.setContent(blocks);
        LoopDurabilityScope scope = new LoopDurabilityScope(
                sessionId, ownerUserId, historyEpoch, loopId, loopFence, executionOwner);
        IntentCommitAck intent = attemptTransactions.commitIntent(new IntentCommitCommand(
                scope, UUID.randomUUID(), UUID.randomUUID().toString(),
                MessageSnapshot.capture(assistant),
                new ToolCallManifest(calls, ReplaySafety.MUTATING),
                DurableFrontier.EMPTY, UUID.randomUUID().toString()));
        ExecutionClaimAck execution = attemptTransactions.claimExecution(
                new ExecutionClaimCommand(
                        scope, intent.attemptId(), intent.stepId(), UUID.randomUUID(),
                        DurableToolAttemptState.INTENT_COMMITTED, 0L));

        jdbcTemplate.update("""
                UPDATE t_session_tool_attempt
                SET state = 'UNCERTAIN_PENDING_RESOLUTION'
                WHERE id = ?
                """, intent.attemptId());
        jdbcTemplate.update("""
                UPDATE t_session
                SET active_loop_id = NULL, loop_owner_instance_id = NULL,
                    loop_lease_until = NULL, runtime_status = 'error',
                    runtime_step = 'tool_outcome_uncertain',
                    runtime_failure_source = 'tool',
                    runtime_failure_code = 'TOOL_OUTCOME_UNCERTAIN',
                    runtime_retryable = FALSE, runtime_side_effects = 'possible',
                    runtime_error = 'Tool outcome requires manual resolution.'
                WHERE id = ?
                """, sessionId);
        entityManager.clear();

        List<UUID> inboxIds = new ArrayList<>();
        for (int i = 0; i < inboxCount; i++) {
            UUID inboxId = UUID.randomUUID();
            SessionMessageInboxEntity inbox = new SessionMessageInboxEntity();
            inbox.setInboxId(inboxId);
            inbox.setSessionId(sessionId);
            inbox.setUserId(ownerUserId);
            inbox.setMessageJson(messageCodec.writeMessage(
                    Message.user(INBOX_PAYLOAD_SECRET + i)));
            inboxRepository.saveAndFlush(inbox);
            inboxIds.add(inboxId);
        }
        return new Fixture(
                sessionId, ownerUserId, historyEpoch, intent.attemptId(), intent.stepId(),
                execution.executionGeneration(), loopFence, List.copyOf(toolUseIds),
                List.copyOf(inboxIds));
    }

    private UnknownOutcomeResolutionRequest request(
            Fixture fixture,
            UUID requestId,
            PostAction action,
            List<InboxDisposition> dispositions) {
        return new UnknownOutcomeResolutionRequest(
                requestId,
                fixture.historyEpoch(),
                fixture.executionGeneration(),
                fixture.executionFence(),
                action,
                REASON_SECRET,
                dispositions);
    }

    private void replaceInboxMessageWithMultimodalUser(Fixture fixture, int inboxOrdinal) {
        Message multimodal = new Message();
        multimodal.setRole(Message.Role.USER);
        multimodal.setContent(List.of(
                ContentBlock.text("inspect the queued image"),
                ContentBlock.imageRef("queued-image", "image/png", "queued.png")));
        int updated = jdbcTemplate.update("""
                UPDATE t_session_message_inbox
                SET message_json = ?
                WHERE session_id = ? AND inbox_id = ?
                """, messageCodec.writeMessage(multimodal), fixture.sessionId(),
                fixture.inboxIds().get(inboxOrdinal));
        assertThat(updated).isEqualTo(1);
        entityManager.clear();
    }

    private static UnknownOutcomeResolutionRequest withEpoch(
            UnknownOutcomeResolutionRequest request, long epoch) {
        return new UnknownOutcomeResolutionRequest(
                request.resolutionRequestId(), epoch,
                request.expectedExecutionGeneration(), request.expectedExecutionFence(),
                request.action(), request.reason(), request.inboxDispositions());
    }

    private static UnknownOutcomeResolutionRequest withGeneration(
            UnknownOutcomeResolutionRequest request, long generation) {
        return new UnknownOutcomeResolutionRequest(
                request.resolutionRequestId(), request.expectedHistoryEpoch(),
                generation, request.expectedExecutionFence(), request.action(),
                request.reason(), request.inboxDispositions());
    }

    private static UnknownOutcomeResolutionRequest withFence(
            UnknownOutcomeResolutionRequest request, long fence) {
        return new UnknownOutcomeResolutionRequest(
                request.resolutionRequestId(), request.expectedHistoryEpoch(),
                request.expectedExecutionGeneration(), fence, request.action(),
                request.reason(), request.inboxDispositions());
    }

    private static UnknownOutcomeResolutionActor owner(Fixture fixture) {
        return UnknownOutcomeResolutionActor.authenticated(fixture.ownerUserId(), Set.of());
    }

    private void assertUnknownResults(Fixture fixture, UnknownOutcomeResolutionAck ack) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT write_batch_ordinal, role, content_json
                FROM t_session_message
                WHERE session_id = ? AND write_batch_id = ?
                ORDER BY write_batch_ordinal
                """, fixture.sessionId(), ack.resultBatchId().toString());
        assertThat(rows).hasSize(fixture.toolUseIds().size());
        for (int ordinal = 0; ordinal < rows.size(); ordinal++) {
            assertThat(rows.get(ordinal))
                    .containsEntry("write_batch_ordinal", ordinal)
                    .containsEntry("role", "user");
            String content = (String) rows.get(ordinal).get("content_json");
            assertThat(content)
                    .contains("\"tool_use_id\":\"" + fixture.toolUseIds().get(ordinal) + "\"")
                    .contains("\"is_error\":true")
                    .contains("\"error_type\":\"OUTCOME_UNKNOWN\"")
                    .contains("may already have succeeded")
                    .contains("do not retry automatically")
                    .doesNotContain(TOOL_PAYLOAD_SECRET, INBOX_PAYLOAD_SECRET, REASON_SECRET);
        }
    }

    private void assertAuditContainsNoPayload(
            Fixture fixture, UUID requestId, String expectedAuthority) {
        Map<String, Object> audit = jdbcTemplate.queryForMap("""
                SELECT actor_authority, reason_hash, inbox_dispositions_json,
                       result_batch_id, outcome_state
                FROM t_session_tool_attempt_resolution_audit
                WHERE resolution_request_id = ?
                """, requestId);
        assertThat(audit)
                .containsEntry("actor_authority", expectedAuthority)
                .containsEntry("outcome_state", "RESOLVED_UNKNOWN");
        assertThat(audit.get("reason_hash").toString()).matches("[0-9a-f]{64}");
        assertThat(audit.get("inbox_dispositions_json").toString())
                .contains("messageHash", "inboxId", "disposition")
                .doesNotContain(REASON_SECRET, TOOL_PAYLOAD_SECRET, INBOX_PAYLOAD_SECRET);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM t_session_tool_attempt_resolution_audit
                WHERE session_id = ? AND attempt_id = ?
                """, Long.class, fixture.sessionId(), fixture.attemptId())).isEqualTo(1L);
    }

    private void assertUnresolvedAndUnchanged(Fixture fixture) {
        assertThat(jdbcTemplate.queryForMap("""
                SELECT state, result_batch_id, post_action_state
                FROM t_session_tool_attempt WHERE id = ?
                """, fixture.attemptId()))
                .containsEntry("state", "UNCERTAIN_PENDING_RESOLUTION")
                .containsEntry("result_batch_id", null)
                .containsEntry("post_action_state", "NONE");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM t_session_message WHERE session_id = ?",
                Long.class, fixture.sessionId())).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM t_session_tool_attempt_resolution_audit
                WHERE session_id = ?
                """, Long.class, fixture.sessionId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM t_session_message_inbox WHERE session_id = ?",
                Long.class, fixture.sessionId())).isEqualTo(fixture.inboxIds().size());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT restore_preparing FROM t_session WHERE id = ?",
                Boolean.class, fixture.sessionId())).isFalse();
    }

    private void installDeferredAuditFailure(UUID resolutionRequestId) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        functionName = "fail_unknown_resolution_" + suffix;
        triggerName = "fail_unknown_resolution_" + suffix;
        jdbcTemplate.execute("""
                CREATE FUNCTION %s() RETURNS trigger
                LANGUAGE plpgsql
                AS $function$
                BEGIN
                    RAISE EXCEPTION '%s';
                END
                $function$
                """.formatted(functionName, FORCED_FAILURE));
        jdbcTemplate.execute("""
                CREATE CONSTRAINT TRIGGER %s
                AFTER INSERT ON t_session_tool_attempt_resolution_audit
                DEFERRABLE INITIALLY DEFERRED
                FOR EACH ROW
                WHEN (NEW.resolution_request_id = '%s'::uuid)
                EXECUTE FUNCTION %s()
                """.formatted(triggerName, resolutionRequestId, functionName));
    }

    private record Fixture(
            String sessionId,
            long ownerUserId,
            long historyEpoch,
            long attemptId,
            UUID stepId,
            long executionGeneration,
            long executionFence,
            List<String> toolUseIds,
            List<UUID> inboxIds) {
    }
}
