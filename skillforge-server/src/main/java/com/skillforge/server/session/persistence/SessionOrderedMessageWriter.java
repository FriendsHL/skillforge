package com.skillforge.server.session.persistence;

import com.skillforge.core.engine.durability.MessageSnapshot;
import com.skillforge.core.engine.durability.PersistedMessageOccurrence;
import com.skillforge.server.entity.SessionMessageEntity;
import com.skillforge.server.repository.SessionMessageRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Transaction participant for exact, ordered {@code t_session_message} batches.
 *
 * <p>The caller must already hold the owning Session row's pessimistic lock. This class never
 * opens a transaction and never uses {@code SessionService}'s process-local append stripe.
 */
@Component
public class SessionOrderedMessageWriter {

    private final SessionMessageRepository messageRepository;
    private final PersistedMessageCodec messageCodec;
    private final EntityManager entityManager;

    public SessionOrderedMessageWriter(
            SessionMessageRepository messageRepository,
            PersistedMessageCodec messageCodec,
            EntityManager entityManager) {
        this.messageRepository = Objects.requireNonNull(messageRepository, "messageRepository");
        this.messageCodec = Objects.requireNonNull(messageCodec, "messageCodec");
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
    }

    /** Appends a previously unseen batch. Any existing row is an integrity failure, not repairable. */
    public List<PersistedMessageOccurrence> appendNewBatchLocked(
            String sessionId,
            String writeBatchId,
            List<PersistedMessageCodec.PersistedMessage> expectedMessages) {
        requireSessionId(sessionId);
        requireCanonicalBatchId(writeBatchId);
        List<PersistedMessageCodec.PersistedMessage> expected = requireMessages(expectedMessages);
        if (!messageRepository
                .findBySessionIdAndWriteBatchIdOrderByWriteBatchOrdinalAsc(sessionId, writeBatchId)
                .isEmpty()) {
            throw integrityFailure();
        }

        long baseSeq = messageRepository.findTopBySessionIdOrderBySeqNoDesc(sessionId)
                .map(SessionMessageEntity::getSeqNo)
                .orElse(-1L);
        List<SessionMessageEntity> rows = new ArrayList<>(expected.size());
        for (int ordinal = 0; ordinal < expected.size(); ordinal++) {
            PersistedMessageCodec.EncodedRow encoded = messageCodec.encodeRow(expected.get(ordinal));
            SessionMessageEntity row = new SessionMessageEntity();
            row.setSessionId(sessionId);
            row.setSeqNo(baseSeq + ordinal + 1L);
            row.setRole(encoded.role());
            row.setContentJson(encoded.contentJson());
            row.setReasoningContent(encoded.reasoningContent());
            row.setMsgType(encoded.msgType());
            row.setMessageType(encoded.messageType());
            row.setControlId(encoded.controlId());
            row.setAnsweredAt(encoded.answeredAt());
            row.setMetadataJson(encoded.metadataJson());
            row.setTraceId(encoded.traceId());
            row.setWriteBatchId(writeBatchId);
            row.setWriteBatchOrdinal(ordinal);
            rows.add(row);
        }

        List<SessionMessageEntity> saved = messageRepository.saveAllAndFlush(rows);
        // ACKs are built from database-reloaded scalar rows, never from the mutable candidates.
        for (SessionMessageEntity row : saved) {
            entityManager.refresh(row);
        }
        return readExactBatch(sessionId, writeBatchId, expected);
    }

    /** Reads and byte-validates a complete batch for an ACK-loss retry. */
    public List<PersistedMessageOccurrence> readExactBatch(
            String sessionId,
            String writeBatchId,
            List<PersistedMessageCodec.PersistedMessage> expectedMessages) {
        requireSessionId(sessionId);
        requireCanonicalBatchId(writeBatchId);
        List<PersistedMessageCodec.PersistedMessage> expected = requireMessages(expectedMessages);
        List<SessionMessageEntity> rows = messageRepository
                .findBySessionIdAndWriteBatchIdOrderByWriteBatchOrdinalAsc(sessionId, writeBatchId);
        if (rows.size() != expected.size()) {
            throw integrityFailure();
        }

        List<PersistedMessageOccurrence> occurrences = new ArrayList<>(rows.size());
        long firstSeq = rows.isEmpty() ? -1L : rows.get(0).getSeqNo();
        for (int ordinal = 0; ordinal < rows.size(); ordinal++) {
            SessionMessageEntity row = rows.get(ordinal);
            if (row.getId() == null
                    || !sessionId.equals(row.getSessionId())
                    || !writeBatchId.equals(row.getWriteBatchId())
                    || row.getWriteBatchOrdinal() == null
                    || row.getWriteBatchOrdinal() != ordinal
                    || row.getSeqNo() != firstSeq + ordinal) {
                throw integrityFailure();
            }

            PersistedMessageCodec.EncodedRow actual = encodedRow(row);
            PersistedMessageCodec.EncodedRow expectedRow = messageCodec.encodeRow(expected.get(ordinal));
            if (!expectedRow.equals(actual)) {
                throw integrityFailure();
            }
            PersistedMessageCodec.PersistedMessage decoded = messageCodec.decodeRow(actual);
            if (!actual.equals(messageCodec.encodeRow(decoded))) {
                throw integrityFailure();
            }
            occurrences.add(new PersistedMessageOccurrence(
                    row.getId(),
                    row.getSeqNo(),
                    writeBatchId,
                    ordinal,
                    MessageSnapshot.capture(decoded.message()),
                    decoded.msgType(),
                    decoded.messageType(),
                    decoded.controlId(),
                    decoded.answeredAt(),
                    decoded.metadata(),
                    decoded.traceId()));
        }
        return List.copyOf(occurrences);
    }

    private static PersistedMessageCodec.EncodedRow encodedRow(SessionMessageEntity row) {
        return new PersistedMessageCodec.EncodedRow(
                row.getRole(),
                row.getContentJson(),
                row.getReasoningContent(),
                row.getMsgType(),
                row.getMessageType(),
                row.getControlId(),
                row.getAnsweredAt(),
                row.getMetadataJson(),
                row.getTraceId());
    }

    private static List<PersistedMessageCodec.PersistedMessage> requireMessages(
            List<PersistedMessageCodec.PersistedMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("expectedMessages must not be empty");
        }
        if (messages.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("expectedMessages must not contain null");
        }
        return List.copyOf(messages);
    }

    private static void requireSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
    }

    private static void requireCanonicalBatchId(String writeBatchId) {
        try {
            UUID parsed = UUID.fromString(writeBatchId);
            if (!parsed.toString().equals(writeBatchId)
                    || parsed.version() < 1 || parsed.version() > 5) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new IllegalArgumentException(
                    "writeBatchId must be a canonical lowercase UUID", invalid);
        }
    }

    private static DurableMessageBatchIntegrityException integrityFailure() {
        return new DurableMessageBatchIntegrityException();
    }
}
