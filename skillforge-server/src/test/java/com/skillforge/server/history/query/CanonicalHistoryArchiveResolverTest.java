package com.skillforge.server.history.query;

import com.skillforge.server.session.ArchivePayloadIdentityHasher;
import com.skillforge.server.session.CanonicalToolResultOccurrenceResolver;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalHistoryArchiveResolverTest {

    private final ArchivePayloadIdentityHasher hasher = new ArchivePayloadIdentityHasher();
    private final CanonicalHistoryArchiveResolver resolver =
            new CanonicalHistoryArchiveResolver(
                    new CanonicalToolResultOccurrenceResolver(hasher));

    @Test
    void exactOccurrenceAndPayload_resolvesCanonicalArchive() {
        var raw = raw(10L, 2, "tool-1", "exact", true, "FAILED");
        var archive = archive(raw, "archive-1", hasher.hash(
                raw.toolUseId(), raw.content(), raw.error(), raw.errorType()));

        var resolution = resolver.resolve(List.of(raw), List.of(archive));

        assertThat(resolution.byOccurrence()).containsOnlyKeys(raw.key());
        assertThat(resolution.byOccurrence().get(raw.key()))
                .extracting(
                        HistoryCanonicalArchiveResolver.CanonicalArchive::archiveId,
                        HistoryCanonicalArchiveResolver.CanonicalArchive::content,
                        HistoryCanonicalArchiveResolver.CanonicalArchive::error,
                        HistoryCanonicalArchiveResolver.CanonicalArchive::errorType)
                .containsExactly("archive-1", "exact", true, "FAILED");
    }

    @Test
    void mismatchedOrAmbiguousArchive_fallsBackToRaw() {
        var raw = raw(10L, 2, "tool-1", "exact", false, null);
        String exactHash = hasher.hash(
                raw.toolUseId(), raw.content(), raw.error(), raw.errorType());

        assertThat(resolver.resolve(List.of(raw), List.of(
                archive(raw, "wrong-hash", "0".repeat(64)))).byOccurrence()).isEmpty();
        assertThat(resolver.resolve(List.of(raw), List.of(
                archive(raw, "wrong-content", exactHash, "changed", (short) 1)))
                .byOccurrence()).isEmpty();
        assertThat(resolver.resolve(List.of(raw), List.of(
                archive(raw, "old-version", exactHash, "exact", (short) 2)))
                .byOccurrence()).isEmpty();
        assertThat(resolver.resolve(List.of(raw), List.of(
                archive(raw, "duplicate-1", exactHash),
                archive(raw, "duplicate-2", exactHash))).byOccurrence()).isEmpty();
    }

    @Test
    void sameToolUseIdDifferentOccurrence_doesNotReuseArchive() {
        var raw = raw(11L, 0, "tool-1", "new", false, null);
        var other = raw(10L, 0, "tool-1", "old", false, null);
        var archive = archive(other, "archive-old", hasher.hash(
                other.toolUseId(), other.content(), other.error(), other.errorType()));

        assertThat(resolver.resolve(List.of(raw), List.of(archive)).byOccurrence()).isEmpty();
    }

    private static HistoryCanonicalArchiveResolver.RawToolResultOccurrence raw(
            long messageId,
            int blockIndex,
            String toolUseId,
            String content,
            boolean error,
            String errorType) {
        return new HistoryCanonicalArchiveResolver.RawToolResultOccurrence(
                new HistoryCanonicalArchiveResolver.OccurrenceKey(messageId, blockIndex),
                7L, "USER", toolUseId, "FileRead", content, error, errorType,
                false, Instant.parse("2026-09-04T00:00:00Z"), blockIndex);
    }

    private static HistoryQueryStore.ArchiveRow archive(
            HistoryCanonicalArchiveResolver.RawToolResultOccurrence raw,
            String archiveId,
            String hash) {
        return archive(raw, archiveId, hash, raw.content(), (short) 1);
    }

    private static HistoryQueryStore.ArchiveRow archive(
            HistoryCanonicalArchiveResolver.RawToolResultOccurrence raw,
            String archiveId,
            String hash,
            String content,
            short version) {
        return new HistoryQueryStore.ArchiveRow(
                1L, archiveId, raw.key().sessionMessageId(), raw.key().blockIndex(),
                raw.toolUseId(), null, content, hash, version,
                Instant.parse("2026-09-04T00:00:01Z"));
    }
}
