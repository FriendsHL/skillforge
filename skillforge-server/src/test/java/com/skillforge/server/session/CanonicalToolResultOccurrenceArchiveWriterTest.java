package com.skillforge.server.session;

import com.skillforge.core.engine.durability.PersistedBlockOccurrence;
import com.skillforge.server.entity.ToolResultArchiveEntity;
import com.skillforge.server.repository.ToolResultArchiveRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CanonicalToolResultOccurrenceArchiveWriterTest {

    @Test
    void prepare_aggregateExceedsBudget_archivesLargestOccurrenceOnly() {
        ToolResultArchiveRepository repository = mock(ToolResultArchiveRepository.class);
        ArchivePayloadIdentityHasher hasher = new ArchivePayloadIdentityHasher();
        CanonicalToolResultOccurrenceArchiveWriter writer =
                new CanonicalToolResultOccurrenceArchiveWriter(repository, hasher);
        PersistedBlockOccurrence largest = occurrence(11L, 0, "tool-large", "a".repeat(150_000));
        PersistedBlockOccurrence smaller = occurrence(11L, 1, "tool-small", "b".repeat(100_000));

        when(repository.findBySessionIdAndSessionMessageIdAndBlockIndex("session-1", 11L, 0))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(archive(largest, hasher)));
        when(repository.findBySessionIdAndSessionMessageIdAndBlockIndex("session-1", 11L, 1))
                .thenReturn(Optional.empty());

        writer.prepare(List.of(smaller, largest));

        ArgumentCaptor<Integer> blockIndex = ArgumentCaptor.forClass(Integer.class);
        verify(repository).insertOccurrenceIgnoreConflict(
                anyString(), eq("session-1"), eq(11L), blockIndex.capture(),
                eq("tool-large"), eq(null), eq(150_000), anyString(),
                eq(largest.content()), anyString(), anyShort(), any());
        assertThat(blockIndex.getValue()).isZero();
        verify(repository, never()).insertOccurrenceIgnoreConflict(
                anyString(), eq("session-1"), eq(11L), eq(1),
                eq("tool-small"), eq(null), anyInt(), anyString(),
                anyString(), anyString(), anyShort(), any());
    }

    @Test
    void prepare_aggregateWithinBudget_doesNotCreateArchive() {
        ToolResultArchiveRepository repository = mock(ToolResultArchiveRepository.class);
        CanonicalToolResultOccurrenceArchiveWriter writer =
                new CanonicalToolResultOccurrenceArchiveWriter(
                        repository, new ArchivePayloadIdentityHasher());
        PersistedBlockOccurrence first = occurrence(21L, 0, "tool-a", "a".repeat(100_000));
        PersistedBlockOccurrence second = occurrence(21L, 1, "tool-b", "b".repeat(100_000));
        when(repository.findBySessionIdAndSessionMessageIdAndBlockIndex(
                anyString(), anyLong(), anyInt())).thenReturn(Optional.empty());

        writer.prepare(List.of(first, second));

        verify(repository, never()).insertOccurrenceIgnoreConflict(
                anyString(), anyString(), anyLong(), anyInt(), anyString(), any(),
                anyInt(), anyString(), anyString(), anyString(), anyShort(), any());
    }

    private static PersistedBlockOccurrence occurrence(
            long messageId, int blockIndex, String toolUseId, String content) {
        return new PersistedBlockOccurrence(
                messageId, 7L, "session-1", UUID.fromString(
                        "11111111-1111-4111-8111-111111111111"),
                0, blockIndex, toolUseId, content, false, null, "trace-1");
    }

    private static ToolResultArchiveEntity archive(
            PersistedBlockOccurrence occurrence, ArchivePayloadIdentityHasher hasher) {
        ToolResultArchiveEntity row = new ToolResultArchiveEntity();
        row.setArchiveId("22222222-2222-4222-8222-222222222222");
        row.setSessionId(occurrence.sessionId());
        row.setSessionMessageId(occurrence.messageId());
        row.setBlockIndex(occurrence.blockIndex());
        row.setToolUseId(occurrence.toolUseId());
        row.setOriginalChars(occurrence.content().length());
        row.setPreview(occurrence.content().substring(0, 2_048));
        row.setContent(occurrence.content());
        row.setCanonicalPayloadHash(hasher.hash(occurrence));
        row.setPayloadHashVersion(ArchivePayloadIdentityHasher.VERSION);
        return row;
    }
}
