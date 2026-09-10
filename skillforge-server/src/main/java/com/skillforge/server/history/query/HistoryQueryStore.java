package com.skillforge.server.history.query;

import com.skillforge.server.history.CurrentSessionHistoryScope;
import com.skillforge.server.history.HistoryCursorCodec;

import java.time.Instant;
import java.util.List;

/** Owner-scoped, read-only persistence boundary used by the History query service. */
public interface HistoryQueryStore {

    ScopeState requireCurrentScope(CurrentSessionHistoryScope scope);

    HistoryCursorCodec.SnapshotCutoff captureCutoff(CurrentSessionHistoryScope scope);

    List<MessageRow> loadMessages(
            CurrentSessionHistoryScope scope,
            HistoryCursorCodec.SnapshotCutoff cutoff,
            int hardLimit);

    List<SummaryRow> loadSummaries(
            CurrentSessionHistoryScope scope,
            HistoryCursorCodec.SnapshotCutoff cutoff,
            int hardLimit);

    List<ArchiveRow> loadArchives(
            CurrentSessionHistoryScope scope,
            HistoryCursorCodec.SnapshotCutoff cutoff,
            int hardLimit);

    List<MessageRow> loadMessages(
            CurrentSessionHistoryScope scope, HistoryCursorCodec.SnapshotCutoff cutoff,
            Selection selection, int hardLimit);

    List<SummaryRow> loadSummaries(
            CurrentSessionHistoryScope scope, HistoryCursorCodec.SnapshotCutoff cutoff,
            Selection selection, int hardLimit);

    List<ArchiveRow> loadArchivesForMessages(
            CurrentSessionHistoryScope scope, HistoryCursorCodec.SnapshotCutoff cutoff,
            List<Long> messageIds, int hardLimit);

    /** Prior occurrence rows are pairing context only, never additional evidence. */
    List<MessageRow> loadPairingContext(
            CurrentSessionHistoryScope scope, HistoryCursorCodec.SnapshotCutoff cutoff,
            List<Long> messageIds, int hardLimit);

    /** Null ID lists mean all IDs; non-null lists form a refs selector (empty means none). */
    record Selection(Long seqFrom, Long seqTo, List<Long> messageIds,
                     List<Long> summaryIds, List<String> archiveIds, boolean ascending) {
        static Selection range(Long from, Long to, boolean ascending) {
            return new Selection(from, to, null, null, null, ascending);
        }
    }

    record ScopeState(long historyEpoch, boolean legacyOnly) { }

    record MessageRow(
            long id,
            long seqNo,
            String role,
            String msgType,
            String contentJson,
            String messageType,
            String controlId,
            Long compactedBySummaryId,
            Instant createdAt) { }

    record SummaryRow(
            long id,
            long startSeq,
            long endSeq,
            String summaryText,
            Long supersededBy,
            Instant createdAt) { }

    record ArchiveRow(
            long rowId,
            String archiveId,
            long sessionMessageId,
            int blockIndex,
            String toolUseId,
            String toolName,
            String content,
            String canonicalPayloadHash,
            Short payloadHashVersion,
            Instant createdAt) { }
}
