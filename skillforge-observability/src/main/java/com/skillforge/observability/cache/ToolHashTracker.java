package com.skillforge.observability.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.llm.cache.ToolNormalizer;
import com.skillforge.core.model.ToolSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PROMPT-CACHE-MVP Phase 1 — per-session tool-schema drift detector (INV-3).
 *
 * <p>Records SHA-256 of each tool's normalized JSON keyed by {@code (sessionId, toolName)}.
 * On the next call we re-hash and compare; mismatch → warn-level log
 * ({@code "tool '{name}' schema drifted in session {sessionId}: prevHash=... newHash=..."})
 * — never throws, never blocks the call. The sole purpose is to surface why a previously
 * healthy cache hit rate may have collapsed; a real break is detected by
 * {@link CacheBreakDetector}.
 *
 * <p>State management: per-session inner Map kept in a top-level
 * {@link ConcurrentHashMap}. MVP unbounded; V2 may add LRU eviction.
 */
@Component
public class ToolHashTracker {

    private static final Logger log = LoggerFactory.getLogger(ToolHashTracker.class);

    private final Map<String, Map<String, String>> hashesBySession = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public ToolHashTracker(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Record-and-compare the current tools list for {@code sessionId}. Drift is logged at
     * WARN; same-hash is silent.
     *
     * @return number of tools whose hash drifted from the previous call (0 = no drift /
     *         first call). Mostly useful for tests; production callers ignore the return.
     */
    public int track(String sessionId, List<ToolSchema> tools) {
        if (sessionId == null || sessionId.isBlank() || tools == null || tools.isEmpty()) {
            return 0;
        }
        Map<String, String> sessionHashes =
                hashesBySession.computeIfAbsent(sessionId, k -> new HashMap<>());
        int driftCount = 0;
        // Synchronize on the per-session map — we read-update-put each tool. Outer map
        // is concurrent so different sessions don't contend.
        synchronized (sessionHashes) {
            for (ToolSchema tool : tools) {
                if (tool == null || tool.getName() == null) continue;
                String currentHash = ToolNormalizer.hashTool(tool, objectMapper);
                if (currentHash.isEmpty()) continue; // serialization failed — don't claim drift
                String prevHash = sessionHashes.put(tool.getName(), currentHash);
                if (prevHash != null && !prevHash.equals(currentHash)) {
                    log.warn("tool '{}' schema drifted in session {}: prevHash={} newHash={} - cache will miss",
                            tool.getName(), sessionId, prevHash, currentHash);
                    driftCount++;
                }
            }
        }
        return driftCount;
    }

    /** Test-only: clear all tracker state. */
    void clear() {
        hashesBySession.clear();
    }
}
