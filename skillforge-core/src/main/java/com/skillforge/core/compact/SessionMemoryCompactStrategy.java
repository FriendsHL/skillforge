package com.skillforge.core.compact;

import com.skillforge.core.model.Message;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Zero-LLM compaction strategy that uses already-extracted session memories
 * as the summary, replacing the expensive LLM summarization call.
 *
 * <p>Follows the same COMPACT_BOUNDARY + SUMMARY pattern as
 * {@link FullCompactStrategy} for consistency.
 *
 * <p>Returns {@code null} from {@link #tryCompact} when:
 * <ul>
 *   <li>No memory summary available</li>
 *   <li>Resulting compacted conversation exceeds token budget</li>
 *   <li>Young-gen has fewer than {@code minMessages} messages</li>
 * </ul>
 * In these cases the caller should fall back to LLM full compact.
 */
public class SessionMemoryCompactStrategy {

    private static final Logger log = LoggerFactory.getLogger(SessionMemoryCompactStrategy.class);

    public static final int DEFAULT_MIN_TOKENS = 10_000;
    public static final int DEFAULT_MIN_MESSAGES = 5;
    public static final int DEFAULT_MAX_TOKENS = 40_000;

    /**
     * Attempt memory-based compaction.
     *
     * @param prep           PreparedCompact from {@link FullCompactStrategy#prepareCompact}
     * @param memorySummary  Pre-rendered memory text (e.g. from MemoryService.previewMemoriesForPrompt)
     * @param maxTokens      Token budget for the compacted result (0 = use default)
     * @param minMessages    Minimum young-gen messages required (0 = use default)
     * @return CompactResult if successful and within budget, null to fall back to LLM
     */
    public CompactResult tryCompact(FullCompactStrategy.PreparedCompact prep,
                                    String memorySummary,
                                    int maxTokens,
                                    int minMessages) {
        if (prep == null) return null;
        if (memorySummary == null || memorySummary.isBlank()) return null;

        int effectiveMaxTokens = maxTokens > 0 ? maxTokens : DEFAULT_MAX_TOKENS;
        int effectiveMinMessages = minMessages > 0 ? minMessages : DEFAULT_MIN_MESSAGES;

        List<Message> youngGen = prep.youngGen();
        if (youngGen.size() < effectiveMinMessages) {
            log.debug("sessionMemoryCompact: young-gen size {} < minMessages {}, skipping",
                    youngGen.size(), effectiveMinMessages);
            return null;
        }

        // Build summary prefix in the same format as FullCompactStrategy
        String summaryPrefix = "[Session memory summary from " + prep.window().size()
                + " messages compacted at " + Instant.now() + "]\n" + memorySummary.trim();

        // Assemble compacted messages: standalone summary user message + unmodified young-gen.
        // (Matches FullCompactStrategy / Claude Code / OpenClaw layout. Modern providers
        // accept consecutive user messages.)
        List<Message> compacted = new ArrayList<>(youngGen.size() + 1);
        compacted.add(Message.user(summaryPrefix));
        compacted.addAll(youngGen);

        int afterTokens = TokenEstimator.estimate(compacted);
        if (afterTokens > effectiveMaxTokens) {
            log.info("sessionMemoryCompact: result {} tokens exceeds budget {} tokens, falling back to LLM",
                    afterTokens, effectiveMaxTokens);
            return null;
        }

        List<String> applied = new ArrayList<>();
        applied.add("session-memory");
        return new CompactResult(compacted, prep.beforeTokens(), afterTokens,
                prep.beforeCount(), compacted.size(), applied);
    }
}
