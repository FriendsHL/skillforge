package com.skillforge.server.session;

import com.skillforge.core.engine.durability.PersistedBlockOccurrence;
import com.skillforge.server.entity.ToolResultArchiveEntity;
import com.skillforge.server.repository.ToolResultArchiveRepository;
import com.skillforge.server.service.ToolResultArchiveService;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Creates occurrence-owned archives only for verified result blocks above the existing budget. */
@Component
public class CanonicalToolResultOccurrenceArchiveWriter
        implements ToolResultOccurrenceArchiveWriter {

    private final ToolResultArchiveRepository archiveRepository;
    private final ArchivePayloadIdentityHasher identityHasher;

    public CanonicalToolResultOccurrenceArchiveWriter(
            ToolResultArchiveRepository archiveRepository,
            ArchivePayloadIdentityHasher identityHasher) {
        this.archiveRepository = Objects.requireNonNull(archiveRepository, "archiveRepository");
        this.identityHasher = Objects.requireNonNull(identityHasher, "identityHasher");
    }

    @Override
    public void prepare(List<PersistedBlockOccurrence> resultBlocks) {
        Map<MessageKey, List<PersistedBlockOccurrence>> byMessage = new LinkedHashMap<>();
        for (PersistedBlockOccurrence occurrence : List.copyOf(resultBlocks)) {
            MessageKey key = new MessageKey(occurrence.sessionId(), occurrence.messageId());
            List<PersistedBlockOccurrence> messageOccurrences =
                    byMessage.computeIfAbsent(key, ignored -> new ArrayList<>());
            if (messageOccurrences.stream().anyMatch(
                    existing -> existing.blockIndex() == occurrence.blockIndex())) {
                throw new ArchivePreparationIntegrityException();
            }
            messageOccurrences.add(occurrence);
        }
        for (List<PersistedBlockOccurrence> messageOccurrences : byMessage.values()) {
            prepareMessage(messageOccurrences);
        }
    }

    private void prepareMessage(List<PersistedBlockOccurrence> occurrences) {
        long retainedChars = occurrences.stream()
                .mapToLong(occurrence -> occurrence.content().length())
                .sum();
        List<PersistedBlockOccurrence> fresh = new ArrayList<>();
        for (PersistedBlockOccurrence occurrence : occurrences) {
            ToolResultArchiveEntity existing = archiveRepository
                    .findBySessionIdAndSessionMessageIdAndBlockIndex(
                            occurrence.sessionId(), occurrence.messageId(),
                            occurrence.blockIndex())
                    .orElse(null);
            if (existing == null) {
                fresh.add(occurrence);
                continue;
            }
            validateWinner(existing, occurrence, identityHasher.hash(occurrence));
            retainedChars -= occurrence.content().length();
        }
        if (retainedChars <= ToolResultArchiveService.DEFAULT_PER_MESSAGE_AGGREGATE_CHARS) {
            return;
        }
        fresh.sort(Comparator
                .comparingInt((PersistedBlockOccurrence value) -> value.content().length())
                .reversed()
                .thenComparingInt(PersistedBlockOccurrence::blockIndex));
        for (PersistedBlockOccurrence occurrence : fresh) {
            if (retainedChars <= ToolResultArchiveService.DEFAULT_PER_MESSAGE_AGGREGATE_CHARS) {
                break;
            }
            prepareLargeOccurrence(occurrence);
            retainedChars -= occurrence.content().length();
        }
    }

    private void prepareLargeOccurrence(PersistedBlockOccurrence occurrence) {
        String hash = identityHasher.hash(occurrence);
        String preview = surrogateSafeHead(
                occurrence.content(), ToolResultArchiveService.PREVIEW_HEAD_CHARS);
        archiveRepository.insertOccurrenceIgnoreConflict(
                UUID.randomUUID().toString(),
                occurrence.sessionId(),
                occurrence.messageId(),
                occurrence.blockIndex(),
                occurrence.toolUseId(),
                null,
                occurrence.content().length(),
                preview,
                occurrence.content(),
                hash,
                ArchivePayloadIdentityHasher.VERSION,
                Instant.now());
        ToolResultArchiveEntity winner = archiveRepository
                .findBySessionIdAndSessionMessageIdAndBlockIndex(
                        occurrence.sessionId(), occurrence.messageId(), occurrence.blockIndex())
                .orElseThrow(ArchivePreparationIntegrityException::new);
        validateWinner(winner, occurrence, hash);
    }

    private static void validateWinner(
            ToolResultArchiveEntity winner,
            PersistedBlockOccurrence occurrence,
            String hash) {
        if (!occurrence.sessionId().equals(winner.getSessionId())
                || !Objects.equals(winner.getSessionMessageId(), occurrence.messageId())
                || !Objects.equals(winner.getBlockIndex(), occurrence.blockIndex())
                || !occurrence.toolUseId().equals(winner.getToolUseId())
                || !occurrence.content().equals(winner.getContent())
                || winner.getOriginalChars() != occurrence.content().length()
                || !hash.equals(winner.getCanonicalPayloadHash())
                || !Objects.equals(
                        winner.getPayloadHashVersion(), ArchivePayloadIdentityHasher.VERSION)) {
            throw new ArchivePreparationIntegrityException();
        }
    }

    private static String surrogateSafeHead(String value, int maxChars) {
        if (value.length() <= maxChars) return value;
        int end = maxChars;
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))
                && end < value.length() && Character.isLowSurrogate(value.charAt(end))) {
            end--;
        }
        return value.substring(0, end);
    }

    private record MessageKey(String sessionId, long messageId) { }
}
