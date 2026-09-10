package com.skillforge.server.session;

import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.server.entity.SessionMessageEntity;
import com.skillforge.server.entity.ToolResultArchiveEntity;
import com.skillforge.server.repository.SessionMessageRepository;
import com.skillforge.server.repository.ToolResultArchiveRepository;
import com.skillforge.server.session.persistence.PersistedMessageCodec;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Explicit offline migration primitive for legacy archives without occurrence identity.
 *
 * <p>This service is deliberately not scheduled at startup and is not on a request path. An
 * operator-owned maintenance runner may call one Session at a time while writers are stopped.
 * A legacy row is claimed only when its available exact scalars identify one and only one
 * unpruned raw TOOL_RESULT occurrence; every other case remains invisible and raw stays canonical.
 */
@Service
public class LegacyToolResultArchiveBackfillService {

    private static final int MESSAGE_PAGE_SIZE = 500;

    private final SessionMessageRepository messageRepository;
    private final ToolResultArchiveRepository archiveRepository;
    private final PersistedMessageCodec messageCodec;
    private final ArchivePayloadIdentityHasher identityHasher;

    public LegacyToolResultArchiveBackfillService(
            SessionMessageRepository messageRepository,
            ToolResultArchiveRepository archiveRepository,
            PersistedMessageCodec messageCodec,
            ArchivePayloadIdentityHasher identityHasher) {
        this.messageRepository = Objects.requireNonNull(messageRepository, "messageRepository");
        this.archiveRepository = Objects.requireNonNull(archiveRepository, "archiveRepository");
        this.messageCodec = Objects.requireNonNull(messageCodec, "messageCodec");
        this.identityHasher = Objects.requireNonNull(identityHasher, "identityHasher");
    }

    @Transactional
    public BackfillReport backfillSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        List<Tracker> trackers = archiveRepository
                .findBySessionIdAndSessionMessageIdIsNullOrderByIdAsc(sessionId)
                .stream()
                .map(row -> new Tracker(sessionId, row))
                .toList();
        if (trackers.isEmpty()) return new BackfillReport(0, 0, 0, 0);

        Map<String, List<Tracker>> byToolUseId = new LinkedHashMap<>();
        for (Tracker tracker : trackers) {
            if (tracker.invalid()) continue;
            byToolUseId.computeIfAbsent(
                    tracker.toolUseId(), ignored -> new ArrayList<>()).add(tracker);
        }
        scanRawOccurrences(sessionId, byToolUseId);
        return claimUniqueMatches(sessionId, trackers);
    }

    private void scanRawOccurrences(String sessionId, Map<String, List<Tracker>> trackers) {
        if (trackers.isEmpty()) return;
        int pageNumber = 0;
        Page<SessionMessageEntity> page;
        do {
            page = messageRepository.findBySessionIdAndPrunedAtIsNullOrderBySeqNoAsc(
                    sessionId, PageRequest.of(pageNumber, MESSAGE_PAGE_SIZE));
            for (SessionMessageEntity row : page.getContent()) {
                scanMessage(row, trackers);
            }
            pageNumber++;
        } while (page.hasNext());
    }

    private void scanMessage(
            SessionMessageEntity row,
            Map<String, List<Tracker>> trackers) {
        if (row.getId() == null || !"user".equals(row.getRole())) return;
        Message message = messageCodec.decodeRow(new PersistedMessageCodec.EncodedRow(
                row.getRole(), row.getContentJson(), row.getReasoningContent(),
                row.getMsgType(), row.getMessageType(), row.getControlId(),
                row.getAnsweredAt(), row.getMetadataJson(), row.getTraceId())).message();
        if (!(message.getContent() instanceof List<?> blocks)) return;
        for (int blockIndex = 0; blockIndex < blocks.size(); blockIndex++) {
            RawOccurrence occurrence = rawOccurrence(row.getId(), blockIndex, blocks.get(blockIndex));
            if (occurrence == null) continue;
            List<Tracker> candidates = trackers.get(occurrence.toolUseId());
            if (candidates == null) continue;
            for (Tracker candidate : candidates) {
                if (candidate.content().equals(occurrence.content())) {
                    candidate.match(occurrence);
                }
            }
        }
    }

    private BackfillReport claimUniqueMatches(String sessionId, List<Tracker> trackers) {
        int claimed = 0;
        int ambiguous = 0;
        int unmatched = 0;
        int invalid = 0;
        for (Tracker tracker : trackers) {
            if (tracker.invalid()) {
                invalid++;
                continue;
            }
            if (tracker.matchCount() > 1) {
                ambiguous++;
                continue;
            }
            RawOccurrence match = tracker.uniqueMatch();
            if (match == null) {
                unmatched++;
                continue;
            }
            if (archiveRepository.findBySessionIdAndSessionMessageIdAndBlockIndex(
                    sessionId, match.messageId(), match.blockIndex()).isPresent()) {
                ambiguous++;
                continue;
            }
            String hash = identityHasher.hash(
                    tracker.toolUseId(), tracker.content(), match.error(), match.errorType());
            int updated = archiveRepository.claimLegacyOccurrence(
                    tracker.id(), sessionId, match.messageId(), match.blockIndex(), hash,
                    ArchivePayloadIdentityHasher.VERSION);
            if (updated == 1) claimed++;
            else unmatched++;
        }
        return new BackfillReport(claimed, ambiguous, unmatched, invalid);
    }

    private static RawOccurrence rawOccurrence(long messageId, int blockIndex, Object value) {
        String type;
        String toolUseId;
        String content;
        Boolean error;
        String errorType;
        if (value instanceof ContentBlock block) {
            type = block.getType();
            toolUseId = block.getToolUseId();
            content = block.getContent();
            error = block.getIsError();
            errorType = block.getErrorType();
        } else if (value instanceof Map<?, ?> block) {
            type = block.get("type") instanceof String text ? text : null;
            toolUseId = block.get("tool_use_id") instanceof String text ? text : null;
            content = block.get("content") instanceof String text ? text : null;
            error = block.get("is_error") instanceof Boolean flag ? flag : null;
            errorType = block.get("error_type") instanceof String text ? text : null;
        } else {
            return null;
        }
        if (!"tool_result".equals(type) || toolUseId == null || toolUseId.isBlank()
                || content == null || error == null
                || (errorType != null && (errorType.isBlank() || !error))) {
            return null;
        }
        return new RawOccurrence(messageId, blockIndex, toolUseId, content, error, errorType);
    }

    public record BackfillReport(int claimed, int ambiguous, int unmatched, int invalid) { }

    private record RawOccurrence(
            long messageId,
            int blockIndex,
            String toolUseId,
            String content,
            boolean error,
            String errorType) { }

    private static final class Tracker {
        private final Long id;
        private final String toolUseId;
        private final String content;
        private final boolean invalid;
        private RawOccurrence uniqueMatch;
        private int matchCount;

        private Tracker(String sessionId, ToolResultArchiveEntity row) {
            this.id = row.getId();
            this.toolUseId = row.getToolUseId();
            this.content = row.getContent();
            this.invalid = row.getId() == null
                    || !sessionId.equals(row.getSessionId())
                    || row.getSessionMessageId() != null
                    || row.getBlockIndex() != null
                    || row.getCanonicalPayloadHash() != null
                    || row.getPayloadHashVersion() != null
                    || toolUseId == null || toolUseId.isBlank()
                    || content == null
                    || row.getOriginalChars() != (content == null ? -1 : content.length());
        }

        private void match(RawOccurrence occurrence) {
            matchCount++;
            if (matchCount == 1) uniqueMatch = occurrence;
            else uniqueMatch = null;
        }

        private Long id() { return id; }
        private String toolUseId() { return toolUseId; }
        private String content() { return content; }
        private boolean invalid() { return invalid; }
        private RawOccurrence uniqueMatch() { return uniqueMatch; }
        private int matchCount() { return matchCount; }
    }
}
