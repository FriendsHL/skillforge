package com.skillforge.observability.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PROMPT-CACHE-MVP Phase 4 — detect cache "break" events across consecutive LLM calls in a
 * session.
 *
 * <p>Algorithm (claude code mode, INV-8 / INV-9):
 * <ol>
 *   <li>Track {@code prevCacheRead} per session.</li>
 *   <li>First call → no prev → return {@code false} (INV-9 first-call never reports break).</li>
 *   <li>Subsequent call: drop = {@code prev - current}.
 *       <ul>
 *         <li>{@code drop < MIN_DROP_TOKENS (2K)} → false (small fluctuations).</li>
 *         <li>{@code current >= prev * TOLERANCE_RATIO (95%)} → false (within 5% noise).</li>
 *         <li>Otherwise → true (real break).</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p>State management: {@link ConcurrentHashMap} keyed by sessionId. MVP accepts unbounded
 * growth (per-session typically &lt;1000 entries lifetime); V2 may add LRU eviction.
 */
@Component
public class CacheBreakDetector {

    private static final Logger log = LoggerFactory.getLogger(CacheBreakDetector.class);

    /** 5% noise floor — anything within 95% of prev is not a break. */
    static final double TOLERANCE_RATIO = 0.95;
    /** Absolute drop floor — fewer than 2K tokens drop is small and not flagged. */
    static final int MIN_DROP_TOKENS = 2000;

    private final Map<String, Integer> prevCacheReadBySession = new ConcurrentHashMap<>();

    /**
     * Update the per-session prev tracker and decide whether this call is a cache break.
     *
     * @param sessionId        session key — null/blank short-circuits to {@code false} (no
     *                         tracking, no false positives for ad-hoc / system calls)
     * @param currentCacheRead cache_read tokens reported in the current response usage
     * @return {@code true} if a cache break has been detected
     */
    public boolean check(String sessionId, int currentCacheRead) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        Integer prev = prevCacheReadBySession.put(sessionId, currentCacheRead);
        if (prev == null) {
            return false;       // first call (INV-9)
        }
        int drop = prev - currentCacheRead;
        if (drop < MIN_DROP_TOKENS) {
            return false;       // within absolute noise
        }
        if (currentCacheRead >= prev * TOLERANCE_RATIO) {
            return false;       // within 5% relative tolerance
        }
        log.info("Cache break detected: sessionId={} prev={} curr={} drop={}",
                sessionId, prev, currentCacheRead, drop);
        return true;
    }

    /**
     * Test-only: clear all tracker state. Production callers should not need this — sessions
     * naturally terminate and the entries become stale (see V2 LRU note).
     */
    void clear() {
        prevCacheReadBySession.clear();
    }

    /** Test-only: peek at current prev for a session. Returns null if not tracked. */
    Integer peekPrev(String sessionId) {
        return prevCacheReadBySession.get(sessionId);
    }
}
