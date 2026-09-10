package com.skillforge.server.history.query;

import com.skillforge.server.session.CanonicalToolResultOccurrenceResolver;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Resolves only occurrence-owned archives whose exact scalar identity matches the raw block. */
@Component
public final class CanonicalHistoryArchiveResolver implements HistoryCanonicalArchiveResolver {

    private final CanonicalToolResultOccurrenceResolver occurrenceResolver;

    public CanonicalHistoryArchiveResolver(
            CanonicalToolResultOccurrenceResolver occurrenceResolver) {
        this.occurrenceResolver = Objects.requireNonNull(
                occurrenceResolver, "occurrenceResolver");
    }

    @Override
    public Resolution resolve(
            List<RawToolResultOccurrence> rawOccurrences,
            List<HistoryQueryStore.ArchiveRow> archiveRows) {
        Objects.requireNonNull(rawOccurrences, "rawOccurrences");
        Objects.requireNonNull(archiveRows, "archiveRows");

        Map<CanonicalToolResultOccurrenceResolver.OccurrenceKey, RawToolResultOccurrence>
                rawMetadata = new LinkedHashMap<>();
        List<CanonicalToolResultOccurrenceResolver.RawOccurrence> raw = rawOccurrences.stream()
                .filter(Objects::nonNull)
                .map(value -> {
                    var key = occurrenceKey(value.key());
                    rawMetadata.putIfAbsent(key, value);
                    return new CanonicalToolResultOccurrenceResolver.RawOccurrence(
                            key, value.toolUseId(), value.content(), value.error(),
                            value.errorType());
                })
                .toList();
        List<CanonicalToolResultOccurrenceResolver.ArchiveCandidate> archives = archiveRows.stream()
                .filter(Objects::nonNull)
                .map(value -> new CanonicalToolResultOccurrenceResolver.ArchiveCandidate(
                        new CanonicalToolResultOccurrenceResolver.OccurrenceKey(
                                value.sessionMessageId(), value.blockIndex()),
                        value.archiveId(), value.toolUseId(), value.content(),
                        value.canonicalPayloadHash(), value.payloadHashVersion()))
                .toList();
        CanonicalToolResultOccurrenceResolver.Resolution canonical =
                occurrenceResolver.resolve(raw, archives);
        Map<OccurrenceKey, CanonicalArchive> resolved = new LinkedHashMap<>();
        for (var value : canonical.byOccurrence().values()) {
            RawToolResultOccurrence metadata = rawMetadata.get(value.raw().key());
            if (metadata == null) continue;
            resolved.put(metadata.key(), new CanonicalArchive(
                    value.archive().archiveId(), metadata.key(), metadata.logicalSeq(),
                    metadata.toolUseId(), metadata.toolName(), value.archive().content(),
                    metadata.error(), metadata.errorType(), metadata.compacted(),
                    metadata.createdAt(), metadata.sourceOrder()));
        }
        return new Resolution(resolved);
    }

    private static CanonicalToolResultOccurrenceResolver.OccurrenceKey occurrenceKey(
            OccurrenceKey key) {
        return new CanonicalToolResultOccurrenceResolver.OccurrenceKey(
                key.sessionMessageId(), key.blockIndex());
    }
}
