package com.skillforge.server.session;

import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.server.entity.ToolResultArchiveEntity;
import com.skillforge.server.repository.ToolResultArchiveRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CanonicalToolResultModelViewTest {

    private static final String SESSION_ID = "00000000-0000-0000-0000-000000000601";

    private final ToolResultArchiveRepository repository =
            mock(ToolResultArchiveRepository.class);
    private final ArchivePayloadIdentityHasher hasher = new ArchivePayloadIdentityHasher();
    private CanonicalToolResultModelView modelView;

    @BeforeEach
    void setUp() {
        modelView = new CanonicalToolResultModelView(
                repository, new CanonicalToolResultOccurrenceResolver(hasher));
    }

    @Test
    void project_exactOccurrenceUsesArchivePreviewWithoutMutatingPersistedMessage() {
        String content = "large exact body";
        Message persisted = Message.toolResult("tool-1", content, true, "FAILED");
        when(repository.findBySessionIdAndSessionMessageIdIn(SESSION_ID, List.of(9L)))
                .thenReturn(List.of(
                archive(9L, 0, "tool-1", content, true, "FAILED")));

        List<Message> projected = modelView.project(SESSION_ID, List.of(
                new CanonicalToolResultModelView.MessageOccurrence(9L, persisted)));

        assertThat(projected).hasSize(1);
        assertThat(projected.get(0)).isNotSameAs(persisted);
        assertThat(projected.get(0).getTextContent())
                .contains("[Tool result archived]", "archive_id: archive-1", content);
        assertThat(persisted.getTextContent()).isEqualTo(content);
    }

    @Test
    void project_wrongOccurrenceAndLegacyArchiveKeepExactRawMessage() {
        Message persisted = Message.toolResult("tool-1", "exact", false);
        ToolResultArchiveEntity wrongOccurrence = archive(
                8L, 0, "tool-1", "exact", false, null);
        ToolResultArchiveEntity legacy = archive(
                9L, 0, "tool-1", "exact", false, null);
        legacy.setSessionMessageId(null);
        legacy.setBlockIndex(null);
        legacy.setCanonicalPayloadHash(null);
        legacy.setPayloadHashVersion(null);
        when(repository.findBySessionIdAndSessionMessageIdIn(SESSION_ID, List.of(9L))).thenReturn(
                List.of(wrongOccurrence, legacy));

        List<Message> projected = modelView.project(SESSION_ID, List.of(
                new CanonicalToolResultModelView.MessageOccurrence(9L, persisted)));

        assertThat(projected).containsExactly(persisted);
    }

    private ToolResultArchiveEntity archive(
            long messageId,
            int blockIndex,
            String toolUseId,
            String content,
            boolean error,
            String errorType) {
        ToolResultArchiveEntity row = new ToolResultArchiveEntity();
        row.setArchiveId("archive-1");
        row.setSessionId(SESSION_ID);
        row.setSessionMessageId(messageId);
        row.setBlockIndex(blockIndex);
        row.setToolUseId(toolUseId);
        row.setContent(content);
        row.setCanonicalPayloadHash(hasher.hash(toolUseId, content, error, errorType));
        row.setPayloadHashVersion(ArchivePayloadIdentityHasher.VERSION);
        return row;
    }
}
