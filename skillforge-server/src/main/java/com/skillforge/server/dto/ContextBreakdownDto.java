package com.skillforge.server.dto;

import java.util.List;

/**
 * Breakdown of the estimated tokens currently occupying a session's LLM context window.
 *
 * <p>Values come from {@link com.skillforge.core.compact.TokenEstimator} — ±10% precision —
 * sufficient to tell users what is taking up their budget, not an exact billing counter.
 */
public record ContextBreakdownDto(
        String sessionId,
        long total,
        long windowLimit,
        int pct,
        List<Segment> segments) {

    /**
     * A single segment. May carry optional children for hierarchical display.
     * Leaf segments pass {@code null} for {@code children}.
     */
    public record Segment(String key, String label, long tokens, List<Segment> children) {
        public static Segment leaf(String key, String label, long tokens) {
            return new Segment(key, label, tokens, null);
        }
    }
}
