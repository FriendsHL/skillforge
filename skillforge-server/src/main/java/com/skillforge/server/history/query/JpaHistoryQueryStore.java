package com.skillforge.server.history.query;

import com.skillforge.server.history.CurrentSessionHistoryScope;
import com.skillforge.server.history.HistoryCursorCodec;
import com.skillforge.server.history.HistoryProtocolException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/** PostgreSQL-shaped owner/epoch-scoped queries for a frozen History snapshot. */
@Repository
public class JpaHistoryQueryStore implements HistoryQueryStore {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public ScopeState requireCurrentScope(CurrentSessionHistoryScope scope) {
        List<Object[]> rows = entityManager.createQuery("""
                        SELECT s.historyEpoch, s.messagesJson
                        FROM SessionEntity s
                        WHERE s.id = :sessionId AND s.userId = :userId
                        """, Object[].class)
                .setParameter("sessionId", scope.sessionId())
                .setParameter("userId", scope.userId())
                .setMaxResults(1)
                .getResultList();
        if (rows.isEmpty()) throw unavailable();
        long epoch = ((Number) rows.get(0)[0]).longValue();
        if (epoch != scope.historyEpoch()) {
            throw new HistoryProtocolException("HISTORY_STALE", "History scope is stale");
        }
        String legacyJson = (String) rows.get(0)[1];
        long rowCount = entityManager.createQuery("""
                        SELECT COUNT(m.id)
                        FROM SessionMessageEntity m, SessionEntity s
                        WHERE m.sessionId = s.id
                          AND s.id = :sessionId AND s.userId = :userId
                          AND s.historyEpoch = :epoch
                        """, Long.class)
                .setParameter("sessionId", scope.sessionId())
                .setParameter("userId", scope.userId())
                .setParameter("epoch", scope.historyEpoch())
                .getSingleResult();
        boolean hasLegacy = legacyJson != null && !legacyJson.isBlank()
                && !"[]".equals(legacyJson.trim());
        return new ScopeState(epoch, hasLegacy && rowCount == 0);
    }

    @Override
    public HistoryCursorCodec.SnapshotCutoff captureCutoff(CurrentSessionHistoryScope scope) {
        requireCurrentScope(scope);
        if (scope.preIntentMaxMessageId() < 0) {
            return new HistoryCursorCodec.SnapshotCutoff(0, 0, 0, 0, -1, -1);
        }
        Object[] messageMax = entityManager.createQuery("""
                        SELECT MAX(m.id), MAX(m.seqNo)
                        FROM SessionMessageEntity m, SessionEntity s
                        WHERE m.sessionId = s.id
                          AND s.id = :sessionId AND s.userId = :userId
                          AND s.historyEpoch = :epoch
                          AND m.id <= :maxMessageId AND m.seqNo <= :maxMessageSeq
                          AND m.prunedAt IS NULL
                        """, Object[].class)
                .setParameter("sessionId", scope.sessionId())
                .setParameter("userId", scope.userId())
                .setParameter("epoch", scope.historyEpoch())
                .setParameter("maxMessageId", scope.preIntentMaxMessageId())
                .setParameter("maxMessageSeq", scope.preIntentMaxSeq())
                .getSingleResult();
        long maxMessageId = numberOrZero(messageMax[0]);
        long maxMessageSeq = numberOrZero(messageMax[1]);
        if (maxMessageId == 0) {
            return new HistoryCursorCodec.SnapshotCutoff(
                    0, 0, 0, 0,
                    scope.preIntentMaxMessageId(), scope.preIntentMaxSeq());
        }

        long maxSummaryId = numberOrZero(entityManager.createQuery("""
                        SELECT MAX(summary.id)
                        FROM SessionSummaryEntity summary, SessionEntity s
                        WHERE summary.sessionId = s.id
                          AND s.id = :sessionId AND s.userId = :userId
                          AND s.historyEpoch = :epoch
                          AND summary.endSeq <= :maxMessageSeq
                        """, Long.class)
                .setParameter("sessionId", scope.sessionId())
                .setParameter("userId", scope.userId())
                .setParameter("epoch", scope.historyEpoch())
                .setParameter("maxMessageSeq", maxMessageSeq)
                .getSingleResult());
        long maxArchiveRowId = numberOrZero(entityManager.createQuery("""
                        SELECT MAX(archive.id)
                        FROM ToolResultArchiveEntity archive, SessionMessageEntity m, SessionEntity s
                        WHERE archive.sessionId = s.id
                          AND archive.sessionMessageId = m.id AND m.sessionId = s.id
                          AND s.id = :sessionId AND s.userId = :userId
                          AND s.historyEpoch = :epoch
                          AND m.id <= :maxMessageId AND m.seqNo <= :maxMessageSeq
                        """, Long.class)
                .setParameter("sessionId", scope.sessionId())
                .setParameter("userId", scope.userId())
                .setParameter("epoch", scope.historyEpoch())
                .setParameter("maxMessageId", maxMessageId)
                .setParameter("maxMessageSeq", maxMessageSeq)
                .getSingleResult());
        return new HistoryCursorCodec.SnapshotCutoff(
                maxMessageId, maxMessageSeq, maxSummaryId, maxArchiveRowId,
                scope.preIntentMaxMessageId(), scope.preIntentMaxSeq());
    }

    @Override
    public List<MessageRow> loadMessages(
            CurrentSessionHistoryScope scope,
            HistoryCursorCodec.SnapshotCutoff cutoff,
            int hardLimit) {
        return loadMessages(scope, cutoff, Selection.range(null, null, false), hardLimit);
    }

    @Override
    public List<MessageRow> loadMessages(
            CurrentSessionHistoryScope scope, HistoryCursorCodec.SnapshotCutoff cutoff,
            Selection selection, int hardLimit) {
        boolean refs = selection.messageIds() != null;
        boolean messageRefs = refs && !selection.messageIds().isEmpty();
        boolean archiveRefs = refs && !selection.archiveIds().isEmpty();
        if (refs && !messageRefs && !archiveRefs) return List.of();
        String restriction = "";
        if (selection.seqFrom() != null) restriction += " AND m.seqNo >= :seqFrom";
        if (selection.seqTo() != null) restriction += " AND m.seqNo <= :seqTo";
        if (refs) {
            restriction += " AND (" + (messageRefs ? "m.id IN :messageIds" : "1 = 0")
                    + (archiveRefs ? " OR m.id IN (SELECT a.sessionMessageId FROM ToolResultArchiveEntity a"
                    + " WHERE a.sessionId = :sessionId AND a.id <= :maxArchiveRowId"
                    + " AND a.archiveId IN :archiveIds)" : "") + ")";
        }
        TypedQuery<Object[]> query = entityManager.createQuery("""
                SELECT m.id, m.seqNo, m.role, m.msgType, m.contentJson, m.messageType,
                       m.controlId, m.compactedBySummaryId, m.createdAt
                FROM SessionMessageEntity m, SessionEntity s
                WHERE m.sessionId = s.id
                  AND s.id = :sessionId AND s.userId = :userId
                  AND s.historyEpoch = :epoch
                  AND m.id <= :maxMessageId AND m.seqNo <= :maxMessageSeq
                  AND m.prunedAt IS NULL
                """ + restriction + " ORDER BY m.seqNo "
                + (selection.ascending() ? "ASC" : "DESC"), Object[].class);
        bindScope(query, scope);
        if (selection.seqFrom() != null) query.setParameter("seqFrom", selection.seqFrom());
        if (selection.seqTo() != null) query.setParameter("seqTo", selection.seqTo());
        if (messageRefs) query.setParameter("messageIds", selection.messageIds());
        if (archiveRefs) {
            query.setParameter("archiveIds", selection.archiveIds());
            query.setParameter("maxArchiveRowId", cutoff.maxArchiveRowId());
        }
        query.setParameter("maxMessageId", cutoff.maxMessageId());
        query.setParameter("maxMessageSeq", cutoff.maxMessageSeq());
        return query.setMaxResults(checkedLimit(hardLimit)).getResultList().stream()
                .map(row -> new MessageRow(
                        number(row[0]), number(row[1]), (String) row[2], (String) row[3],
                        (String) row[4], (String) row[5], (String) row[6],
                        row[7] == null ? null : number(row[7]), (Instant) row[8]))
                .toList();
    }

    @Override
    public List<SummaryRow> loadSummaries(
            CurrentSessionHistoryScope scope,
            HistoryCursorCodec.SnapshotCutoff cutoff,
            int hardLimit) {
        return loadSummaries(scope, cutoff, Selection.range(null, null, false), hardLimit);
    }

    @Override
    public List<SummaryRow> loadSummaries(
            CurrentSessionHistoryScope scope, HistoryCursorCodec.SnapshotCutoff cutoff,
            Selection selection, int hardLimit) {
        if (selection.summaryIds() != null && selection.summaryIds().isEmpty()) return List.of();
        String restriction = "";
        if (selection.seqFrom() != null) restriction += " AND summary.endSeq >= :seqFrom";
        if (selection.seqTo() != null) restriction += " AND summary.endSeq <= :seqTo";
        if (selection.summaryIds() != null) restriction += " AND summary.id IN :summaryIds";
        TypedQuery<Object[]> query = entityManager.createQuery("""
                SELECT summary.id, summary.startSeq, summary.endSeq, summary.summaryText,
                       summary.supersededBy, summary.createdAt
                FROM SessionSummaryEntity summary, SessionEntity s
                WHERE summary.sessionId = s.id
                  AND s.id = :sessionId AND s.userId = :userId
                  AND s.historyEpoch = :epoch
                  AND summary.id <= :maxSummaryId
                  AND summary.endSeq <= :maxMessageSeq
                """ + restriction + " ORDER BY summary.endSeq "
                + (selection.ascending() ? "ASC" : "DESC")
                + ", summary.createdAt " + (selection.ascending() ? "ASC" : "DESC")
                + ", summary.id " + (selection.ascending() ? "ASC" : "DESC"), Object[].class);
        bindScope(query, scope);
        if (selection.seqFrom() != null) query.setParameter("seqFrom", selection.seqFrom());
        if (selection.seqTo() != null) query.setParameter("seqTo", selection.seqTo());
        if (selection.summaryIds() != null) query.setParameter("summaryIds", selection.summaryIds());
        query.setParameter("maxSummaryId", cutoff.maxSummaryId());
        query.setParameter("maxMessageSeq", cutoff.maxMessageSeq());
        return query.setMaxResults(checkedLimit(hardLimit)).getResultList().stream()
                .map(row -> new SummaryRow(
                        number(row[0]), number(row[1]), number(row[2]), (String) row[3],
                        row[4] == null ? null : number(row[4]), (Instant) row[5]))
                .toList();
    }

    @Override
    public List<ArchiveRow> loadArchives(
            CurrentSessionHistoryScope scope,
            HistoryCursorCodec.SnapshotCutoff cutoff,
            int hardLimit) {
        return loadArchivesForMessages(scope, cutoff, null, hardLimit);
    }

    @Override
    public List<ArchiveRow> loadArchivesForMessages(
            CurrentSessionHistoryScope scope, HistoryCursorCodec.SnapshotCutoff cutoff,
            List<Long> messageIds, int hardLimit) {
        if (messageIds != null && messageIds.isEmpty()) return List.of();
        TypedQuery<Object[]> query = entityManager.createQuery("""
                SELECT archive.id, archive.archiveId, archive.sessionMessageId,
                       archive.blockIndex, archive.toolUseId, archive.toolName,
                       archive.content, archive.canonicalPayloadHash,
                       archive.payloadHashVersion, archive.createdAt
                FROM ToolResultArchiveEntity archive, SessionMessageEntity m, SessionEntity s
                WHERE archive.sessionId = s.id
                  AND archive.sessionMessageId = m.id AND m.sessionId = s.id
                  AND s.id = :sessionId AND s.userId = :userId
                  AND s.historyEpoch = :epoch
                  AND archive.id <= :maxArchiveRowId
                  AND m.id <= :maxMessageId AND m.seqNo <= :maxMessageSeq
                  AND archive.blockIndex IS NOT NULL
                  AND archive.canonicalPayloadHash IS NOT NULL
                  AND archive.payloadHashVersion IS NOT NULL
                """ + (messageIds == null ? "" : " AND m.id IN :messageIds")
                + " ORDER BY archive.id ASC", Object[].class);
        bindScope(query, scope);
        if (messageIds != null) query.setParameter("messageIds", messageIds);
        query.setParameter("maxArchiveRowId", cutoff.maxArchiveRowId());
        query.setParameter("maxMessageId", cutoff.maxMessageId());
        query.setParameter("maxMessageSeq", cutoff.maxMessageSeq());
        return query.setMaxResults(checkedLimit(hardLimit)).getResultList().stream()
                .map(row -> new ArchiveRow(
                        number(row[0]), (String) row[1], number(row[2]),
                        ((Number) row[3]).intValue(), (String) row[4], (String) row[5],
                        (String) row[6], (String) row[7], (Short) row[8], (Instant) row[9]))
                .toList();
    }

    @Override
    public List<MessageRow> loadPairingContext(
            CurrentSessionHistoryScope scope, HistoryCursorCodec.SnapshotCutoff cutoff,
            List<Long> messageIds, int hardLimit) {
        if (messageIds.isEmpty()) return List.of();
        // Only the immediately preceding matching occurrence can pair with a selected result.
        // A preceding result consumes the intent; reused IDs must never search past it.
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT DISTINCT previous.id, previous.seq_no, previous.role, previous.msg_type,
                       previous.content_json, previous.message_type, previous.control_id,
                       previous.compacted_by_summary_id, previous.created_at
                FROM t_session_message target
                JOIN t_session s ON s.id = target.session_id
                CROSS JOIN LATERAL jsonb_array_elements(
                    CASE WHEN jsonb_typeof(CAST(target.content_json AS jsonb)) = 'array'
                         THEN CAST(target.content_json AS jsonb) ELSE CAST('[]' AS jsonb) END) result
                CROSS JOIN LATERAL (
                    SELECT prior.id, prior.seq_no, prior.role, prior.msg_type, prior.content_json,
                           prior.message_type, prior.control_id, prior.compacted_by_summary_id,
                           prior.created_at FROM t_session_message prior
                    WHERE prior.session_id = s.id AND prior.pruned_at IS NULL
                      AND prior.id <= :maxMessageId AND prior.seq_no < target.seq_no
                      AND prior.msg_type = 'NORMAL' AND prior.message_type = 'normal'
                      AND prior.control_id IS NULL
                      AND EXISTS (
                        SELECT 1 FROM jsonb_array_elements(
                            CASE WHEN jsonb_typeof(CAST(prior.content_json AS jsonb)) = 'array'
                                 THEN CAST(prior.content_json AS jsonb) ELSE CAST('[]' AS jsonb) END) block
                        WHERE (prior.role = 'assistant' AND block->>'type' = 'tool_use'
                                AND jsonb_typeof(block->'name') = 'string'
                                AND jsonb_typeof(block->'id') = 'string'
                                AND jsonb_exists(block, 'input')
                                AND block->>'id' = result->>'tool_use_id')
                           OR (prior.role = 'user' AND block->>'type' = 'tool_result'
                                AND jsonb_exists(block, 'content')
                                AND jsonb_typeof(block->'tool_use_id') = 'string'
                                AND block->>'tool_use_id' = result->>'tool_use_id'))
                    ORDER BY prior.seq_no DESC LIMIT 1
                ) previous
                WHERE s.id = :sessionId AND s.user_id = :userId AND s.history_epoch = :epoch
                  AND target.id IN :messageIds AND target.id <= :maxMessageId
                  AND target.seq_no <= :maxMessageSeq AND target.pruned_at IS NULL
                  AND target.role = 'user' AND target.msg_type = 'NORMAL'
                  AND target.message_type = 'normal' AND target.control_id IS NULL
                  AND result->>'type' = 'tool_result'
                  AND jsonb_typeof(result->'tool_use_id') = 'string'
                  AND jsonb_exists(result, 'content')
                """)
                .setParameter("sessionId", scope.sessionId())
                .setParameter("userId", scope.userId())
                .setParameter("epoch", scope.historyEpoch())
                .setParameter("maxMessageId", cutoff.maxMessageId())
                .setParameter("maxMessageSeq", cutoff.maxMessageSeq())
                .setParameter("messageIds", messageIds)
                .setMaxResults(checkedLimit(hardLimit)).getResultList();
        return rows.stream().map(row -> new MessageRow(
                number(row[0]), number(row[1]), (String) row[2], (String) row[3], (String) row[4],
                (String) row[5], (String) row[6], row[7] == null ? null : number(row[7]),
                nativeInstant(row[8]))).toList();
    }

    private static Instant nativeInstant(Object value) {
        if (value instanceof Instant instant) return instant;
        if (value instanceof java.time.OffsetDateTime dateTime) return dateTime.toInstant();
        return ((java.sql.Timestamp) value).toInstant();
    }

    private static void bindScope(TypedQuery<?> query, CurrentSessionHistoryScope scope) {
        query.setParameter("sessionId", scope.sessionId());
        query.setParameter("userId", scope.userId());
        query.setParameter("epoch", scope.historyEpoch());
    }

    private static int checkedLimit(int hardLimit) {
        if (hardLimit < 1 || hardLimit == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("History hard limit is invalid");
        }
        return hardLimit + 1;
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    private static long numberOrZero(Object value) {
        return value == null ? 0L : number(value);
    }

    private static HistoryProtocolException unavailable() {
        return new HistoryProtocolException(
                "HISTORY_UNAVAILABLE", "History is unavailable for the current Session");
    }
}
