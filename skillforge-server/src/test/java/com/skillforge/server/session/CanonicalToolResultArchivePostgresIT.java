package com.skillforge.server.session;

import com.skillforge.core.engine.durability.PersistedBlockOccurrence;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.server.AbstractPostgresIT;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.entity.SessionMessageEntity;
import com.skillforge.server.entity.ToolResultArchiveEntity;
import com.skillforge.server.repository.SessionMessageRepository;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.repository.ToolResultArchiveRepository;
import com.skillforge.server.session.persistence.PersistedMessageCodec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import({
        JacksonAutoConfiguration.class,
        PersistedMessageCodec.class,
        ArchivePayloadIdentityHasher.class,
        CanonicalToolResultOccurrenceArchiveWriter.class,
        LegacyToolResultArchiveBackfillService.class
})
class CanonicalToolResultArchivePostgresIT extends AbstractPostgresIT {

    @Autowired private SessionRepository sessionRepository;
    @Autowired private SessionMessageRepository messageRepository;
    @Autowired private ToolResultArchiveRepository archiveRepository;
    @Autowired private PersistedMessageCodec messageCodec;
    @Autowired private ArchivePayloadIdentityHasher identityHasher;
    @Autowired private CanonicalToolResultOccurrenceArchiveWriter writer;
    @Autowired private LegacyToolResultArchiveBackfillService backfillService;

    @Test
    void legacyBackfill_uniqueExactOccurrence_claimsRowWithFullScalarHash() {
        String sessionId = createSession();
        SessionMessageEntity raw = appendResult(
                sessionId, 0L, "tool-1", "exact body", true, "FAILED");
        insertLegacy(sessionId, "archive-1", "tool-1", "exact body");

        LegacyToolResultArchiveBackfillService.BackfillReport report =
                backfillService.backfillSession(sessionId);

        ToolResultArchiveEntity claimed = archiveRepository.findByArchiveId("archive-1")
                .orElseThrow();
        assertThat(report).isEqualTo(
                new LegacyToolResultArchiveBackfillService.BackfillReport(1, 0, 0, 0));
        assertThat(claimed.getSessionMessageId()).isEqualTo(raw.getId());
        assertThat(claimed.getBlockIndex()).isZero();
        assertThat(claimed.getPayloadHashVersion())
                .isEqualTo(ArchivePayloadIdentityHasher.VERSION);
        assertThat(claimed.getCanonicalPayloadHash()).isEqualTo(
                identityHasher.hash("tool-1", "exact body", true, "FAILED"));
    }

    @Test
    void legacyBackfill_ambiguousExactOccurrence_keepsLegacyInvisible() {
        String sessionId = createSession();
        appendResult(sessionId, 0L, "tool-2", "same", false, null);
        appendResult(sessionId, 1L, "tool-2", "same", false, null);
        insertLegacy(sessionId, "archive-2", "tool-2", "same");

        LegacyToolResultArchiveBackfillService.BackfillReport report =
                backfillService.backfillSession(sessionId);

        ToolResultArchiveEntity untouched = archiveRepository.findByArchiveId("archive-2")
                .orElseThrow();
        assertThat(report).isEqualTo(
                new LegacyToolResultArchiveBackfillService.BackfillReport(0, 1, 0, 0));
        assertThat(untouched.getSessionMessageId()).isNull();
        assertThat(untouched.getCanonicalPayloadHash()).isNull();
    }

    @Test
    void occurrenceWriter_retryIsIdempotentAndPayloadMismatchFailsClosed() {
        String sessionId = createSession();
        String content = "x".repeat(200_001);
        SessionMessageEntity raw = appendResult(
                sessionId, 0L, "tool-3", content, false, null);
        UUID batchId = UUID.fromString("33333333-3333-4333-8333-333333333333");
        PersistedBlockOccurrence occurrence = new PersistedBlockOccurrence(
                raw.getId(), raw.getSeqNo(), sessionId, batchId, 0, 0,
                "tool-3", content, false, null, "trace-3");

        writer.prepare(List.of(occurrence));
        writer.prepare(List.of(occurrence));

        assertThat(archiveRepository.findBySessionId(sessionId)).hasSize(1);
        PersistedBlockOccurrence mismatch = new PersistedBlockOccurrence(
                raw.getId(), raw.getSeqNo(), sessionId, batchId, 0, 0,
                "tool-3", content, true, "FAILED", "trace-3");
        assertThatThrownBy(() -> writer.prepare(List.of(mismatch)))
                .isInstanceOf(ArchivePreparationIntegrityException.class);
        assertThat(archiveRepository.findBySessionId(sessionId)).hasSize(1);
    }

    private String createSession() {
        SessionEntity session = new SessionEntity();
        session.setId(UUID.randomUUID().toString());
        session.setUserId(901L);
        session.setAgentId(1L);
        session.setHistoryEpoch(0L);
        session.setMessagesJson("[]");
        return sessionRepository.saveAndFlush(session).getId();
    }

    private SessionMessageEntity appendResult(
            String sessionId,
            long seqNo,
            String toolUseId,
            String content,
            boolean error,
            String errorType) {
        Message message = new Message();
        message.setRole(Message.Role.USER);
        message.setContent(List.of(ContentBlock.toolResult(
                toolUseId, content, error, errorType)));
        PersistedMessageCodec.EncodedRow encoded = messageCodec.encodeRow(
                new PersistedMessageCodec.PersistedMessage(
                        message, "NORMAL", "normal", null, null, Map.of(), "trace-3"));
        SessionMessageEntity row = new SessionMessageEntity();
        row.setSessionId(sessionId);
        row.setSeqNo(seqNo);
        row.setRole(encoded.role());
        row.setMsgType(encoded.msgType());
        row.setMessageType(encoded.messageType());
        row.setContentJson(encoded.contentJson());
        row.setMetadataJson(encoded.metadataJson());
        row.setTraceId(encoded.traceId());
        return messageRepository.saveAndFlush(row);
    }

    private void insertLegacy(
            String sessionId, String archiveId, String toolUseId, String content) {
        int inserted = archiveRepository.insertIgnoreConflict(
                archiveId, sessionId, null, toolUseId, null, content.length(),
                content, content, Instant.now());
        assertThat(inserted).isOne();
    }
}
