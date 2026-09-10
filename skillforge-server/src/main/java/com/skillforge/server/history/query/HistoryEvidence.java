package com.skillforge.server.history.query;

import java.time.Instant;
import java.util.Objects;

/** Fully authorized logical evidence block; storage-only identities never leave this package. */
record HistoryEvidence(
        String ref,
        EvidenceClass evidenceClass,
        Kind kind,
        String role,
        long logicalSeq,
        String toolName,
        String toolUseId,
        boolean compacted,
        String summaryState,
        String content,
        String authorizedContentHash,
        Instant createdAt,
        int sourceOrder,
        HistoryCanonicalArchiveResolver.OccurrenceKey rawOccurrence) {

    HistoryEvidence {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(evidenceClass, "evidenceClass");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(authorizedContentHash, "authorizedContentHash");
        createdAt = createdAt == null ? Instant.EPOCH : createdAt;
    }

    enum EvidenceClass { ORIGINAL, DERIVED_SUMMARY }

    enum Kind { TEXT, TOOL_USE, TOOL_RESULT, SUMMARY }
}
