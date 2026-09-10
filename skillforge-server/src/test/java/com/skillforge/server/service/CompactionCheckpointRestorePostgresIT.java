package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.ToolCatalog;
import com.skillforge.core.compact.FullCompactStrategy;
import com.skillforge.core.compact.LightCompactStrategy;
import com.skillforge.core.context.runtime.ContextRuntimeAuthority;
import com.skillforge.core.context.runtime.ContextRuntimeSnapshot;
import com.skillforge.core.model.Message;
import com.skillforge.core.skill.view.SessionSkillView;
import com.skillforge.server.AbstractPostgresIT;
import com.skillforge.server.config.SessionMessageStoreProperties;
import com.skillforge.server.entity.SessionCompactionCheckpointEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionMessageInboxEntity;
import com.skillforge.server.entity.SessionSummaryEntity;
import com.skillforge.server.entity.SessionToolAttemptEntity;
import com.skillforge.server.entity.SessionToolAttemptResolutionAuditEntity;
import com.skillforge.server.entity.ToolResultArchiveEntity;
import com.skillforge.server.history.CurrentSessionHistoryScope;
import com.skillforge.server.history.HistoryProtocolException;
import com.skillforge.server.history.HistoryRefCodec;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.CompactionEventRepository;
import com.skillforge.server.repository.SessionCompactionCheckpointRepository;
import com.skillforge.server.repository.SessionMessageInboxRepository;
import com.skillforge.server.repository.SessionMessageRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.SessionSummaryRepository;
import com.skillforge.server.repository.SessionToolAttemptRepository;
import com.skillforge.server.repository.SessionToolAttemptResolutionAuditRepository;
import com.skillforge.server.repository.ToolResultArchiveRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Import(JacksonAutoConfiguration.class)
class CompactionCheckpointRestorePostgresIT extends AbstractPostgresIT {

    @Autowired private SessionRepository sessionRepository;
    @Autowired private SessionMessageRepository messageRepository;
    @Autowired private SessionSummaryRepository summaryRepository;
    @Autowired private SessionCompactionCheckpointRepository checkpointRepository;
    @Autowired private SessionMessageInboxRepository inboxRepository;
    @Autowired private SessionToolAttemptRepository attemptRepository;
    @Autowired private SessionToolAttemptResolutionAuditRepository auditRepository;
    @Autowired private ToolResultArchiveRepository archiveRepository;
    @Autowired private AgentRepository agentRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void restorePreservesPrefixIdentityBumpsEpochAndKeepsResolutionAudit() {
        SessionService sessions = new SessionService(
                sessionRepository, messageRepository, agentRepository,
                new SessionMessageStoreProperties(), objectMapper, transactionManager);
        sessions.setSessionSummaryRepository(summaryRepository);
        CompactionService compaction = compactionService(sessions);

        SessionEntity session = new SessionEntity();
        session.setId(UUID.randomUUID().toString());
        session.setUserId(73L);
        session.setAgentId(1L);
        session.setTitle("restore-pg");
        session.setStatus("active");
        session.setRuntimeStatus("idle");
        session.setHistoryEpoch(4L);
        session.setRestorePreparing(true);
        session.setContextRuntimeJson("future-runtime");
        sessionRepository.saveAndFlush(session);

        String batch = UUID.randomUUID().toString();
        sessions.appendMessages(session.getId(), List.of(
                append(Message.user("keep-user"), batch, 0),
                append(Message.assistant("keep-assistant"), batch, 1),
                append(Message.user("keep-result"), batch, 2),
                append(Message.user("future"), batch, 3)));
        List<SessionService.StoredMessage> before = sessions.getFullHistoryRecords(session.getId());
        long keptMessageId = before.get(0).messageId();
        long assistantMessageId = before.get(1).messageId();
        ToolResultArchiveEntity keptArchive = archive(
                session.getId(), assistantMessageId, "kept-tool-use", "1");
        archiveRepository.saveAndFlush(keptArchive);
        ToolResultArchiveEntity futureArchive = archive(
                session.getId(), before.get(3).messageId(), "future-tool-use", "2");
        archiveRepository.saveAndFlush(futureArchive);

        SessionToolAttemptEntity terminal = terminalAttempt(
                session.getId(), assistantMessageId, session.getHistoryEpoch());
        terminal = attemptRepository.saveAndFlush(terminal);
        SessionToolAttemptResolutionAuditEntity audit = audit(
                terminal, session.getUserId());
        audit = auditRepository.save(audit);

        SessionSummaryEntity keptSummary = summary(session.getId(), 0, 1, "kept");
        keptSummary = summaryRepository.saveAndFlush(keptSummary);

        SessionCompactionCheckpointEntity selected = checkpoint(
                session.getId(), "selected", 2L, "{corrupt");
        selected.setSummaryIdWatermark(keptSummary.getId());
        checkpointRepository.saveAndFlush(selected);
        SessionSummaryEntity futureSummary = summary(session.getId(), 0, 3, "future");
        futureSummary = summaryRepository.saveAndFlush(futureSummary);
        SessionCompactionCheckpointEntity futureCheckpoint = checkpoint(
                session.getId(), "future-checkpoint", 3L, null);
        futureCheckpoint.setSummaryIdWatermark(futureSummary.getId());
        checkpointRepository.saveAndFlush(futureCheckpoint);

        String oldRef = new HistoryRefCodec().format(
                new HistoryRefCodec.MessageRef(4L, keptMessageId, 0),
                scope(session, 4L));
        SessionEntity restored = compaction.restoreFromCheckpoint(session.getId(), selected.getId());
        entityManager.flush();
        entityManager.clear();

        List<SessionService.StoredMessage> remaining = sessions.getFullHistoryRecords(session.getId());
        assertThat(remaining).hasSize(3);
        assertThat(remaining.get(0).messageId()).isEqualTo(keptMessageId);
        assertThat(remaining).extracting(SessionService.StoredMessage::writeBatchId)
                .containsOnly(batch);
        assertThat(restored.getHistoryEpoch()).isEqualTo(5L);
        assertThat(sessionRepository.findById(session.getId()).orElseThrow().isRestorePreparing())
                .isFalse();
        assertThat(new ContextRuntimeSnapshotCodec(objectMapper).decodeOrEmpty(
                sessionRepository.findById(session.getId()).orElseThrow().getContextRuntimeJson()))
                .isEqualTo(ContextRuntimeSnapshot.empty());
        assertThat(attemptRepository.count()).isZero();
        assertThat(auditRepository.findById(audit.getId())).isPresent();
        assertThat(archiveRepository.findById(keptArchive.getId())).isPresent();
        assertThat(archiveRepository.findById(futureArchive.getId())).isEmpty();
        assertThat(summaryRepository.findById(keptSummary.getId())).isPresent();
        assertThat(summaryRepository.findById(futureSummary.getId())).isEmpty();
        assertThat(checkpointRepository.findById(selected.getId())).isPresent();
        assertThat(checkpointRepository.findById("future-checkpoint")).isEmpty();
        assertThatThrownBy(() -> new HistoryRefCodec().parse(oldRef, scope(session, 5L)))
                .isInstanceOf(HistoryProtocolException.class)
                .extracting(error -> ((HistoryProtocolException) error).getCode())
                .isEqualTo("HISTORY_STALE");
    }

    @Test
    void restoreRejectsSessionClaimUnresolvedAttemptAndInboxBeforePruning() {
        SessionService sessions = new SessionService(
                sessionRepository, messageRepository, agentRepository,
                new SessionMessageStoreProperties(), objectMapper, transactionManager);
        CompactionService compaction = compactionService(sessions);
        SessionEntity session = new SessionEntity();
        session.setId(UUID.randomUUID().toString());
        session.setUserId(74L);
        session.setAgentId(1L);
        session.setRuntimeStatus("idle");
        session.setActiveLoopId(UUID.randomUUID().toString());
        session.setLoopOwnerInstanceId("test-instance");
        session.setLoopLeaseUntil(Instant.now().plusSeconds(60));
        sessionRepository.saveAndFlush(session);
        sessions.appendMessages(session.getId(), List.of(
                append(Message.assistant("intent"), UUID.randomUUID().toString(), 0),
                append(Message.user("future"), UUID.randomUUID().toString(), 0)));
        SessionCompactionCheckpointEntity checkpoint = checkpoint(
                session.getId(), "reject-checkpoint", 0L, null);
        checkpointRepository.saveAndFlush(checkpoint);

        assertThatThrownBy(() -> compaction.restoreFromCheckpoint(session.getId(), checkpoint.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active claim");

        SessionEntity unlocked = sessionRepository.findById(session.getId()).orElseThrow();
        unlocked.setActiveLoopId(null);
        unlocked.setLoopOwnerInstanceId(null);
        unlocked.setLoopLeaseUntil(null);
        sessionRepository.saveAndFlush(unlocked);
        long assistantId = sessions.getFullHistoryRecords(session.getId()).get(0).messageId();
        SessionToolAttemptEntity unresolved = unresolvedAttempt(session.getId(), assistantId);
        unresolved = attemptRepository.saveAndFlush(unresolved);
        assertThatThrownBy(() -> compaction.restoreFromCheckpoint(session.getId(), checkpoint.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unresolved");

        attemptRepository.delete(unresolved);
        attemptRepository.flush();
        SessionMessageInboxEntity inbox = new SessionMessageInboxEntity();
        inbox.setInboxId(UUID.randomUUID());
        inbox.setSessionId(session.getId());
        inbox.setUserId(session.getUserId());
        inbox.setMessageJson("{\"role\":\"user\",\"content\":\"queued\"}");
        inboxRepository.saveAndFlush(inbox);
        assertThatThrownBy(() -> compaction.restoreFromCheckpoint(session.getId(), checkpoint.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inbox");

        assertThat(messageRepository.countBySessionId(session.getId())).isEqualTo(2L);
        assertThat(sessionRepository.findById(session.getId()).orElseThrow().getHistoryEpoch())
                .isZero();
    }

    @Test
    void branchCopiesOnlyCheckpointTranscriptAndRuntimeRefsNotLiveWorkQueuesOrAudit() {
        SessionService sessions = new SessionService(
                sessionRepository, messageRepository, agentRepository,
                new SessionMessageStoreProperties(), objectMapper, transactionManager);
        sessions.setSessionSummaryRepository(summaryRepository);
        CompactionService compaction = compactionService(sessions);
        SessionEntity source = new SessionEntity();
        source.setId(UUID.randomUUID().toString());
        source.setUserId(75L);
        source.setAgentId(1L);
        source.setTitle("branch-source");
        source.setRuntimeStatus("idle");
        source.setSkillOverridesJson("[\"current-skill\"]");
        sessionRepository.saveAndFlush(source);
        sessions.appendMessages(source.getId(), List.of(
                append(Message.user("keep-user"), UUID.randomUUID().toString(), 0),
                append(Message.assistant("keep-assistant"), UUID.randomUUID().toString(), 0),
                append(Message.user("future"), UUID.randomUUID().toString(), 0)));
        long assistantId = sessions.getFullHistoryRecords(source.getId()).get(1).messageId();

        SessionToolAttemptEntity attempt = attemptRepository.saveAndFlush(
                terminalAttempt(source.getId(), assistantId, 0L));
        SessionToolAttemptResolutionAuditEntity audit = auditRepository.save(
                audit(attempt, source.getUserId()));
        SessionMessageInboxEntity inbox = new SessionMessageInboxEntity();
        inbox.setInboxId(UUID.randomUUID());
        inbox.setSessionId(source.getId());
        inbox.setUserId(source.getUserId());
        inbox.setMessageJson("{\"role\":\"user\",\"content\":\"queued\"}");
        inboxRepository.saveAndFlush(inbox);
        ToolResultArchiveEntity archive = archive(
                source.getId(), assistantId, "source-tool-use", "f");
        archiveRepository.saveAndFlush(archive);

        ContextRuntimeSnapshotCodec codec = new ContextRuntimeSnapshotCodec(objectMapper);
        SessionCompactionCheckpointEntity checkpoint = checkpoint(
                source.getId(), "branch-checkpoint", 1L,
                codec.encode(new ContextRuntimeSnapshot(
                        1, Map.of("tool:removed", "old-hash"), List.of())));
        checkpointRepository.saveAndFlush(checkpoint);

        SessionEntity branch = compaction.createBranchFromCheckpoint(
                source.getId(), checkpoint.getId(), "safe branch");
        entityManager.flush();
        entityManager.clear();

        List<SessionService.StoredMessage> branchMessages =
                sessions.getFullHistoryRecords(branch.getId());
        assertThat(branchMessages).hasSize(2);
        assertThat(branchMessages).extracting(stored -> stored.message().getContent().toString())
                .allMatch(content -> !content.contains("future"));
        assertThat(branch.getParentSessionId()).isEqualTo(source.getId());
        assertThat(branch.getSkillOverridesJson()).isEqualTo("[\"current-skill\"]");
        assertThat(codec.decodeOrEmpty(sessionRepository.findById(branch.getId()).orElseThrow()
                .getContextRuntimeJson())).isEqualTo(ContextRuntimeSnapshot.empty());
        assertThat(inboxRepository.countBySessionId(branch.getId())).isZero();
        assertThat(attemptRepository.findBySessionIdAndStateIn(
                branch.getId(), List.of("RESOLVED_UNKNOWN"))).isEmpty();
        assertThat(auditRepository.findBySessionIdOrderByCreatedAtAsc(
                branch.getId(), PageRequest.of(0, 10))).isEmpty();
        assertThat(archiveRepository.findBySessionId(branch.getId())).isEmpty();

        assertThat(inboxRepository.countBySessionId(source.getId())).isOne();
        assertThat(attemptRepository.findById(attempt.getId())).isPresent();
        assertThat(auditRepository.findById(audit.getId())).isPresent();
        assertThat(archiveRepository.findById(archive.getId())).isPresent();
    }

    @Test
    void sameEndSeqCheckpoints_branchAndRestoreUseTheSelectedSidecarTimeline() {
        SessionService sessions = new SessionService(
                sessionRepository, messageRepository, agentRepository,
                new SessionMessageStoreProperties(), objectMapper, transactionManager);
        sessions.setSessionSummaryRepository(summaryRepository);
        CompactionService compaction = compactionService(sessions);
        SessionEntity source = new SessionEntity();
        source.setId(UUID.randomUUID().toString());
        source.setUserId(76L);
        source.setAgentId(1L);
        source.setTitle("same-frontier-source");
        source.setRuntimeStatus("idle");
        sessionRepository.saveAndFlush(source);
        sessions.appendMessages(source.getId(), List.of(
                append(Message.user("zero"), UUID.randomUUID().toString(), 0),
                append(Message.assistant("one"), UUID.randomUUID().toString(), 0),
                append(Message.user("two"), UUID.randomUUID().toString(), 0)));

        SessionSummaryEntity first = summaryRepository.saveAndFlush(
                summary(source.getId(), 0, 1, "first summary"));
        SessionCompactionCheckpointEntity selected = checkpoint(
                source.getId(), "same-frontier-selected", 2L, null);
        selected.setSummaryIdWatermark(first.getId());
        checkpointRepository.saveAndFlush(selected);
        selected.setSidecarWatermark(checkpointRepository
                .findSidecarWatermarkById(selected.getId()).orElseThrow());
        assertThat(selected.getSidecarWatermark()).isNotNull();

        SessionSummaryEntity later = summaryRepository.saveAndFlush(
                summary(source.getId(), 0, 1, "later summary"));
        summaryRepository.markSuperseded(first.getId(), later.getId());
        SessionCompactionCheckpointEntity laterCheckpoint = checkpoint(
                source.getId(), "same-frontier-later", 2L, null);
        laterCheckpoint.setSummaryIdWatermark(later.getId());
        checkpointRepository.saveAndFlush(laterCheckpoint);
        laterCheckpoint.setSidecarWatermark(checkpointRepository
                .findSidecarWatermarkById(laterCheckpoint.getId()).orElseThrow());
        assertThat(laterCheckpoint.getSidecarWatermark())
                .isGreaterThan(selected.getSidecarWatermark());

        SessionEntity branch = compaction.createBranchFromCheckpoint(
                source.getId(), selected.getId(), "first-timeline-branch");
        List<SessionSummaryEntity> branchSummaries =
                summaryRepository.findBySessionIdOrderByStartSeqAsc(branch.getId());
        assertThat(branchSummaries).singleElement()
                .satisfies(summary -> {
                    assertThat(summary.getSummaryText()).isEqualTo("first summary");
                    assertThat(summary.getSupersededBy()).isNull();
                });

        compaction.restoreFromCheckpoint(source.getId(), selected.getId());
        entityManager.flush();
        entityManager.clear();

        assertThat(summaryRepository.findBySessionIdOrderByStartSeqAsc(source.getId()))
                .singleElement()
                .satisfies(summary -> {
                    assertThat(summary.getId()).isEqualTo(first.getId());
                    assertThat(summary.getSummaryText()).isEqualTo("first summary");
                    assertThat(summary.getSupersededBy()).isNull();
                });
        assertThat(checkpointRepository.findById(selected.getId())).isPresent();
        assertThat(checkpointRepository.findById(laterCheckpoint.getId())).isEmpty();
    }

    @Test
    void unorderedLegacyCheckpoint_failsClosedBeforeBranchOrRestoreWrites() {
        SessionService sessions = new SessionService(
                sessionRepository, messageRepository, agentRepository,
                new SessionMessageStoreProperties(), objectMapper, transactionManager);
        sessions.setSessionSummaryRepository(summaryRepository);
        CompactionService compaction = compactionService(sessions);
        SessionEntity source = new SessionEntity();
        source.setId(UUID.randomUUID().toString());
        source.setUserId(77L);
        source.setAgentId(1L);
        source.setRuntimeStatus("idle");
        sessionRepository.saveAndFlush(source);
        sessions.appendMessages(source.getId(), List.of(
                append(Message.user("keep"), UUID.randomUUID().toString(), 0),
                append(Message.user("future"), UUID.randomUUID().toString(), 0)));
        SessionCompactionCheckpointEntity legacy = checkpoint(
                source.getId(), "legacy-unordered", 0L, null);
        checkpointRepository.saveAndFlush(legacy);
        jdbcTemplate.update("""
                UPDATE t_session_compaction_checkpoint
                SET sidecar_watermark = NULL, summary_id_watermark = NULL
                WHERE id = ?
                """, legacy.getId());
        entityManager.clear();

        assertThatThrownBy(() -> compaction.createBranchFromCheckpoint(
                source.getId(), legacy.getId(), "must-not-exist"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("predates deterministic sidecar recovery");
        assertThatThrownBy(() -> compaction.restoreFromCheckpoint(
                source.getId(), legacy.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("predates deterministic sidecar recovery");

        assertThat(sessionRepository.count()).isOne();
        assertThat(messageRepository.countBySessionId(source.getId())).isEqualTo(2L);
        assertThat(checkpointRepository.findById(legacy.getId())).isPresent();
        assertThat(sessionRepository.findById(source.getId()).orElseThrow().getHistoryEpoch())
                .isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void restoreFailure_rollsBackMessageSummaryAndCheckpointTimelinePruning() {
        SessionService sessions = new SessionService(
                sessionRepository, messageRepository, agentRepository,
                new SessionMessageStoreProperties(), objectMapper, transactionManager);
        sessions.setSessionSummaryRepository(summaryRepository);
        CompactionService compaction = compactionService(sessions);
        SessionEntity source = new SessionEntity();
        source.setId(UUID.randomUUID().toString());
        source.setUserId(78L);
        source.setAgentId(1L);
        source.setRuntimeStatus("idle");
        source.setHistoryEpoch(Long.MAX_VALUE);
        sessionRepository.saveAndFlush(source);
        sessions.appendMessages(source.getId(), List.of(
                append(Message.user("keep"), UUID.randomUUID().toString(), 0),
                append(Message.user("future"), UUID.randomUUID().toString(), 0)));

        SessionSummaryEntity first = summaryRepository.saveAndFlush(
                summary(source.getId(), 0, 0, "rollback first"));
        SessionCompactionCheckpointEntity selected = checkpoint(
                source.getId(), "rollback-selected", 0L, null);
        selected.setSummaryIdWatermark(first.getId());
        checkpointRepository.saveAndFlush(selected);
        SessionSummaryEntity later = summaryRepository.saveAndFlush(
                summary(source.getId(), 0, 0, "rollback later"));
        summaryRepository.markSuperseded(first.getId(), later.getId());
        SessionCompactionCheckpointEntity laterCheckpoint = checkpoint(
                source.getId(), "rollback-later", 0L, null);
        laterCheckpoint.setSummaryIdWatermark(later.getId());
        checkpointRepository.saveAndFlush(laterCheckpoint);

        assertThatThrownBy(() -> compaction.restoreFromCheckpoint(
                source.getId(), selected.getId()))
                .isInstanceOf(ArithmeticException.class);
        entityManager.clear();

        assertThat(messageRepository.countBySessionId(source.getId())).isEqualTo(2L);
        assertThat(checkpointRepository.findById(laterCheckpoint.getId())).isPresent();
        assertThat(summaryRepository.findById(later.getId())).isPresent();
        assertThat(summaryRepository.findById(first.getId()).orElseThrow().getSupersededBy())
                .isEqualTo(later.getId());
        assertThat(sessionRepository.findById(source.getId()).orElseThrow().getHistoryEpoch())
                .isEqualTo(Long.MAX_VALUE);
    }

    private CompactionService compactionService(SessionService sessions) {
        CompactionService service = new CompactionService(
                sessionRepository,
                mock(CompactionEventRepository.class),
                checkpointRepository,
                sessions,
                new LightCompactStrategy(),
                new FullCompactStrategy(),
                mock(com.skillforge.core.llm.LlmProviderFactory.class),
                mock(com.skillforge.server.config.LlmProperties.class),
                mock(com.skillforge.core.engine.ChatEventBroadcaster.class),
                transactionManager);
        service.setSessionSummaryRepository(summaryRepository);
        service.setSessionMessageRepository(messageRepository);
        service.setRangeModelEnabled(true);
        SessionContextRuntimeAuthorityResolver authority =
                mock(SessionContextRuntimeAuthorityResolver.class);
        when(authority.resolve(any(SessionEntity.class))).thenReturn(new ContextRuntimeAuthority(
                ToolCatalog.fromAuthorizedSchemas(List.of(), objectMapper), SessionSkillView.EMPTY));
        service.setCheckpointRuntimeDependencies(
                inboxRepository, attemptRepository,
                new ContextRuntimeCheckpointService(new ContextRuntimeSnapshotCodec(objectMapper)),
                authority);
        return service;
    }

    private static SessionService.AppendMessage append(Message message, String batch, int ordinal) {
        return new SessionService.AppendMessage(
                message, SessionService.MSG_TYPE_NORMAL, SessionService.MESSAGE_TYPE_NORMAL,
                null, null, Map.of(), null, batch, ordinal);
    }

    private static SessionCompactionCheckpointEntity checkpoint(
            String sessionId, String id, long endSeq, String runtime) {
        SessionCompactionCheckpointEntity checkpoint = new SessionCompactionCheckpointEntity();
        checkpoint.setId(id);
        checkpoint.setSessionId(sessionId);
        checkpoint.setBoundarySeqNo(endSeq);
        checkpoint.setPostRangeEndSeqNo(endSeq);
        checkpoint.setReason("manual");
        checkpoint.setRuntimeSnapshotJson(runtime);
        checkpoint.setSummaryIdWatermark(0L);
        return checkpoint;
    }

    private static SessionSummaryEntity summary(
            String sessionId, long start, long end, String text) {
        SessionSummaryEntity summary = new SessionSummaryEntity();
        summary.setSessionId(sessionId);
        summary.setStartSeq(start);
        summary.setEndSeq(end);
        summary.setSummaryText(text);
        summary.setLevel("full");
        summary.setSource("user-manual");
        return summary;
    }

    private static ToolResultArchiveEntity archive(
            String sessionId, long messageId, String toolUseId, String hashChar) {
        ToolResultArchiveEntity archive = new ToolResultArchiveEntity();
        archive.setArchiveId(UUID.randomUUID().toString());
        archive.setSessionId(sessionId);
        archive.setSessionMessageId(messageId);
        archive.setBlockIndex(0);
        archive.setToolUseId(toolUseId);
        archive.setToolName("Read");
        archive.setOriginalChars(12);
        archive.setPreview(toolUseId);
        archive.setContent(toolUseId);
        archive.setCanonicalPayloadHash(hashChar.repeat(64));
        archive.setPayloadHashVersion((short) 1);
        return archive;
    }

    private static SessionToolAttemptEntity terminalAttempt(
            String sessionId, long assistantMessageId, long epoch) {
        SessionToolAttemptEntity attempt = new SessionToolAttemptEntity();
        attempt.setSessionId(sessionId);
        attempt.setStepId(UUID.randomUUID());
        attempt.setHistoryEpoch(epoch);
        attempt.setOriginLoopId(UUID.randomUUID().toString());
        attempt.setOriginFence(1L);
        attempt.setAssistantMessageId(assistantMessageId);
        attempt.setAssistantPayloadHash("a".repeat(64));
        attempt.setPreIntentMaxMessageId(-1L);
        attempt.setPreIntentMaxSeq(-1L);
        attempt.setManifestJson("{\"schemaVersion\":1,\"calls\":[],\"replaySafety\":\"UNKNOWN\"}");
        attempt.setManifestHash("b".repeat(64));
        attempt.setReplaySafety("UNKNOWN");
        attempt.setState("RESOLVED_UNKNOWN");
        attempt.setExecutionLoopId(UUID.randomUUID().toString());
        attempt.setExecutionFence(2L);
        attempt.setExecutionOwnerInstanceId("test-instance");
        attempt.setExecutionGeneration(1L);
        attempt.setClaimRequestId(UUID.randomUUID());
        attempt.setClaimedAt(Instant.now());
        attempt.setExecutionLeaseUntil(Instant.now().plusSeconds(60));
        attempt.setResultBatchId(UUID.randomUUID());
        attempt.setResultExecutionGeneration(1L);
        attempt.setResultExecutionFence(2L);
        return attempt;
    }

    private static SessionToolAttemptEntity unresolvedAttempt(
            String sessionId, long assistantMessageId) {
        SessionToolAttemptEntity attempt = new SessionToolAttemptEntity();
        attempt.setSessionId(sessionId);
        attempt.setStepId(UUID.randomUUID());
        attempt.setHistoryEpoch(0L);
        attempt.setOriginLoopId(UUID.randomUUID().toString());
        attempt.setOriginFence(0L);
        attempt.setAssistantMessageId(assistantMessageId);
        attempt.setAssistantPayloadHash("d".repeat(64));
        attempt.setPreIntentMaxMessageId(-1L);
        attempt.setPreIntentMaxSeq(-1L);
        attempt.setManifestJson("{\"schemaVersion\":1,\"calls\":[],\"replaySafety\":\"UNKNOWN\"}");
        attempt.setManifestHash("e".repeat(64));
        attempt.setReplaySafety("UNKNOWN");
        attempt.setState("INTENT_COMMITTED");
        return attempt;
    }

    private static SessionToolAttemptResolutionAuditEntity audit(
            SessionToolAttemptEntity attempt, long actorId) {
        SessionToolAttemptResolutionAuditEntity audit =
                new SessionToolAttemptResolutionAuditEntity();
        audit.setResolutionRequestId(UUID.randomUUID());
        audit.setSessionId(attempt.getSessionId());
        audit.setAttemptId(attempt.getId());
        audit.setStepId(attempt.getStepId());
        audit.setHistoryEpoch(attempt.getHistoryEpoch());
        audit.setExecutionGeneration(attempt.getExecutionGeneration());
        audit.setExecutionFence(attempt.getExecutionFence());
        audit.setActorId(actorId);
        audit.setActorAuthority("OWNER");
        audit.setReasonHash("c".repeat(64));
        audit.setAction("CONTINUE_CURRENT_TIMELINE");
        audit.setInboxDispositionsJson("[]");
        audit.setResultBatchId(attempt.getResultBatchId());
        audit.setOutcomeState("RESOLVED_UNKNOWN");
        return audit;
    }

    private static CurrentSessionHistoryScope scope(SessionEntity session, long epoch) {
        com.skillforge.core.skill.SkillContext context = new com.skillforge.core.skill.SkillContext();
        context.setSessionId(session.getId());
        context.setUserId(session.getUserId());
        return CurrentSessionHistoryScope.from(context, epoch, -1L, -1L);
    }
}
