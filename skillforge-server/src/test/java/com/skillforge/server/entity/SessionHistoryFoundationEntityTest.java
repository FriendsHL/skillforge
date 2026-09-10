package com.skillforge.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Session History foundation entity mappings")
class SessionHistoryFoundationEntityTest {

    @Test
    @DisplayName("session maps epoch separately from mutable loop generation and restore state")
    void session_mapsTimelineAndLoopStateSeparately() {
        SessionEntity session = new SessionEntity();
        assertThat(session.getHistoryEpoch()).isZero();
        assertThat(session.getLoopFence()).isZero();
        assertThat(session.isRestorePreparing()).isFalse();

        session.setHistoryEpoch(7);
        session.setActiveLoopId("origin-loop");
        session.setLoopFence(11);
        session.setLoopOwnerInstanceId("instance-a");
        session.setLoopLeaseUntil(Instant.parse("2026-09-02T04:00:00Z"));
        session.setRestorePreparing(true);

        assertThat(session.getHistoryEpoch()).isEqualTo(7);
        assertThat(session.getActiveLoopId()).isEqualTo("origin-loop");
        assertThat(session.getLoopFence()).isEqualTo(11);
        assertThat(session.getLoopOwnerInstanceId()).isEqualTo("instance-a");
        assertThat(session.getLoopLeaseUntil()).isEqualTo(Instant.parse("2026-09-02T04:00:00Z"));
        assertThat(session.isRestorePreparing()).isTrue();
    }

    @Test
    @DisplayName("checkpoint runtime snapshot and occurrence archive identity have dedicated columns")
    void checkpointAndArchive_mapNewStorageShape() throws Exception {
        SessionCompactionCheckpointEntity checkpoint = new SessionCompactionCheckpointEntity();
        checkpoint.setRuntimeSnapshotJson("{\"schema\":1}");
        assertThat(checkpoint.getRuntimeSnapshotJson()).isEqualTo("{\"schema\":1}");

        ToolResultArchiveEntity archive = new ToolResultArchiveEntity();
        archive.setBlockIndex(2);
        archive.setCanonicalPayloadHash("a".repeat(64));
        archive.setPayloadHashVersion((short) 1);
        assertThat(archive.getBlockIndex()).isEqualTo(2);
        assertThat(archive.getCanonicalPayloadHash()).isEqualTo("a".repeat(64));
        assertThat(archive.getPayloadHashVersion()).isEqualTo((short) 1);

        Table table = ToolResultArchiveEntity.class.getAnnotation(Table.class);
        assertThat(Arrays.stream(table.uniqueConstraints())
                .flatMap(constraint -> Arrays.stream(constraint.columnNames()).sorted())
                .toList()).doesNotContain("tool_use_id");
        assertColumn(ToolResultArchiveEntity.class, "blockIndex", "block_index");
        assertColumn(ToolResultArchiveEntity.class, "canonicalPayloadHash", "canonical_payload_hash");
        assertColumn(ToolResultArchiveEntity.class, "payloadHashVersion", "payload_hash_version");
    }

    @Test
    @DisplayName("inbox stores exact message JSON and database acceptance order")
    void inbox_mapsExactMessageAndAcceptanceOrder() {
        SessionMessageInboxEntity inbox = new SessionMessageInboxEntity();
        UUID inboxId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-09-02T04:05:00Z");
        inbox.setId(9L);
        inbox.setInboxId(inboxId);
        inbox.setSessionId("session-1");
        inbox.setUserId(3L);
        inbox.setMessageJson("{\"role\":\"user\",\"content\":\"exact\"}");
        inbox.setCreatedAt(createdAt);

        assertThat(inbox.getId()).isEqualTo(9L);
        assertThat(inbox.getInboxId()).isEqualTo(inboxId);
        assertThat(inbox.getMessageJson()).contains("exact");
        assertThat(inbox.getCreatedAt()).isEqualTo(createdAt);
    }

    @Test
    @DisplayName("attempt keeps immutable origin separate from mutable execution and post-action claim")
    void attempt_separatesOriginExecutionAndPostAction() {
        SessionToolAttemptEntity attempt = new SessionToolAttemptEntity();
        UUID stepId = UUID.randomUUID();
        UUID claimRequestId = UUID.randomUUID();
        UUID resolutionRequestId = UUID.randomUUID();
        UUID resultBatchId = UUID.randomUUID();

        attempt.setSessionId("session-1");
        attempt.setStepId(stepId);
        attempt.setHistoryEpoch(4L);
        attempt.setOriginLoopId("origin-loop");
        attempt.setOriginFence(5L);
        attempt.setAssistantMessageId(101L);
        attempt.setAssistantPayloadHash("b".repeat(64));
        attempt.setPreIntentMaxMessageId(100L);
        attempt.setPreIntentMaxSeq(88L);
        attempt.setManifestJson("[]");
        attempt.setManifestHash("c".repeat(64));
        attempt.setReplaySafety("UNKNOWN");
        attempt.setState("INTENT_COMMITTED");

        attempt.setExecutionLoopId("execution-loop");
        attempt.setExecutionFence(8L);
        attempt.setExecutionOwnerInstanceId("instance-a");
        attempt.setExecutionGeneration(2L);
        attempt.setClaimRequestId(claimRequestId);
        attempt.setPostActionState("PENDING");
        attempt.setPostActionResolutionRequestId(resolutionRequestId);
        attempt.setPostActionResultBatchId(resultBatchId);
        attempt.setPostActionKind("CONTINUE_CURRENT_TIMELINE");

        assertThat(attempt.getOriginLoopId()).isEqualTo("origin-loop");
        assertThat(attempt.getOriginFence()).isEqualTo(5L);
        assertThat(attempt.getExecutionLoopId()).isEqualTo("execution-loop");
        assertThat(attempt.getExecutionFence()).isEqualTo(8L);
        assertThat(attempt.getExecutionGeneration()).isEqualTo(2L);
        assertThat(attempt.getPostActionResolutionRequestId()).isEqualTo(resolutionRequestId);
        assertThat(attempt.getPostActionResultBatchId()).isEqualTo(resultBatchId);
    }

    @Test
    @DisplayName("resolution audit references attempt by immutable scalar without a prunable attempt relation")
    void resolutionAudit_attemptIdHasNoAttemptForeignKeyMapping() throws Exception {
        Field attemptId = SessionToolAttemptResolutionAuditEntity.class.getDeclaredField("attemptId");
        assertThat(attemptId.getAnnotation(ManyToOne.class)).isNull();
        assertThat(attemptId.getAnnotation(OneToOne.class)).isNull();
        assertThat(attemptId.getType()).isEqualTo(Long.class);

        SessionToolAttemptResolutionAuditEntity audit = new SessionToolAttemptResolutionAuditEntity();
        audit.setAttemptId(55L);
        audit.setResolutionRequestId(UUID.randomUUID());
        audit.setActorId(7L);
        audit.setActorAuthority("OWNER");
        audit.setReasonHash("d".repeat(64));
        audit.setAction("CONTINUE_CURRENT_TIMELINE");
        audit.setOutcomeState("RESOLVED_UNKNOWN");
        assertThat(audit.getAttemptId()).isEqualTo(55L);
    }

    private static void assertColumn(Class<?> type, String fieldName, String columnName) throws Exception {
        Column column = type.getDeclaredField(fieldName).getAnnotation(Column.class);
        assertThat(column).isNotNull();
        assertThat(column.name()).isEqualTo(columnName);
    }
}
