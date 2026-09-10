package com.skillforge.server.session;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.server.entity.SessionMessageEntity;
import com.skillforge.server.entity.ToolResultArchiveEntity;
import com.skillforge.server.repository.SessionMessageRepository;
import com.skillforge.server.repository.ToolResultArchiveRepository;
import com.skillforge.server.session.persistence.PersistedMessageCodec;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

import java.util.List;

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

class LegacyToolResultArchiveBackfillServiceTest {

    @Test
    void backfillSession_uniqueExactRawOccurrence_claimsLegacyArchive() {
        Fixture fixture = new Fixture();
        ToolResultArchiveEntity legacy = fixture.legacy(9L, "tool-1", "exact");
        SessionMessageEntity exact = fixture.message(101L, "tool-1", "exact", true, "FAILED");
        SessionMessageEntity different = fixture.message(102L, "tool-1", "different", false, null);
        when(fixture.archiveRepository
                .findBySessionIdAndSessionMessageIdIsNullOrderByIdAsc("session-1"))
                .thenReturn(List.of(legacy));
        when(fixture.messageRepository.findBySessionIdAndPrunedAtIsNullOrderBySeqNoAsc(
                eq("session-1"), any())).thenReturn(new PageImpl<>(List.of(exact, different)));
        when(fixture.archiveRepository.findBySessionIdAndSessionMessageIdAndBlockIndex(
                "session-1", 101L, 0)).thenReturn(java.util.Optional.empty());
        when(fixture.archiveRepository.claimLegacyOccurrence(
                eq(9L), eq("session-1"), eq(101L), eq(0), any(),
                eq(ArchivePayloadIdentityHasher.VERSION))).thenReturn(1);

        LegacyToolResultArchiveBackfillService.BackfillReport report =
                fixture.service.backfillSession("session-1");

        assertThat(report).isEqualTo(
                new LegacyToolResultArchiveBackfillService.BackfillReport(1, 0, 0, 0));
        verify(fixture.archiveRepository).claimLegacyOccurrence(
                eq(9L), eq("session-1"), eq(101L), eq(0),
                eq(fixture.hasher.hash("tool-1", "exact", true, "FAILED")),
                eq(ArchivePayloadIdentityHasher.VERSION));
    }

    @Test
    void backfillSession_twoExactOccurrences_leavesLegacyArchiveUnclaimed() {
        Fixture fixture = new Fixture();
        ToolResultArchiveEntity legacy = fixture.legacy(10L, "tool-2", "same");
        SessionMessageEntity first = fixture.message(201L, "tool-2", "same", false, null);
        SessionMessageEntity second = fixture.message(202L, "tool-2", "same", false, null);
        when(fixture.archiveRepository
                .findBySessionIdAndSessionMessageIdIsNullOrderByIdAsc("session-1"))
                .thenReturn(List.of(legacy));
        when(fixture.messageRepository.findBySessionIdAndPrunedAtIsNullOrderBySeqNoAsc(
                eq("session-1"), any())).thenReturn(new PageImpl<>(List.of(first, second)));

        LegacyToolResultArchiveBackfillService.BackfillReport report =
                fixture.service.backfillSession("session-1");

        assertThat(report).isEqualTo(
                new LegacyToolResultArchiveBackfillService.BackfillReport(0, 1, 0, 0));
        verify(fixture.archiveRepository, never()).claimLegacyOccurrence(
                anyLong(), anyString(), anyLong(), anyInt(), anyString(), anyShort());
    }

    @Test
    void backfillSession_archiveLengthMismatch_failsClosedAsInvalid() {
        Fixture fixture = new Fixture();
        ToolResultArchiveEntity legacy = fixture.legacy(11L, "tool-3", "payload");
        legacy.setOriginalChars(1);
        when(fixture.archiveRepository
                .findBySessionIdAndSessionMessageIdIsNullOrderByIdAsc("session-1"))
                .thenReturn(List.of(legacy));
        when(fixture.messageRepository.findBySessionIdAndPrunedAtIsNullOrderBySeqNoAsc(
                eq("session-1"), any())).thenReturn(new PageImpl<>(List.of(
                        fixture.message(301L, "tool-3", "payload", false, null))));

        LegacyToolResultArchiveBackfillService.BackfillReport report =
                fixture.service.backfillSession("session-1");

        assertThat(report).isEqualTo(
                new LegacyToolResultArchiveBackfillService.BackfillReport(0, 0, 0, 1));
        verify(fixture.archiveRepository, never()).claimLegacyOccurrence(
                anyLong(), anyString(), anyLong(), anyInt(), anyString(), anyShort());
    }

    private static final class Fixture {
        private final SessionMessageRepository messageRepository =
                mock(SessionMessageRepository.class);
        private final ToolResultArchiveRepository archiveRepository =
                mock(ToolResultArchiveRepository.class);
        private final PersistedMessageCodec codec = new PersistedMessageCodec(
                JsonMapper.builder()
                        .addModule(new JavaTimeModule())
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                        .build());
        private final ArchivePayloadIdentityHasher hasher = new ArchivePayloadIdentityHasher();
        private final LegacyToolResultArchiveBackfillService service =
                new LegacyToolResultArchiveBackfillService(
                        messageRepository, archiveRepository, codec, hasher);

        private ToolResultArchiveEntity legacy(long id, String toolUseId, String content) {
            ToolResultArchiveEntity row = new ToolResultArchiveEntity();
            row.setId(id);
            row.setArchiveId("archive-" + id);
            row.setSessionId("session-1");
            row.setToolUseId(toolUseId);
            row.setContent(content);
            row.setOriginalChars(content.length());
            return row;
        }

        private SessionMessageEntity message(
                long id, String toolUseId, String content, boolean error, String errorType) {
            SessionMessageEntity row = new SessionMessageEntity();
            row.setId(id);
            row.setSessionId("session-1");
            row.setSeqNo(id);
            row.setRole("user");
            row.setMsgType("NORMAL");
            row.setMessageType("normal");
            Message message = new Message();
            message.setRole(Message.Role.USER);
            message.setContent(List.of(ContentBlock.toolResult(
                    toolUseId, content, error, errorType)));
            PersistedMessageCodec.EncodedRow encoded = codec.encodeRow(
                    new PersistedMessageCodec.PersistedMessage(
                            message, "NORMAL", "normal", null, null,
                            java.util.Map.of(), null));
            row.setContentJson(encoded.contentJson());
            row.setMetadataJson(encoded.metadataJson());
            return row;
        }
    }
}
