package com.skillforge.observability.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PROMPT-CACHE-MVP Phase 4 — INV-8 / INV-9 unit tests for {@link CacheBreakDetector}.
 *
 * <p>Algorithm rules under test:
 * <ul>
 *   <li>First call returns false (no prev tracker).</li>
 *   <li>Drop &lt; 2K tokens absolute → false (small fluctuation).</li>
 *   <li>current ≥ prev × 0.95 → false (within 5% noise).</li>
 *   <li>Otherwise → true (real break).</li>
 *   <li>State is per-session — two sessions don't pollute each other.</li>
 * </ul>
 */
@DisplayName("CacheBreakDetector — 5%/2K break heuristic (Phase 4 / INV-8 / INV-9)")
class CacheBreakDetectorTest {

    @Test
    @DisplayName("INV-9: first call always returns false (no prev tracker yet)")
    void firstCallNeverReportsBreak() {
        CacheBreakDetector detector = new CacheBreakDetector();
        assertThat(detector.check("session-1", 50_000)).isFalse();
    }

    @Test
    @DisplayName("small drop under 2K tokens is NOT a break")
    void smallDropIgnored() {
        CacheBreakDetector detector = new CacheBreakDetector();
        detector.check("session-1", 50_000); // first call → tracker primed
        // Drop of 1K is below MIN_DROP_TOKENS=2000 → false
        assertThat(detector.check("session-1", 49_000)).isFalse();
    }

    @Test
    @DisplayName("drop within 5% of prev is NOT a break (TOLERANCE_RATIO)")
    void within5PctNoiseIgnored() {
        CacheBreakDetector detector = new CacheBreakDetector();
        detector.check("session-1", 100_000);
        // 4K drop, but 96K >= 100K * 0.95 → still no break.
        assertThat(detector.check("session-1", 96_000)).isFalse();
    }

    @Test
    @DisplayName("real break: drop > 2K AND current < 95% of prev → true")
    void realBreakDetected() {
        CacheBreakDetector detector = new CacheBreakDetector();
        detector.check("session-1", 50_000);
        // current=10K is well under 95% of 50K (47.5K) and drop is 40K > 2K → break.
        assertThat(detector.check("session-1", 10_000)).isTrue();
    }

    @Test
    @DisplayName("state is keyed per-session — sessions don't cross-contaminate")
    void statePerSession() {
        CacheBreakDetector detector = new CacheBreakDetector();
        detector.check("alpha", 50_000);
        detector.check("beta",  10_000);
        // beta's first check is itself the first call, so even though alpha's prev is
        // 50K, beta starts fresh — its second check follows beta's own prev (10K).
        assertThat(detector.check("beta", 9_500)).isFalse(); // small drop on beta
        // alpha sees a real break (50K → 5K).
        assertThat(detector.check("alpha", 5_000)).isTrue();
    }

    @Test
    @DisplayName("null/blank sessionId short-circuits to false (no false positives for ad-hoc calls)")
    void nullOrBlankSessionFalse() {
        CacheBreakDetector detector = new CacheBreakDetector();
        assertThat(detector.check(null, 1000)).isFalse();
        assertThat(detector.check("", 1000)).isFalse();
        assertThat(detector.check("   ", 1000)).isFalse();
    }

    @Test
    @DisplayName("after a break, the new low becomes the next baseline")
    void breakUpdatesBaseline() {
        CacheBreakDetector detector = new CacheBreakDetector();
        detector.check("session-1", 50_000);
        assertThat(detector.check("session-1", 5_000)).isTrue();
        // Next call: prev=5_000 — drop of 100 is below MIN, no break.
        assertThat(detector.check("session-1", 4_900)).isFalse();
    }
}
