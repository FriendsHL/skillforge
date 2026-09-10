package com.skillforge.server.history.query;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Pure read contract for occurrence-owned canonical archives.
 *
 * <p>Batch 5 does not infer canonicality from archive presence. Until the occurrence resolver is
 * available, the default implementation returns no replacement and History exposes exact raw
 * occurrences. Batch 6 can replace this bean without changing History query semantics.
 */
public interface HistoryCanonicalArchiveResolver {

    Resolution resolve(
            List<RawToolResultOccurrence> rawOccurrences,
            List<HistoryQueryStore.ArchiveRow> archiveRows);

    record OccurrenceKey(long sessionMessageId, int blockIndex) { }

    record RawToolResultOccurrence(
            OccurrenceKey key,
            long logicalSeq,
            String role,
            String toolUseId,
            String toolName,
            String content,
            boolean error,
            String errorType,
            boolean compacted,
            Instant createdAt,
            int sourceOrder) { }

    record CanonicalArchive(
            String archiveId,
            OccurrenceKey occurrence,
            long logicalSeq,
            String toolUseId,
            String toolName,
            String content,
            boolean error,
            String errorType,
            boolean compacted,
            Instant createdAt,
            int sourceOrder) { }

    record Resolution(Map<OccurrenceKey, CanonicalArchive> byOccurrence) {
        public Resolution {
            byOccurrence = Map.copyOf(byOccurrence);
        }

        public static Resolution rawOnly() {
            return new Resolution(Map.of());
        }
    }
}
