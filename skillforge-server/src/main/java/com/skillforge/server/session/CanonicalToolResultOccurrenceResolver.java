package com.skillforge.server.session;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Pure exact-identity resolver shared by History and the provider-visible model view. */
@Component
public final class CanonicalToolResultOccurrenceResolver {

    private final ArchivePayloadIdentityHasher identityHasher;

    public CanonicalToolResultOccurrenceResolver(ArchivePayloadIdentityHasher identityHasher) {
        this.identityHasher = Objects.requireNonNull(identityHasher, "identityHasher");
    }

    public Resolution resolve(
            List<RawOccurrence> rawOccurrences,
            List<ArchiveCandidate> archiveCandidates) {
        Objects.requireNonNull(rawOccurrences, "rawOccurrences");
        Objects.requireNonNull(archiveCandidates, "archiveCandidates");

        Map<OccurrenceKey, List<RawOccurrence>> rawByKey = groupRaw(rawOccurrences);
        Map<OccurrenceKey, List<ArchiveCandidate>> archivesByKey = groupArchives(
                archiveCandidates);
        Map<OccurrenceKey, CanonicalOccurrence> resolved = new LinkedHashMap<>();
        for (Map.Entry<OccurrenceKey, List<RawOccurrence>> entry : rawByKey.entrySet()) {
            List<RawOccurrence> rawCandidates = entry.getValue();
            List<ArchiveCandidate> archives = archivesByKey.get(entry.getKey());
            if (rawCandidates.size() != 1 || archives == null || archives.size() != 1) continue;
            RawOccurrence raw = rawCandidates.get(0);
            ArchiveCandidate archive = archives.get(0);
            if (matches(raw, archive)) {
                resolved.put(entry.getKey(), new CanonicalOccurrence(raw, archive));
            }
        }
        return new Resolution(resolved);
    }

    private boolean matches(RawOccurrence raw, ArchiveCandidate archive) {
        if (raw.content() == null
                || archive.archiveId() == null || archive.archiveId().isBlank()
                || archive.payloadHashVersion() == null
                || archive.payloadHashVersion() != ArchivePayloadIdentityHasher.VERSION
                || !raw.toolUseId().equals(archive.toolUseId())
                || !raw.content().equals(archive.content())) {
            return false;
        }
        return identityHasher.hash(
                        raw.toolUseId(), raw.content(), raw.error(), raw.errorType())
                .equals(archive.canonicalPayloadHash());
    }

    private static Map<OccurrenceKey, List<RawOccurrence>> groupRaw(
            List<RawOccurrence> values) {
        Map<OccurrenceKey, List<RawOccurrence>> grouped = new LinkedHashMap<>();
        for (RawOccurrence value : values) {
            if (value != null) grouped.computeIfAbsent(
                    value.key(), ignored -> new ArrayList<>()).add(value);
        }
        return grouped;
    }

    private static Map<OccurrenceKey, List<ArchiveCandidate>> groupArchives(
            List<ArchiveCandidate> values) {
        Map<OccurrenceKey, List<ArchiveCandidate>> grouped = new LinkedHashMap<>();
        for (ArchiveCandidate value : values) {
            if (value != null) grouped.computeIfAbsent(
                    value.key(), ignored -> new ArrayList<>()).add(value);
        }
        return grouped;
    }

    public record OccurrenceKey(long sessionMessageId, int blockIndex) {
        public OccurrenceKey {
            if (sessionMessageId <= 0L || blockIndex < 0) {
                throw new IllegalArgumentException("Invalid Tool result occurrence key");
            }
        }
    }

    public record RawOccurrence(
            OccurrenceKey key,
            String toolUseId,
            String content,
            boolean error,
            String errorType) {
        public RawOccurrence {
            Objects.requireNonNull(key, "key");
            if (toolUseId == null || toolUseId.isBlank()) {
                throw new IllegalArgumentException("toolUseId must not be blank");
            }
        }
    }

    public record ArchiveCandidate(
            OccurrenceKey key,
            String archiveId,
            String toolUseId,
            String content,
            String canonicalPayloadHash,
            Short payloadHashVersion) {
        public ArchiveCandidate {
            Objects.requireNonNull(key, "key");
        }
    }

    public record CanonicalOccurrence(RawOccurrence raw, ArchiveCandidate archive) {
        public CanonicalOccurrence {
            Objects.requireNonNull(raw, "raw");
            Objects.requireNonNull(archive, "archive");
            if (!raw.key().equals(archive.key())) {
                throw new IllegalArgumentException("Canonical occurrence key mismatch");
            }
        }
    }

    public record Resolution(Map<OccurrenceKey, CanonicalOccurrence> byOccurrence) {
        public Resolution {
            byOccurrence = Map.copyOf(byOccurrence);
        }
    }
}
