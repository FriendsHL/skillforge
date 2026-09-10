package com.skillforge.server.session;

import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.server.entity.ToolResultArchiveEntity;
import com.skillforge.server.repository.ToolResultArchiveRepository;
import com.skillforge.server.service.ToolResultArchiveService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Read-only canonical archive projection for provider-visible persisted message occurrences. */
@Service
public class CanonicalToolResultModelView {

    private static final int ARCHIVE_LOOKUP_BATCH_SIZE = 500;

    private final ToolResultArchiveRepository archiveRepository;
    private final CanonicalToolResultOccurrenceResolver occurrenceResolver;

    public CanonicalToolResultModelView(
            ToolResultArchiveRepository archiveRepository,
            CanonicalToolResultOccurrenceResolver occurrenceResolver) {
        this.archiveRepository = Objects.requireNonNull(archiveRepository, "archiveRepository");
        this.occurrenceResolver = Objects.requireNonNull(
                occurrenceResolver, "occurrenceResolver");
    }

    @Transactional(readOnly = true)
    public List<Message> project(String sessionId, List<MessageOccurrence> messages) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        List<MessageOccurrence> safeMessages = List.copyOf(
                Objects.requireNonNull(messages, "messages"));
        List<CanonicalToolResultOccurrenceResolver.RawOccurrence> rawBlocks =
                rawBlocks(safeMessages);
        if (rawBlocks.isEmpty()) return messageList(safeMessages);

        List<CanonicalToolResultOccurrenceResolver.ArchiveCandidate> archives =
                archiveCandidates(sessionId, loadCandidateArchives(sessionId, rawBlocks));
        var resolution = occurrenceResolver.resolve(
                rawBlocks, archives);
        if (resolution.byOccurrence().isEmpty()) return messageList(safeMessages);

        List<Message> projected = new ArrayList<>(safeMessages.size());
        for (MessageOccurrence message : safeMessages) {
            projected.add(projectMessage(message, resolution));
        }
        return List.copyOf(projected);
    }

    private List<ToolResultArchiveEntity> loadCandidateArchives(
            String sessionId,
            List<CanonicalToolResultOccurrenceResolver.RawOccurrence> rawBlocks) {
        List<Long> messageIds = new ArrayList<>(new LinkedHashSet<>(rawBlocks.stream()
                .map(value -> value.key().sessionMessageId())
                .toList()));
        List<ToolResultArchiveEntity> archives = new ArrayList<>();
        for (int start = 0; start < messageIds.size(); start += ARCHIVE_LOOKUP_BATCH_SIZE) {
            int end = Math.min(messageIds.size(), start + ARCHIVE_LOOKUP_BATCH_SIZE);
            archives.addAll(archiveRepository.findBySessionIdAndSessionMessageIdIn(
                    sessionId, messageIds.subList(start, end)));
        }
        return List.copyOf(archives);
    }

    private static List<CanonicalToolResultOccurrenceResolver.RawOccurrence> rawBlocks(
            List<MessageOccurrence> messages) {
        List<CanonicalToolResultOccurrenceResolver.RawOccurrence> blocks = new ArrayList<>();
        for (MessageOccurrence value : messages) {
            if (value.messageId() == null || value.messageId() <= 0L
                    || value.message().getRole() != Message.Role.USER
                    || !(value.message().getContent() instanceof List<?> content)) {
                continue;
            }
            for (int blockIndex = 0; blockIndex < content.size(); blockIndex++) {
                CanonicalToolResultOccurrenceResolver.RawOccurrence occurrence =
                        rawOccurrence(value.messageId(), blockIndex, content.get(blockIndex));
                if (occurrence != null) blocks.add(occurrence);
            }
        }
        return List.copyOf(blocks);
    }

    private static CanonicalToolResultOccurrenceResolver.RawOccurrence rawOccurrence(
            long messageId,
            int blockIndex,
            Object value) {
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
                || content == null || error == null) {
            return null;
        }
        return new CanonicalToolResultOccurrenceResolver.RawOccurrence(
                new CanonicalToolResultOccurrenceResolver.OccurrenceKey(
                        messageId, blockIndex),
                toolUseId, content, error, errorType);
    }

    private static List<CanonicalToolResultOccurrenceResolver.ArchiveCandidate>
            archiveCandidates(String sessionId, List<ToolResultArchiveEntity> rows) {
        List<CanonicalToolResultOccurrenceResolver.ArchiveCandidate> out = new ArrayList<>();
        for (ToolResultArchiveEntity row : rows) {
            if (row == null || !sessionId.equals(row.getSessionId())
                    || row.getSessionMessageId() == null || row.getSessionMessageId() <= 0L
                    || row.getBlockIndex() == null || row.getBlockIndex() < 0) {
                continue;
            }
            out.add(new CanonicalToolResultOccurrenceResolver.ArchiveCandidate(
                    new CanonicalToolResultOccurrenceResolver.OccurrenceKey(
                            row.getSessionMessageId(), row.getBlockIndex()),
                    row.getArchiveId(), row.getToolUseId(), row.getContent(),
                    row.getCanonicalPayloadHash(), row.getPayloadHashVersion()));
        }
        return List.copyOf(out);
    }

    private static Message projectMessage(
            MessageOccurrence occurrence,
            CanonicalToolResultOccurrenceResolver.Resolution resolution) {
        Message original = occurrence.message();
        if (occurrence.messageId() == null
                || !(original.getContent() instanceof List<?> blocks)) {
            return original;
        }
        List<Object> projected = new ArrayList<>(blocks.size());
        boolean changed = false;
        for (int index = 0; index < blocks.size(); index++) {
            var key = new CanonicalToolResultOccurrenceResolver.OccurrenceKey(
                    occurrence.messageId(), index);
            var canonical = resolution.byOccurrence().get(key);
            if (canonical == null) {
                projected.add(blocks.get(index));
                continue;
            }
            changed = true;
            projected.add(ContentBlock.toolResult(
                    canonical.raw().toolUseId(), archivePreview(canonical),
                    canonical.raw().error(), canonical.raw().errorType()));
        }
        if (!changed) return original;
        Message copy = new Message();
        copy.setRole(original.getRole());
        copy.setContent(projected);
        copy.setReasoningContent(original.getReasoningContent());
        return copy;
    }

    private static String archivePreview(
            CanonicalToolResultOccurrenceResolver.CanonicalOccurrence canonical) {
        String content = canonical.archive().content();
        String head = surrogateSafeHead(content, ToolResultArchiveService.PREVIEW_HEAD_CHARS);
        return "[Tool result archived]\n"
                + "archive_id: " + canonical.archive().archiveId() + "\n"
                + "tool_use_id: " + canonical.raw().toolUseId() + "\n"
                + "original_chars: " + content.length() + "\n"
                + "preview:\n" + head;
    }

    private static String surrogateSafeHead(String value, int maxChars) {
        if (value.length() <= maxChars) return value;
        int end = maxChars;
        if (Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) {
            end--;
        }
        return value.substring(0, end);
    }

    private static List<Message> messageList(List<MessageOccurrence> messages) {
        return messages.stream().map(MessageOccurrence::message).toList();
    }

    public record MessageOccurrence(Long messageId, Message message) {
        public MessageOccurrence {
            Objects.requireNonNull(message, "message");
        }
    }
}
