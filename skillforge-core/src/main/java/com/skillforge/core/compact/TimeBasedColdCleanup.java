package com.skillforge.core.compact;

import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Zero-cost time-based cleanup of old compactable tool results.
 *
 * <p>When a session has been idle beyond a threshold, old tool_result content
 * from compactable tools is replaced with a placeholder. This reduces context
 * window usage without any LLM call.
 *
 * <p>Complementary to {@link LightCompactStrategy} (ratio-based) — this handles
 * cold sessions, LightCompact handles hot sessions.
 *
 * <p>Invariants preserved:
 * <ul>
 *   <li>Messages are never removed, only content is replaced</li>
 *   <li>tool_use / tool_result pairing is untouched</li>
 *   <li>Protection window (last N messages) is respected</li>
 *   <li>Non-compactable tools are never touched</li>
 * </ul>
 */
public final class TimeBasedColdCleanup {

    private static final Logger log = LoggerFactory.getLogger(TimeBasedColdCleanup.class);

    public static final String CLEARED_PLACEHOLDER = "[Old tool result content cleared]";
    public static final int DEFAULT_KEEP_RECENT = 5;
    public static final long DEFAULT_IDLE_THRESHOLD_SECONDS = 300; // 5 minutes

    private TimeBasedColdCleanup() {}

    /**
     * Clear old compactable tool results if the session has been idle long enough.
     *
     * @param messages            the conversation message list (mutated in-place)
     * @param idleSeconds         how long the session has been idle (-1 = unknown, skip)
     * @param idleThresholdSeconds minimum idle time before cleanup triggers
     * @param keepRecent          number of most recent compactable tool results to preserve
     * @param registry            which tools are eligible for cleanup
     * @return number of tool results cleared
     */
    public static int apply(List<Message> messages,
                            long idleSeconds,
                            long idleThresholdSeconds,
                            int keepRecent,
                            CompactableToolRegistry registry) {
        if (messages == null || messages.isEmpty()) return 0;
        if (idleSeconds < 0 || idleSeconds < idleThresholdSeconds) return 0;
        if (registry == null) return 0;

        // Build toolUseId -> toolName index
        Map<String, String> toolUseIdToName = buildToolUseIdToNameIndex(messages);

        // Collect indices of compactable tool_result blocks (message index + block index)
        // in reverse order (most recent first)
        int protectFrom = Math.max(0, messages.size() - LightCompactStrategy.PROTECTION_WINDOW);
        List<ToolResultLocation> compactableResults = new ArrayList<>();

        for (int i = protectFrom - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (!(m.getContent() instanceof List<?> blocks)) continue;
            for (int j = blocks.size() - 1; j >= 0; j--) {
                Object o = blocks.get(j);
                if (!(o instanceof ContentBlock cb)) continue;
                if (!"tool_result".equals(cb.getType())) continue;
                String toolName = toolUseIdToName.get(cb.getToolUseId());
                if (!registry.isCompactable(toolName)) continue;
                // Skip already cleared
                if (cb.getContent() == null || cb.getContent().isEmpty()) continue;
                if (CLEARED_PLACEHOLDER.equals(cb.getContent())) continue;
                compactableResults.add(new ToolResultLocation(i, j));
            }
        }

        // Keep the most recent `keepRecent` results, clear the rest
        if (compactableResults.size() <= keepRecent) return 0;

        int cleared = 0;
        for (int k = keepRecent; k < compactableResults.size(); k++) {
            ToolResultLocation loc = compactableResults.get(k);
            Message m = messages.get(loc.msgIndex);
            @SuppressWarnings("unchecked")
            List<ContentBlock> blocks = (List<ContentBlock>) m.getContent();
            ContentBlock original = blocks.get(loc.blockIndex);
            blocks.set(loc.blockIndex, ContentBlock.toolResult(
                    original.getToolUseId(), CLEARED_PLACEHOLDER,
                    Boolean.TRUE.equals(original.getIsError())));
            cleared++;
        }

        if (cleared > 0) {
            log.info("Time-based cold cleanup: cleared {} old tool results (idle={}s, threshold={}s, kept={})",
                    cleared, idleSeconds, idleThresholdSeconds, keepRecent);
        }
        return cleared;
    }

    private static Map<String, String> buildToolUseIdToNameIndex(List<Message> messages) {
        Map<String, String> index = new HashMap<>();
        for (Message m : messages) {
            if (!(m.getContent() instanceof List<?> blocks)) continue;
            for (Object o : blocks) {
                if (o instanceof ContentBlock cb) {
                    if ("tool_use".equals(cb.getType()) && cb.getId() != null && cb.getName() != null) {
                        index.put(cb.getId(), cb.getName());
                    }
                } else if (o instanceof Map<?, ?> map) {
                    if ("tool_use".equals(map.get("type"))) {
                        Object id = map.get("id");
                        Object name = map.get("name");
                        if (id instanceof String sid && name instanceof String sname) {
                            index.put(sid, sname);
                        }
                    }
                }
            }
        }
        return index;
    }

    /** Location of a tool_result ContentBlock within the message list. */
    private record ToolResultLocation(int msgIndex, int blockIndex) {}
}
